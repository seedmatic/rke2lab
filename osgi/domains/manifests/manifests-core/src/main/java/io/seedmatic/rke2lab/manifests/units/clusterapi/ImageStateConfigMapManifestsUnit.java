package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Manifest unit that creates the image-state ConfigMap for Stage A → Stage B handoff.
 *
 * <p>The ConfigMap provides cluster image identity to seed-peers (Stage B) for synthesizing
 * LXCMachineTemplate CRs. Contains:
 *
 * <ul>
 *   <li>{@code imageAlias} - Image alias (e.g., "control-node")
 *   <li>{@code imageFingerprint} - Immutable image fingerprint from imageProvider
 *   <li>{@code imageBuildChecksum} - SHA-256 of image build inputs
 *   <li>{@code incusProject} - Incus project name (e.g., "rke2lab")
 *   <li>{@code incusRemoteAddress} - Remote URI (e.g., "https://bioskop-nixos:8443")
 * </ul>
 *
 * <p>The ConfigMap is named {@code <cluster-name>-image-state} in namespace {@code capn-system}.
 *
 * <p>Registered in {@link io.seedmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar}. It
 * renders the ConfigMap only when the {@link ImageState} synth slice is present; that slice is
 * populated by the incus scion's {@code IMAGE_STATE} amendment — the built node-base image's
 * identity (alias, content fingerprint, build checksum, incus project/remote, baked RKE2 version),
 * computed from the freshly-built artifacts rather than a Pulumi provider round trip, which
 * sidesteps the old Stage A/B chicken-and-egg. When no image state is bound (a bare survey / a
 * render with no image built), the unit no-ops, same as for an unknown cluster identity.
 */
public final class ImageStateConfigMapManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/image-state";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "image-state");

  public ImageStateConfigMapManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String effectiveClusterName =
        ManifestSynthesisContext.current().bootstrapIdentity().clusterName();

    // Skip synthesis when running in ephemeral/test mode without real bootstrap identity
    if (BootstrapIdentity.UNKNOWN.equals(effectiveClusterName)) {
      return;
    }

    final Optional<ImageState> maybeState = ManifestSynthesisContext.current().imageState();

    // Skip when seed-master supplied no real image identity (ephemeral/test synth): an
    // all-placeholder ConfigMap would mislead Stage B into pinning a non-existent image.
    if (maybeState.isEmpty()) {
      return;
    }
    final ImageState state = maybeState.orElseThrow();

    final Map<String, String> data =
        Map.of(
            "imageAlias", state.imageAlias(),
            "imageFingerprint", state.imageFingerprint(),
            "imageBuildChecksum", state.imageBuildChecksum(),
            "incusProject", state.incusProject(),
            "incusRemoteAddress", state.incusRemoteAddress(),
            "rke2Version", state.rke2Version());

    createImageStateConfigMap(scope, effectiveClusterName, data);
  }

  private void createImageStateConfigMap(
      Construct scope, String clusterName, Map<String, String> imageState) {
    ApiObject configMap =
        new ApiObject(
            scope,
            "configmap-image-state",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterName + "-image-state")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|capn-system|" + clusterName + "-image-state"))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", imageState));
  }
}
