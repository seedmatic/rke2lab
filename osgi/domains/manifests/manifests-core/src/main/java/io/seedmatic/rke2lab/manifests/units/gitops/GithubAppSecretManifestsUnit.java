package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Manifest unit that creates the {@code githubapp} Secret for Flux's native GitHub App auth — the
 * twin of {@link SopsAgeSecretManifestsUnit}, riding the same LOCAL-ONLY bootstrap lane.
 *
 * <p>The Secret carries the one org-owned App's id, installation id, and private key, in the shape
 * the Flux source-controller reads for a {@code GitRepository}'s {@code spec.secretRef}: {@code
 * githubAppID}, {@code githubAppInstallationID}, {@code githubAppPrivateKey}. Flux self-mints and
 * self-refreshes a {@code contents:read} pull token from these — no deploy key, no PAT. It is the
 * pull-side companion to the {@code FluxRoot} GitRepository that references it.
 *
 * <p><b>The unit only RENDERS.</b> The App credentials are a prerequisite, revealed upstream by the
 * manifests scion from the sealed {@code GhAppCoordinate.GITHUB_APP} cellar case and handed in as
 * {@link GithubAppCredentials} on {@link ManifestSynthesisContext} — the same channel as {@code
 * AdminCredentials} / {@code SopsAgeMaterial}. This unit never reveals a cellar case or shells a
 * tool itself; it embeds the identifiers + key into the Secret, base64-encoded as Kubernetes
 * requires.
 *
 * <p>Absence — no App credentials sealed (ephemeral / test / bare-survey runs) — is carried as an
 * empty {@code Optional<GithubAppCredentials>}, and the unit then renders nothing (a
 * required-credential absence is a hard fail at the mint site, never here — this is the honest
 * local-render skip).
 *
 * <p><b>Note:</b> Stage A bootstrap — the Secret is applied at master bootstrap time from the
 * node's local-only seed (never pushed to the branch it authenticates the pull of), so Flux comes
 * up WITH the App key and can mint the first pull token.
 */
public final class GithubAppSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/githubapp";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "githubapp", true);

  public GithubAppSecretManifestsUnit() {
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
            "secret-githubapp",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("githubapp")
                        .namespace("flux-system")
                        .annotations(packageProfile.packageAnnotations())
                        .build())
                .build());

    final Base64.Encoder b64 = Base64.getEncoder();
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "githubAppID",
                b64.encodeToString(material.appId().getBytes(StandardCharsets.UTF_8)),
                "githubAppInstallationID",
                b64.encodeToString(material.installationId().getBytes(StandardCharsets.UTF_8)),
                "githubAppPrivateKey",
                b64.encodeToString(material.privateKeyPem().getBytes(StandardCharsets.UTF_8)))));
  }
}
