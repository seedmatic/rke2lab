package io.seedmatic.rke2lab.ghapp.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppWebhookConfigurer;
import io.seedmatic.rke2lab.ghapp.contract.WebhookConfig;
import io.seedmatic.rke2lab.ghapp.contract.WebhookReconcileInput;
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
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The ghapp webhook-reconcile scion — the in-container {@code @SeedScenario} the grow sows to point
 * the one org-owned GitHub App's webhook at the current Tailscale funnel (its URL changes on a
 * funnel rename) and set its shared HMAC secret. It closes the last operator-manual GitHub-UI step:
 * the webhook {@code url}/{@code secret} that would otherwise be edited by hand after every rename
 * are reconciled here, AS the App, on each grow.
 *
 * <p>It consumes what earlier scions already sealed/hold: the App {@link GithubAppCredentials} from
 * the cellar (the {@code ghapp} registration scion filed them) and the shared {@code
 * github.webhook.secret} from {@code .secrets} (the same value the replicator-secrets scion seals
 * and Pipelines-as-Code validates against). The funnel URL — the one host-held fact — arrives as
 * the {@link WebhookReconcileInput}'s FUNNEL amendment through the {@link #INPUT} channel.
 *
 * <p>Gardening-gated at the EDGE: {@link GithubAppWebhookConfigurer} carries {@code
 * rke2lab.gardening=cultivating}, so under a survey/preview frontier the service is filtered out
 * and this scion no-ops (PENDS) rather than calling GitHub. Absence of any input (no credentials in
 * the cellar, no secret in {@code .secrets}, no configurer) is the honest local skip — the
 * reconcile is a best-effort convenience, never a hard gate on the grow.
 *
 * <p>GIVEN the desired funnel endpoint; WHEN the App credentials + webhook secret are resolved;
 * THEN the App webhook is reconciled to that endpoint.
 */
@SeedScenario
public class GithubAppWebhookScenario
    extends ScenarioTestBase<
        GithubAppWebhookScenario.Given,
        GithubAppWebhookScenario.When,
        GithubAppWebhookScenario.Then>
    implements CellarReceiver<ScenarioCellar>,
        InputReceiver<WebhookReconcileInput>,
        ScenarioPlayer.Playable {

  private static final String SECRETS_KEY = "github";

  /** The inbound channel the runbook handler seeds the {@link WebhookReconcileInput} through. */
  @RegisterExtension
  public static final ScenarioInputSeed<WebhookReconcileInput> INPUT =
      new ScenarioInputSeed<>(WebhookReconcileInput.class, "ghapp-webhook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ScenarioCellar cellar;
  @MonotonicNonNull private WebhookReconcileInput input;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The host's {@code .secrets} door — optional; absent under a survey means no secret. */
  @OsgiService(await = false)
  private Optional<SecretsGateway> secrets = Optional.empty();

  /** The live GitHub webhook edge — absent under a survey (gardening-gated) means no reconcile. */
  @OsgiService(await = false)
  private Optional<GithubAppWebhookConfigurer> configurer = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public void receiveInput(WebhookReconcileInput input) {
    this.input = input;
  }

  @Test
  void the_github_app_webhook_is_reconciled() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    final WebhookReconcileInput trigger =
        Objects.requireNonNull(input, "the webhook input was not seeded before the body");
    given().the_desired_funnel_endpoint(trigger.funnelUrl());
    when().the_app_credentials_and_webhook_secret_are_resolved(plot, tx, secrets);
    then().the_app_webhook_is_reconciled(configurer);
  }

  /** GIVEN — the desired funnel endpoint the App must POST its events to. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState String funnelUrl;

    public Given the_desired_funnel_endpoint(String funnelUrl) {
      this.funnelUrl = funnelUrl;
      return self();
    }
  }

  /** WHEN — the App credentials (cellar) and the shared webhook secret ({@code .secrets}). */
  public static class When extends Stage<When> {

    private final SeedCodec codec = new SeedCodec();

    /** The resolved App credentials — empty when the ghapp registration sealed nothing. */
    @ProvidedScenarioState Optional<GithubAppCredentials> credentials = Optional.empty();

    /** The shared webhook HMAC secret — empty under a survey / before the seal filed. */
    @ProvidedScenarioState Optional<String> webhookSecret = Optional.empty();

    @As("the app credentials and webhook secret are resolved")
    public When the_app_credentials_and_webhook_secret_are_resolved(
        @Hidden Parcel parcel, @Hidden Cellar cellar, @Hidden Optional<SecretsGateway> secrets) {
      this.credentials =
          cellar.fetch(parcel, GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class);
      this.webhookSecret =
          secrets.flatMap(gateway -> gateway.read(SECRETS_KEY)).flatMap(this::webhookSecret);
      return self();
    }

    private Optional<String> webhookSecret(String json) {
      final JsonNode secret = codec.decode(json).path("webhook").path("secret");
      return secret.isMissingNode() || secret.asText().isBlank()
          ? Optional.empty()
          : Optional.of(secret.asText());
    }
  }

  /** THEN — the App webhook is reconciled, if every input and the live edge are present. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState String funnelUrl;
    @ExpectedScenarioState Optional<GithubAppCredentials> credentials;
    @ExpectedScenarioState Optional<String> webhookSecret;

    @As("the app webhook is reconciled")
    public Then the_app_webhook_is_reconciled(
        @Hidden Optional<GithubAppWebhookConfigurer> configurer) {
      configurer.ifPresent(
          edge ->
              credentials.ifPresent(
                  creds ->
                      webhookSecret.ifPresent(
                          secret -> edge.configure(creds, new WebhookConfig(funnelUrl, secret)))));
      return self();
    }
  }
}
