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
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The ghapp scion — the in-container {@code @SeedScenario} the grow sows to make the one org-owned
 * GitHub App's {@link GithubAppCredentials} available to this run. It is a *pure rehydrate*: it
 * does NOT declare the App. Declaration (create the App from GitHub's pre-filled form, generate the
 * key, install it, resolve the installation id, write {@code .secrets}) is an OUT-OF-BAND operator
 * ceremony driven by the standalone {@code ghapp} CLI ({@code seed-master}'s subcommand) — kept out
 * of the grow precisely because {@code seed-master} runs under {@code pulumi up}, whose gRPC engine
 * captures the console and makes a mid-run browser/prompt unusable.
 *
 * <p>Two branches, never a browser, never a hard fail here:
 *
 * <ol>
 *   <li>credentials already in the cellar → no-op (this run sealed them, idempotent-keep);
 *   <li>else in {@code .secrets} (via the read-only {@link SecretsGateway}) → rehydrate: seal to
 *       the cellar. This is the steady state once the operator has run {@code ghapp seed}.
 * </ol>
 *
 * <p>Absence — no cellar case and no {@code github.app} block in {@code .secrets} — seals nothing.
 * That is the honest local skip: a required-credential absence is a HARD fail at the CONSUMPTION
 * site (the writer mint / the Flux-Secret render), never here (see {@code
 * github-credential-model.adoc}). The operator runs the {@code ghapp} CLI first; if they did not,
 * the downstream mint/render fails loudly.
 *
 * <p>GIVEN the operator's GitHub session; WHEN the App credentials are rehydrated (from the cellar
 * or {@code .secrets}); THEN they are filed SEALED. The G/W/T rule holds: the WHEN establishes, the
 * THEN seals.
 */
@SeedScenario
public class GithubAppScenario
    extends ScenarioTestBase<
        GithubAppScenario.Given, GithubAppScenario.When, GithubAppScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private static final String SECRETS_KEY = "github";

  private final Scenario<Given, When, Then> scenario = createScenario();

  /**
   * Injected by {@code ScenarioCellarExtension} before the body (store→tag, durable fallthrough).
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The host's {@code .secrets} door — optional; absent under a survey means no rehydrate. */
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
  void the_github_app_credentials_are_rehydrated() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().the_operators_github_session();
    when().the_github_app_credentials_are_resolved(plot, tx, secrets);
    then().the_github_app_credentials_are_filed(plot, tx);
  }

  /** GIVEN — the operator's GitHub session (narration; declaration is the out-of-band CLI). */
  public static class Given extends Stage<Given> {

    public Given the_operators_github_session() {
      return self();
    }
  }

  /**
   * WHEN — the App credentials are resolved (cellar hit; else rehydrated from {@code .secrets}).
   */
  public static class When extends Stage<When> {

    private final SeedCodec codec = new SeedCodec();

    /** Credentials to seal to the cellar (from a cellar miss: rehydrated from {@code .secrets}). */
    @ProvidedScenarioState Optional<GithubAppCredentials> credentials = Optional.empty();

    @As("the github app credentials are resolved")
    public When the_github_app_credentials_are_resolved(
        @Hidden Parcel parcel, @Hidden Cellar cellar, @Hidden Optional<SecretsGateway> secrets) {
      if (cellar
          .fetch(parcel, GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class)
          .isPresent()) {
        return self();
      }
      this.credentials =
          secrets.flatMap(gateway -> gateway.read(SECRETS_KEY)).flatMap(this::fromJson);
      return self();
    }

    private Optional<GithubAppCredentials> fromJson(String json) {
      final JsonNode app = codec.decode(json).path("app");
      if (app.path("privateKeyPem").isMissingNode()) {
        return Optional.empty(); // .github present but no .github.app sub-block (survey/ephemeral)
      }
      return Optional.of(
          new GithubAppCredentials(
              app.path("appId").asText(),
              app.path("installationId").asText(),
              app.path("privateKeyPem").asText()));
    }
  }

  /** THEN — the credentials are filed SEALED at {@code GhAppCoordinate.GITHUB_APP}. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Optional<GithubAppCredentials> credentials;

    @As("the github app credentials are filed")
    public Then the_github_app_credentials_are_filed(@Hidden Parcel parcel, @Hidden Cellar cellar) {
      credentials.ifPresent(
          value -> cellar.store(parcel, GhAppCoordinate.GITHUB_APP, value, Sensitivity.SEALED));
      return self();
    }
  }
}
