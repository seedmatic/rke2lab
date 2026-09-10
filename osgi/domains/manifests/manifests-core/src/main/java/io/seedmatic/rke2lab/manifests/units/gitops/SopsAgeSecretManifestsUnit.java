package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
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
 * Manifest unit that creates the SOPS age key Secret for Flux decryption.
 *
 * <p>The Secret contains the private age key that Flux uses to decrypt SOPS-encrypted resources
 * (e.g., cloud-init Secrets in Phase 2).
 *
 * <p><b>The unit only RENDERS.</b> The age key is a prerequisite, resolved upstream by the
 * synthesis service's pre-synthesis step (read the {@code rke2-cluster} SSH key, convert it via the
 * {@code ssh-to-age} edge) and handed in as {@link SopsAgeMaterial} on {@link
 * ManifestSynthesisContext} — the same channel as {@code IncusIdentityMaterial} / {@code
 * BootstrapIdentity}. This unit never reads a host file or shells a tool itself; it embeds the key
 * into the Secret, base64-encoded as Kubernetes requires.
 *
 * <p><b>Key derivation:</b> the age key is derived from the {@code rke2-cluster} SSH key managed in
 * nix-darwin-home. Public age key: {@code
 * age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak}. This same public key is added as
 * a recipient in both rke2lab and nix-darwin-home {@code .sops.yaml}, enabling both operator (via
 * git filter) and Flux (in-cluster) to decrypt the same content.
 *
 * <p><b>Note:</b> This is Stage A bootstrap - the Secret is applied at master bootstrap time,
 * enabling Flux to decrypt SOPS-encrypted resources from gitops/ directory.
 */
public final class SopsAgeSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/sops-age";

  /** The Secret name (in flux-system AND the rke2lab-system replica) + its age-key data key. */
  public static final String SECRET_NAME = "sops-age";

  public static final String AGE_KEY = "age.agekey";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "sops-age", true);

  public SopsAgeSecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<SopsAgeMaterial> maybeMaterial =
        ManifestSynthesisContext.current().sopsAgeMaterial();

    // Skip in ephemeral/test mode: no SSH key-store was present, so the pre-synthesis step supplied
    // no real age key.
    if (maybeMaterial.isEmpty()) {
      return;
    }
    final SopsAgeMaterial material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-sops-age",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SECRET_NAME)
                        .namespace("flux-system")
                        // Allow the mittwald replicator to fan the LIVE (decrypted) age key out to
                        // rke2lab-system, where the in-cluster render Task reads it as SOPS_AGE_KEY
                        // to smudge .secrets + the cellar asset (secret-full render). The
                        // replicator
                        // copies the in-cluster Secret, not the branch blob — so this is
                        // independent
                        // of how sops-age itself reaches flux-system (the Stage-A bootstrap path).
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|flux-system|" + SECRET_NAME,
                                Map.of(
                                    "replicator.v1.mittwald.de/replication-allowed",
                                    "true",
                                    "replicator.v1.mittwald.de/replication-allowed-namespaces",
                                    ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name())))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                AGE_KEY,
                Base64.getEncoder()
                    .encodeToString(material.ageKey().getBytes(StandardCharsets.UTF_8)))));

    // The replicate-from stub in rke2lab-system: mittwald fills its age.agekey from the flux-system
    // source above. The render-publish step reads it as SOPS_AGE_KEY (a Pod can only secretKeyRef a
    // Secret in its own namespace, and the render runs here). An empty stub — NO data: mittwald
    // owns the content, and a rendered empty data would be force-reset by Flux SSA every reconcile.
    final ApiObject replicated =
        new ApiObject(
            scope,
            "secret-sops-age-replicated",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SECRET_NAME)
                        .namespace(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name())
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|"
                                    + ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name()
                                    + "|"
                                    + SECRET_NAME,
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    "flux-system/" + SECRET_NAME)))
                        .build())
                .build());
    replicated.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }
}
