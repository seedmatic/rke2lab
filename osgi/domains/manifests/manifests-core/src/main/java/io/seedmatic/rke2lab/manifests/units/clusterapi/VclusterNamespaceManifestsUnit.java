package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.VclusterDeclaration;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

/**
 * The namespace of each vcluster its host renders, in a unit of its own: both the certs and the
 * vcluster land in it, and the certs' Kustomization is applied first, so the namespace cannot ride
 * the vcluster's ("namespaces vcluster-ccr not found", nikopol-wrkld 2026-10-28).
 */
public final class VclusterNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.VCLUSTER + "/namespace";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.VCLUSTER, "namespace");

  public VclusterNamespaceManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String self = ManifestSynthesisContext.current().bootstrapIdentity().clusterName();
    for (final VclusterDeclaration.Vcluster vcluster : VclusterDeclaration.fleet().vclusters()) {
      if (!vcluster.hostCluster().equals(self)) {
        continue;
      }
      new ApiObject(
          scope,
          "namespace-" + vcluster.namespace(),
          ApiObjectProps.builder()
              .apiVersion("v1")
              .kind("Namespace")
              .metadata(
                  ApiObjectMetadata.builder()
                      .name(vcluster.namespace())
                      .annotations(packageProfile.packageAnnotations())
                      .labels(
                          Map.of(
                              "pod-security.kubernetes.io/enforce", "restricted",
                              "pod-security.kubernetes.io/warn", "restricted"))
                      .build())
              .build());
    }
  }
}
