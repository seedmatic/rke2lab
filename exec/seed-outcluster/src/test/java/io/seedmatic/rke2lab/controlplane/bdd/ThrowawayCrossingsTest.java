package io.seedmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tngtech.jgiven.CurrentStep;
import com.tngtech.jgiven.attachment.Attachment;
import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.seedmatic.rke2lab.controlplane.config.ConfigLoader;
import io.seedmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.seedmatic.rke2lab.host.runtime.GrowProfile;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.seed.bdd.SowAndGraftStage;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.SeedBroker;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A throwaway grow sows none of the crossings that take a fleet identity: one case per crossing,
 * each played on the step itself with a broker that records every sow. Under the fleet the same
 * step reaches the broker for that soil, so a step that forgets the profile fails its own case.
 */
class ThrowawayCrossingsTest {

  /** Raised by the broker on its first sow, so a fleet step stops before the graft. */
  private static final class Sown extends RuntimeException {
    Sown(String soil) {
      super(soil);
    }
  }

  private static final class RecordingBroker implements SeedBroker {
    final List<String> soils = new ArrayList<>();

    @Override
    public SeedEnvelope sow(SeedCoordinate wanted, Cellar cellar, SeedEnvelope seed) {
      soils.add(wanted.domain());
      throw new Sown(wanted.domain());
    }

    @Override
    public boolean serves(SeedCoordinate wanted) {
      return true;
    }
  }

  private static final class NamedStep implements CurrentStep {
    String name;

    @Override
    public void addAttachment(Attachment attachment) {}

    @Override
    public void setExtendedDescription(String description) {}

    @Override
    public void setName(String name) {
      this.name = name;
    }

    @Override
    public void setComment(String comment) {}
  }

  private record Played(List<String> soils, Optional<String> stepName) {}

  private static BootstrapConfig config() {
    return BootstrapConfig.from(
        Rke2labConfig.from(
            ConfigLoader.ofNestedRoot(
                Map.of(
                    "cluster", Map.of("host", "nikopol", "role", "mgmt"),
                    "incus", Map.of("project", "throwaway-nikopol", "configDir", "/tmp/incus")))));
  }

  private static Played play(GrowProfile profile, Consumer<ClusterSeedScenario.When> crossing) {
    final RecordingBroker broker = new RecordingBroker();
    final NamedStep step = new NamedStep();
    final ClusterSeedScenario.When when = new ClusterSeedScenario.When();
    when.sowAndGraft = new SowAndGraftStage();
    when.gardening = new Gardening((OsgiConnection) null, broker);
    when.config = config();
    when.profile = profile;
    when.currentStep = step;
    when.workloadClusterNames = JsonNodeFactory.instance.arrayNode();
    when.incusIdentityHostCreds = JsonNodeFactory.instance.objectNode();
    try {
      crossing.accept(when);
    } catch (Sown reached) {
      // the fleet's step reached the broker: what the case asserts on
    }
    return new Played(List.copyOf(broker.soils), Optional.ofNullable(step.name));
  }

  private static void assertFleetOnly(String soil, Consumer<ClusterSeedScenario.When> crossing) {
    final Played throwaway = play(GrowProfile.THROWAWAY, crossing);
    assertEquals(List.of(), throwaway.soils(), "a throwaway grow sowed " + soil);
    assertTrue(
        throwaway.stepName().orElse("").startsWith("skipped by the throwaway profile: "),
        "the runbook names the skip of " + soil);
    final Played fleet = play(GrowProfile.FLEET, crossing);
    assertEquals(List.of(soil), fleet.soils(), "the fleet sows " + soil);
    assertFalse(fleet.stepName().isPresent(), "the fleet step keeps its name");
  }

  @Test
  void no_cluster_ca_is_sealed_from_the_fleet_authority() {
    assertFleetOnly("cluster-pki", w -> w.the_cluster_ca_is_sealed(null, null));
  }

  @Test
  void no_incus_identity_is_sealed() {
    assertFleetOnly("incus-identity", w -> w.the_incus_identity_is_sealed(null, null));
  }

  @Test
  void the_org_github_app_is_not_registered() {
    assertFleetOnly("ghapp", w -> w.the_github_app_is_registered(null, null));
  }

  @Test
  void no_replicator_secret_is_sealed() {
    assertFleetOnly("replicator-secrets", w -> w.the_replicator_secrets_are_sealed(null, null));
  }

  @Test
  void the_app_webhook_is_not_re_pointed() {
    assertFleetOnly("ghapp-webhook", w -> w.the_github_app_webhook_is_reconciled(null, null));
  }

  @Test
  void the_systemd_adapter_is_not_launched_by_the_grow() {
    assertFleetOnly("systemd", w -> w.the_systemd_adapter_is_launched(null, null));
  }

  @Test
  void the_cluster_readiness_is_not_probed_by_the_grow() {
    assertFleetOnly("cluster", w -> w.the_cluster_becomes_ready(null, null));
  }

  @Test
  void a_crossing_that_reads_a_secret_is_refused_by_the_throwaway_gateway() {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> ClusterSeedScenario.Given.NO_SECRETS.read("github.app.privateKey"));
    assertTrue(refused.getMessage().contains("github.app.privateKey"), refused.getMessage());
  }
}
