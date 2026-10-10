package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxReceiverManifestsUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The {@code pipelines-as-code-secret} that configures Pipelines-as-Code with OUR baked GitHub App
 * — the CI twin of {@link io.seedmatic.rke2lab.manifests.units.gitops.GithubAppSecretManifestsUnit}
 * (which configures Flux's native App auth). Both consume the SAME {@link GithubAppCredentials} the
 * manifests scion reveals from the sealed {@code github-app} cellar case; both ride the {@code
 * NODE_BOOTSTRAP} lane (real App key, never on the reconciled branch). PaC reads this secret to act
 * AS the App: verify webhooks, clone, set commit statuses, and mint the {@code git_auth_secret} it
 * injects into each PipelineRun.
 *
 * <p>Keys (the names PaC reads for a GitHub App):
 *
 * <ul>
 *   <li>{@code github-application-id} — the App id.
 *   <li>{@code github-private-key} — the App's private key (PEM), from which PaC mints installation
 *       tokens.
 *   <li>{@code webhook.secret} — the App-webhook HMAC PaC validates incoming payloads against. The
 *       operator-chosen {@code github.webhook.secret}, read from the {@code flux-webhook-token}
 *       replicator source already on the context — the SINGLE source shared by the App webhook, the
 *       Flux receiver, and PaC (no duplicate reveal, no new synthesis material). Omitted when no
 *       replicator sources are sealed (a survey); PaC then runs without payload validation until it
 *       lands.
 * </ul>
 *
 * <p>Namespace {@code tekton-pipelines}: the Tekton operator (profile {@code all}, {@code
 * targetNamespace: tekton-pipelines}) installs the PaC controller there, so its secret lives there
 * too (twin of {@link PacWebhookManifestsUnit}'s funnel backend).
 *
 * <p>Absence — no App credentials sealed (ephemeral / survey runs) — is an empty {@code
 * Optional<GithubAppCredentials>} and the unit renders nothing (the honest local-render skip, as
 * its gitops twin).
 */
public final class PacSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/pac-secret";

  private static final String NAMESPACE = "tekton-pipelines";

  private static final String SECRET_NAME = "pipelines-as-code-secret";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "pac-secret", true);

  public PacSecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<GithubAppCredentials> maybeMaterial =
        ManifestSynthesisContext.current().githubApp();
    if (maybeMaterial.isEmpty()) {
      return;
    }
    final GithubAppCredentials material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-pipelines-as-code",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SECRET_NAME)
                        .namespace(NAMESPACE)
                        .annotations(packageProfile.packageAnnotations())
                        .build())
                .build());

    final Base64.Encoder b64 = Base64.getEncoder();
    final Map<String, String> data = new LinkedHashMap<>();
    data.put(
        "github-application-id",
        b64.encodeToString(material.appId().getBytes(StandardCharsets.UTF_8)));
    data.put(
        "github-private-key",
        b64.encodeToString(material.privateKeyPem().getBytes(StandardCharsets.UTF_8)));
    // webhook.secret — the App-webhook HMAC PaC validates incoming payloads against. Absent on runs
    // with no replicator sources sealed → PaC runs without payload validation until it lands.
    webhookSecret()
        .ifPresent(
            token ->
                data.put(
                    "webhook.secret", b64.encodeToString(token.getBytes(StandardCharsets.UTF_8))));

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(JsonPatch.add("/data", data));
  }

  /**
   * The shared webhook HMAC secret ({@code github.webhook.secret}), read from the {@code
   * flux-webhook-token} replicator source already revealed onto the context — the SINGLE source of
   * truth for the value the App webhook, the Flux receiver, and PaC all validate against (no
   * duplicate reveal, no new synthesis material). Empty when no replicator sources are sealed (a
   * survey / before the seal filed), and the secret then renders without {@code webhook.secret}.
   */
  private Optional<String> webhookSecret() {
    return ManifestSynthesisContext.current()
        .replicatorSources()
        .flatMap(
            sources ->
                sources.sources().stream()
                    .filter(s -> FluxReceiverManifestsUnit.WEBHOOK_TOKEN_SECRET.equals(s.name()))
                    .findFirst())
        .map(source -> source.stringData().get("token"))
        .filter(token -> token != null && !token.isBlank());
  }
}
