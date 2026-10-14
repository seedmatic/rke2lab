// @codebase
package io.seedmatic.rke2lab.manifests.units.cluster;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

public final class ClusterRuntimeNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER + "/runtime-system-namespace";

  public static final String OUTPUT_DIR = "runtime-system-namespace";

  // Foundation layer, not the default workloads: this shared namespace is depended on by
  // operators-layer resources (the flox-controller SA/DaemonSet live here), which apply before
  // workloads. Creating it in foundation breaks the deadlock (operators needs the namespace ↔
  // workloads holds it) — namespaces the later layers rely on belong early.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.CLUSTER, OUTPUT_DIR, false, ManifestLayer.FOUNDATION);

  public ClusterRuntimeNamespaceManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    new ApiObject(
        scope,
        "namespace-" + ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name(),
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name())
                    .annotations(packageProfile.packageAnnotations())
                    .labels(Map.of("rke2lab.nxmatic.io/shared-namespace", "true"))
                    .build())
            .build());
  }
}
