package io.seedmatic.rke2lab.manifests.units.ingress;

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

public final class IngressSystemNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.INGRESS + "/system-namespace";

  // The shared ingress-system namespace must land in the foundation layer, NOT the default
  // workloads
  // layer: the tailscale-operator HelmChart lives in the `operators` layer (CRD-before-CR), and
  // operators applies before workloads — so a workloads-layer namespace would not yet exist when
  // the operators layer tries to create the HelmChart *in* ingress-system ("namespaces
  // ingress-system not
  // found"). foundation applies before operators, so the namespace is Ready for both.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "system-namespace", false, ManifestLayer.FOUNDATION);

  public IngressSystemNamespaceManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    new ApiObject(
        scope,
        "namespace-" + IngressRefs.SYSTEM_NAMESPACE.name(),
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(IngressRefs.SYSTEM_NAMESPACE.name())
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|" + IngressRefs.SYSTEM_NAMESPACE.name()))
                    .labels(Map.of("rke2lab.nxmatic.io/shared-namespace", "true"))
                    .build())
            .build());
  }
}
