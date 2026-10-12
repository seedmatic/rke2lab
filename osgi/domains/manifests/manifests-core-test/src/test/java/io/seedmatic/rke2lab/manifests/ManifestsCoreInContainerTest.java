package io.seedmatic.rke2lab.manifests;

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
 * The bare-JVM proxy (VSCode-clickable) that runs manifests-core's actor tests IN-CONTAINER. It
 * boots a Felix carrying the JUnit + jGiven worlds (via {@link ScenarioTestkit#felix()}), installs
 * the manifests-core host bundle and this {@code -test} fragment plus manifests-core's whole
 * runtime graph — the cdk8s carrier (with its embedded flat closure) and the
 * systemd-cdk8s-manifests fragment that rides it, the sibling ports, the pipeline engine,
 * unitrepo-core, and the third-party OSGi bundles — resolves the host (attaching the fragment, OSGi
 * Core §3.14), then drives a JUnit Platform Launcher FROM INSIDE the framework via {@code
 * ManifestsCoreTests}. Resolving the host IS the live proof that the cdk8s bundle-to-bundle wiring
 * (org.cdk8s/software.constructs exported by the carrier, no host-flat leak) holds in-container.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
// (io.seedmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkLog) — it raises Felix's own
// felix.log.level so
// the
// resolver prints WHICH requirement could not be wired to System.out (resolve() otherwise returns a
// bare false). Left as a comment: it is the lever to reach for, not a permanent dependency.
class ManifestsCoreInContainerTest {

  // Select the fixture by what it declares (type=fixture, suite, role); its host (manifests-core)
  // comes from Fragment-Host.
  private static final String CORE_FIXTURE = "(&(type=fixture)(suite=manifests)(role=core))";
  private static final String RUNNER_FQN = "io.seedmatic.rke2lab.manifests.ManifestsCoreTests";
  private static final String SOPS_EDGE = "(&(type=edge)(edge=sops))";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // manifests-core carries @Components (YamlMapper, the Default…Services), so its bundle
          // Requires the DS extender (osgi.extender=osgi.component); felix.scr must run for
          // manifests-core to resolve and activate.
          .withScr()
          // The ONE package system-exported is seed.broker.port (the host↔OSGi membrane —
          // SeedHandler / SeedEnvelope, published by the host in prod). manifests-core's
          // SeedHandler
          // reflectors import it, and it is a MEMBRANE package the closure walk never stages (it is
          // host-provided, not a normal bundle); the test mirrors prod by system-exporting it. The
          // synthesis grammar itself is internal to manifests-core (un-exported), not a seam, and
          // everything else the runtime graph needs — manifests-contract, the cdk8s carrier and its
          // systemd fragment, seed-broker-codec/shape (+ victools-wrap for the shape engine),
          // unitrepo-core, jackson, ipaddress, snakeyaml, commons-compress — is derived from the
          // host's manifest in the test body via installImportClosureOf.
          .systemPackages("io.seedmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit runner world — the proxy's own infrastructure, the single shared declaration.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> actorTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host),
    // pulls the host's own import closure, resolves+starts it, and runs the front-door
    // in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(CORE_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          // The sops edge exports nothing — it only publishes the sops services — so no import
          // closure reaches it; RenderedSecretSealTest seals with it, so it is installed by its
          // capability. Its own imports (cluster-pki-contract, slf4j) are already in the host's
          // closure; a second closure walk would install the osgi.core API jar, which never
          // resolves and fails the whole graph. The whole graph is started: the edge's
          // components publish only from an ACTIVE bundle.
          toResolve.addAll(f.installMatching(SOPS_EDGE));
          return new Provisioning(host, toResolve, true);
        });
  }
}
