package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.InContainerScenarios.Provisioning;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.seedmatic.rke2lab.scenario.testkit.ScenarioTestkit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.log.LogLevel;

/**
 * The bare-JVM proxy (VSCode-clickable) that runs the manifests scion's in-container proof. It
 * boots a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot
 * closure (via {@link ScenarioTestkit#felix()}, since the scenario is a jGiven ScenarioTest) —
 * installs the manifests-bdd host bundle and this {@code -test} fragment PLUS manifests-core (the
 * real synthesis impl) and BOTH their import closures (the cdk8s carrier + systemd fragment, the
 * sibling ports, the pipeline engine, unitrepo-core, and the third-party OSGi bundles), resolves
 * the host (attaching the fragment, OSGi Core §3.14), then drives {@code ManifestsBddTests} FROM
 * INSIDE the framework. Resolving the host IS the live proof that the scion's graph wires
 * in-container.
 *
 * <p>manifests-core is installed EXPLICITLY (not left to the host's import closure): the scenario
 * reaches the synthesis through its {@code ManifestSynthesisService} interface, which lives in
 * manifests-contract, so the closure wires that package to manifests-contract and never pulls the
 * impl bundle — the {@code @Component}s would then be absent and the {@code @OsgiService} would
 * find nothing. Unlike the other scion proofs, this scion resolves the REAL synthesis
 * (manifests-core's DS {@code @Component}s), so {@code .withScr()} must run for them to activate.
 * Its one non-SCR collaborator is the {@link
 * io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter} edge (a pure external-tool seam) — the
 * passenger registers a stub in-container, on the shared fragment loader, so nothing crosses to the
 * host JVM and only {@code seed.broker.port} (the one true host↔OSGi membrane) is system-exported.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
// Felix's own log level so the resolver prints WHICH requirement could not be wired.
class ManifestsBddInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-bdd)
  // comes from Fragment-Host.
  private static final String BDD_FIXTURE = "(&(type=fixture)(suite=manifests)(role=bdd))";
  private static final String RUNNER_FQN = "io.seedmatic.rke2lab.manifests.bdd.ManifestsBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core carries the synthesis @Components, so its bundle Requires the DS
          // extender
          // (osgi.extender=osgi.component); felix.scr must run for them to resolve and activate —
          // the scenario resolves the REAL synthesis, not mocks.
          .withScr()
          // The ONE package still system-exported is seed.broker.port (RunGate + SeedEnvelope — the
          // one true host↔OSGi membrane, published by the host in prod). Everything else
          // (manifests-contract, the cdk8s carrier, seed-broker-codec, jackson, jGiven) is derived
          // from the import closures in the test body; manifests-core itself is installed
          // explicitly
          // (its service package resolves to manifests-contract, so the closure never pulls it).
          .systemPackages("io.seedmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          .withJUnitRunner()
          .build();

  // The bundle carrying the REAL synthesis @Components (DefaultManifestSynthesisService and its
  // collaborators). Its service interfaces live in manifests-contract, so the host's import closure
  // wires the contract package to manifests-contract and NEVER pulls the impl bundle — this scion
  // must install it explicitly (unlike incus/cluster, which mock their collaborators and need
  // no impl bundle in-container).
  private static final String CORE_BSN = "io.seedmatic.rke2lab.manifests.core";

  @TestFactory
  Stream<DynamicTest> scionTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host)
    // AND manifests-core (the real synthesis impl), pulls both import closures, resolves+starts the
    // graph, and runs the front-door in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(BDD_FIXTURE).host();
          final Bundle core = f.install(CORE_BSN);
          final List<Bundle> toResolve = new ArrayList<>(List.of(host, core));
          toResolve.addAll(f.installImportClosureOf(host, core));
          return new Provisioning(host, toResolve, true);
        });
  }
}
