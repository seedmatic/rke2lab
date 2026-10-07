package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.SigningKeyMaterial;
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
 * The {@code manifests-render-signing} Secret — the commit-signing SSH private key the in-cluster
 * Tekton {@code render-publish} step mounts as {@code RKE2LAB_SIGNING_KEY} to sign the rendered
 * {@code manifests/<cluster>} commit. The CI twin of {@link
 * io.seedmatic.rke2lab.manifests.units.gitops.SopsAgeSecretManifestsUnit}: the operator's grow
 * reads the {@code github-signing} key from its ndh key-store (pre-synthesis, {@link
 * SigningKeyMaterial}) and emits this Secret so the future in-cluster render can sign, exactly as
 * it emits {@code sops-age} for Flux.
 *
 * <p>{@code NODE_BOOTSTRAP} lane (a real-data secret, never on the reconciled branch the render
 * pushes) in {@code tekton-pipelines} (where PaC runs the render PipelineRun). Absence — an
 * in-cluster render (the key-store is sops-encrypted at rest and the Secret is already applied), or
 * a bare survey — is an empty {@code Optional<SigningKeyMaterial>} and the unit renders nothing.
 */
public final class RenderSigningSecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/render-signing";

  /** The Secret + the data key the {@code render-publish} step reads the SSH private from. */
  public static final String SECRET_NAME = "manifests-render-signing";

  public static final String SSH_PRIVATE_KEY = "ssh-private";

  // rke2lab-system — where the render PipelineRun executes (the render-publish step mounts this
  // key).
  // Moved off tekton-pipelines with the pipeline; rides the NODE_BOOTSTRAP lane, applied here.
  private static final String NAMESPACE = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "render-signing", true);

  public RenderSigningSecretManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<SigningKeyMaterial> maybeMaterial =
        ManifestSynthesisContext.current().signingKeyMaterial();
    if (maybeMaterial.isEmpty()) {
      return;
    }
    final SigningKeyMaterial material = maybeMaterial.orElseThrow();

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-manifests-render-signing",
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

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                SSH_PRIVATE_KEY,
                Base64.getEncoder()
                    .encodeToString(material.sshPrivate().getBytes(StandardCharsets.UTF_8)))));
  }
}
