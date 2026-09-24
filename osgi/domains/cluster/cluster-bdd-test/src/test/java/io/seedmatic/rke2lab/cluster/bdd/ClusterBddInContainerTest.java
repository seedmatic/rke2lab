package io.seedmatic.rke2lab.cluster.bdd;

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
 * The bare-JVM proxy (VSCode-clickable) that runs the cluster scion's in-container proof. It boots
 * a Felix carrying BOTH worlds — the JUnit bundles (via the runner) and the jGiven boot closure
 * (via {@link ScenarioTestkit#felix()}, since the scenario is a jGiven ScenarioTest) — installs the
 * cluster-bdd host bundle and this {@code -test} fragment, resolves the host (attaching the
 * fragment, OSGi Core §3.14), then drives {@code ClusterBddTests} FROM INSIDE the framework. The
 * passenger it runs registers its mock collaborators in-container and plays the scenario through
 * the front-door.
 *
 * <p>No domain {@code systemPackages}: cluster-contract + doctor-contract are DE-SEAMED (installed
 * bundles, type=contract), wired bundle-to-bundle by {@code installImportClosureOf} — the mocks the
 * passenger registers on the shared bundle loader are the same Class the scenario reads, nothing
 * crosses to the host JVM. The one package still system-exported here is {@code seed.broker.port}
 * (RunGate + SeedEnvelope — the one true host↔OSGi membrane, published by the host in prod). This
 * is the in-container scion pattern, the twin of {@code IncusBddInContainerTest}.
 *
 * <p>Each in-container test comes back as an encoded {@link String} mapped to one {@link
 * DynamicTest}, so VSCode shows a node per test and a single failure fails alone.
 */
@OsgiWorld
// Flip to LogLevel.DEBUG to troubleshoot a failed in-container resolve/activation
// (Felix then traces WHICH requirement could not be wired); WARNING is the quiet committed default.
@FrameworkLog(LogLevel.WARN)
class ClusterBddInContainerTest {

  // The cluster scion fixture, selected by what it declares (its host cluster-bdd is found through
  // the fragment's Fragment-Host — no host named by a literal here).
  private static final String CLUSTER_BDD_FIXTURE = "(&(type=fixture)(suite=cluster)(role=bdd))";
  private static final String RUNNER_FQN = "io.seedmatic.rke2lab.cluster.bdd.ClusterBddTests";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      ScenarioTestkit.felix() // jGiven boot closure (byte-buddy, jgiven-wrap, slf4j/junit packages)
          // seed.broker.port stays a seam (the one true host↔OSGi membrane: RunGate + SeedEnvelope,
          // published by the host in prod). Everything else — cluster-contract, doctor-contract,
          // seed-broker-codec, jackson, jGiven — is derived from the host bundle's manifest in the
          // test body via installImportClosureOf and wired bundle-to-bundle in-container.
          .systemPackages("io.seedmatic.rke2lab.seed.broker.port;version=1.0.0")
          // The JUnit-Platform runner world (launcher + engine) the front-door drives in-container.
          .withJUnitRunner()
          .build();

  @TestFactory
  Stream<DynamicTest> scionTests() throws Exception {
    // The shared driver installs the fixture + its host (found through the fragment's
    // Fragment-Host),
    // pulls the host's own import closure, resolves+starts it, and runs the front-door
    // in-container.
    return InContainerScenarios.drive(
        felix,
        RUNNER_FQN,
        f -> {
          final Bundle host = f.installFixtureWithHost(CLUSTER_BDD_FIXTURE).host();
          final List<Bundle> toResolve = new ArrayList<>(List.of(host));
          toResolve.addAll(f.installImportClosureOf(host));
          return new Provisioning(host, toResolve, false);
        });
  }
}
