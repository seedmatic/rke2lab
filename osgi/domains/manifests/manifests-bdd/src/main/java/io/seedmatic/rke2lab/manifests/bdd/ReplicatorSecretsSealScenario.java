package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial.SourceSecret;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The replicator-secrets scion — the in-container {@code @SeedScenario} the grow sows to make the
 * mittwald-replicator SOURCE secrets available to this run. A *pure rehydrate*, the twin of {@code
 * GithubAppScenario}: it reveals nothing new, it reads the operator's {@code .secrets} through the
 * read-only {@link SecretsGateway} and files the assembled {@link ReplicatorSourceSecretsMaterial}
 * SEALED in the cellar for the manifests synthesis to reveal (in-container) and {@code
 * ReplicatorManifestsUnit} to render onto the node-bootstrap lane.
 *
 * <p>The mapping lives in {@code .secrets}: {@code kubernetes.sourceNamespace} + {@code
 * kubernetes.secrets.<key>.{name,replicateTo}} name each source secret and its allowed target
 * namespaces; the credential data comes from the matching {@code flox.*} / {@code github.*} /
 * {@code tailscale.*} block. The bridged shapes are the FloxHub token, the GitHub webhook secret,
 * and the tailscale oauth — the one domain-specific step the split {@code .secrets} layout
 * requires. (Tekton git/docker source secrets were dropped: git auth flows from the App via PaC's
 * dynamic git_auth, and no in-cluster build pulls a private registry.)
 *
 * <p>Absence — no {@code kubernetes.secrets} block — seals an empty material (the honest local
 * skip): the render then produces no source secrets and the replicator's targets stay at their
 * placeholders. A required-credential absence surfaces at the pipeline that needs it, never here.
 */
@SeedScenario
public class ReplicatorSecretsSealScenario
    extends ScenarioTestBase<
        ReplicatorSecretsSealScenario.Given,
        ReplicatorSecretsSealScenario.When,
        ReplicatorSecretsSealScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ScenarioCellar cellar;

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

  @Test
  void the_replicator_source_secrets_are_rehydrated() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().the_operator_secrets();
    when().the_replicator_source_secrets_are_resolved(secrets);
    then().the_replicator_source_secrets_are_filed(plot, tx);
  }

  /** GIVEN — the operator's {@code .secrets} (narration; the door is the read-only gateway). */
  public static class Given extends Stage<Given> {
    public Given the_operator_secrets() {
      return self();
    }
  }

  /**
   * WHEN — the source secrets are assembled from {@code .secrets} (empty when the block is absent).
   */
  public static class When extends Stage<When> {

    private final SeedCodec codec = new SeedCodec();

    @ProvidedScenarioState
    ReplicatorSourceSecretsMaterial material = new ReplicatorSourceSecretsMaterial(List.of());

    @As("the replicator source secrets are resolved")
    public When the_replicator_source_secrets_are_resolved(
        @Hidden Optional<SecretsGateway> secrets) {
      this.material = secrets.map(this::assemble).orElse(this.material);
      return self();
    }

    private ReplicatorSourceSecretsMaterial assemble(final SecretsGateway gateway) {
      final Optional<JsonNode> kubernetes = read(gateway, "kubernetes");
      if (kubernetes.isEmpty()) {
        return new ReplicatorSourceSecretsMaterial(List.of());
      }
      final JsonNode k = kubernetes.orElseThrow();
      final String sourceNamespace = k.path("sourceNamespace").asText();
      final JsonNode secretsMap = k.path("secrets");
      final Optional<JsonNode> tailscale = read(gateway, "tailscale");
      final Optional<JsonNode> flox = read(gateway, "flox");
      final Optional<JsonNode> github = read(gateway, "github");

      final List<SourceSecret> sources = new ArrayList<>();
      flox.ifPresent(
          fx -> floxSecret(secretsMap.path("flox"), sourceNamespace, fx).ifPresent(sources::add));
      github.ifPresent(
          gh ->
              webhookSecret(secretsMap.path("web-hook"), sourceNamespace, gh)
                  .ifPresent(sources::add));
      tailscale.ifPresent(
          ts ->
              oauthSecret(secretsMap.path("tailscale"), sourceNamespace, ts.path("oauth"))
                  .ifPresent(sources::add));
      return new ReplicatorSourceSecretsMaterial(List.copyOf(sources));
    }

    private Optional<SourceSecret> floxSecret(
        final JsonNode mapping, final String namespace, final JsonNode flox) {
      if (mapping.path("name").isMissingNode() || flox.path("token").isMissingNode()) {
        return Optional.empty();
      }
      return Optional.of(
          new SourceSecret(
              mapping.path("name").asText(),
              namespace,
              "Opaque",
              Map.of("token", flox.path("token").asText()),
              namespaces(mapping.path("replicateTo"))));
    }

    /**
     * The Flux Receiver webhook HMAC secret: the operator-chosen shared value at {@code
     * github.webhook.secret} in {@code .secrets}. The rendered Secret's data key stays {@code
     * token} — the key a Flux {@code Receiver} validates against (and the {@code cicd} PaC secret
     * reads), not configurable. The SAME value goes in the GitHub webhook's Secret field.
     */
    private Optional<SourceSecret> webhookSecret(
        final JsonNode mapping, final String namespace, final JsonNode github) {
      final JsonNode secret = github.path("webhook").path("secret");
      if (mapping.path("name").isMissingNode() || secret.isMissingNode()) {
        return Optional.empty();
      }
      return Optional.of(
          new SourceSecret(
              mapping.path("name").asText(),
              namespace,
              "Opaque",
              Map.of("token", secret.asText()),
              namespaces(mapping.path("replicateTo"))));
    }

    private Optional<SourceSecret> oauthSecret(
        final JsonNode mapping, final String namespace, final JsonNode oauth) {
      if (mapping.path("name").isMissingNode() || oauth.isMissingNode()) {
        return Optional.empty();
      }
      return Optional.of(
          new SourceSecret(
              mapping.path("name").asText(),
              namespace,
              "Opaque",
              Map.of(
                  "client_id", oauth.path("id").asText(),
                  "client_secret", oauth.path("token").asText()),
              namespaces(mapping.path("replicateTo"))));
    }

    /** {@code replicateTo} as a list — a JSON array of namespaces, or a comma-separated string. */
    private static List<String> namespaces(final JsonNode replicateTo) {
      if (replicateTo.isArray()) {
        final List<String> out = new ArrayList<>();
        replicateTo.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
      }
      if (replicateTo.isTextual() && !replicateTo.asText().isBlank()) {
        return List.of(replicateTo.asText().split("\\s*,\\s*"));
      }
      return List.of();
    }

    private Optional<JsonNode> read(final SecretsGateway gateway, final String block) {
      return gateway.read(block).map(codec::decode);
    }
  }

  /** THEN — the assembled material is filed SEALED at {@link ReplicatorSecretsCase}. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState ReplicatorSourceSecretsMaterial material;

    @As("the replicator source secrets are filed")
    public Then the_replicator_source_secrets_are_filed(
        @Hidden Parcel parcel, @Hidden Cellar cellar) {
      if (!material.sources().isEmpty()) {
        cellar.store(
            parcel, ReplicatorSecretsCase.REPLICATOR_SECRETS, material, Sensitivity.SEALED);
      }
      return self();
    }
  }
}
