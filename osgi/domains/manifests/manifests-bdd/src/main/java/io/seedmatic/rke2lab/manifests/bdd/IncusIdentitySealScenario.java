package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.manifests.contract.IncusIdentitySealInput;
import io.seedmatic.rke2lab.manifests.contract.IncusIdentitySealInput.HostIncusIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The incus-identity seal scion — the in-container {@code @SeedScenario} the grow sows (before
 * provisioning) to make the CAPN provider's incus identity available to this run. The twin of
 * {@link ReplicatorSecretsSealScenario} (reads {@code .secrets} through the read-only {@link
 * SecretsGateway} and files SEALED for the manifests synthesis to reveal), plus the {@code
 * cluster-pki} amendment shape: the host supplies the three host-world creds ({@code
 * serverAddress}, {@code serverCert}, {@code clientCert}) via the {@link IncusIdentitySealInput}
 * runbook input, and this scion reads the fourth — the client KEY — itself from {@code
 * .secrets:incus.capn.clientKey}, so the private key never rides the amendment wire.
 *
 * <p>It assembles {@link IncusIdentityMaterial} and files it SEALED at {@link IncusIdentityCase};
 * {@code ManifestSynthesisScenario} reveals it in-container and {@code
 * ClusterApiWorkloadManifestsUnit} renders the {@code <host>-incus-identity} Secret onto the
 * NODE_BOOTSTRAP lane. Unlike the replicator secrets, the incus identity is MANDATORY — CAPN cannot
 * authenticate to incus without it, so a workload cannot be grown. There is NO fallback: any gap
 * (no amendment, an incomplete host cred, a missing {@code incus.capn.clientKey}) raises an
 * explicit exception rather than silently skipping.
 */
@SeedScenario
public class IncusIdentitySealScenario
    extends ScenarioTestBase<
        IncusIdentitySealScenario.Given,
        IncusIdentitySealScenario.When,
        IncusIdentitySealScenario.Then>
    implements CellarReceiver<ScenarioCellar>,
        InputReceiver<IncusIdentitySealInput>,
        ScenarioPlayer.Playable {

  /** The inbound channel the runbook handler seeds the host-world creds through. */
  @RegisterExtension
  public static final ScenarioInputSeed<IncusIdentitySealInput> INPUT =
      new ScenarioInputSeed<>(IncusIdentitySealInput.class, "incus-identity-seal-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ScenarioCellar cellar;
  @MonotonicNonNull private IncusIdentitySealInput input;

  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  @OsgiService(await = false)
  private Optional<SecretsGateway> secrets = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public void receiveInput(IncusIdentitySealInput input) {
    this.input = input;
  }

  @Test
  void the_incus_identity_is_sealed() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    final IncusIdentitySealInput trigger =
        Objects.requireNonNull(input, "the incus identity input was not seeded before the body");
    given().the_host_world_creds();
    when().the_incus_identity_is_resolved(trigger.host(), secrets);
    then().the_incus_identity_is_filed(plot, tx);
  }

  /** GIVEN — the host-world creds + the operator {@code .secrets} (narration). */
  public static class Given extends Stage<Given> {
    public Given the_host_world_creds() {
      return self();
    }
  }

  /** WHEN — the identity is assembled from the host creds + the {@code .secrets} client key. */
  public static class When extends Stage<When> {

    private final SeedCodec codec = new SeedCodec();

    @ProvidedScenarioState IncusIdentityMaterial material;

    // The incus identity is MANDATORY — CAPN cannot authenticate to incus without it, so a workload
    // cannot be grown. The host creds arrive already-complete (the record's constructor rejects a
    // blank field); the only gap left to guard here is the client key, and a miss fails loud rather
    // than falling back.
    @As("the incus identity is resolved")
    public When the_incus_identity_is_resolved(
        @Hidden HostIncusIdentity host, @Hidden Optional<SecretsGateway> secrets) {
      final String clientKey =
          secrets
              .flatMap(gateway -> gateway.read("incus"))
              .flatMap(this::capnClientKey)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "cannot grow: the capn client key is missing at"
                              + " .secrets:incus.capn.clientKey"));
      this.material =
          new IncusIdentityMaterial(
              host.serverAddress(), host.serverCert(), host.clientCert(), clientKey);
      return self();
    }

    private Optional<String> capnClientKey(String incusJson) {
      final JsonNode key = codec.decode(incusJson).path("capn").path("clientKey");
      return key.isMissingNode() || key.asText().isBlank()
          ? Optional.empty()
          : Optional.of(key.asText());
    }
  }

  /** THEN — the assembled material is filed SEALED at {@link IncusIdentityCase}. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState IncusIdentityMaterial material;

    @As("the incus identity is filed")
    public Then the_incus_identity_is_filed(@Hidden Parcel parcel, @Hidden Cellar cellar) {
      cellar.store(parcel, IncusIdentityCase.INCUS_IDENTITY, material, Sensitivity.SEALED);
      return self();
    }
  }
}
