package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

public final class TailscaleSystemNamespaceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.TAILSCALE + "/system-namespace";

  // The tailscale-system namespace must land in the foundation layer, NOT the default workloads
  // layer: the tailscale-operator HelmChart lives in the `operators` layer (CRD-before-CR), and
  // operators applies before workloads — so a workloads-layer namespace would not yet exist when
  // the operators layer tries to create the HelmChart *in* tailscale-system ("namespaces
  // tailscale-system not found"). foundation applies before operators, so the namespace is Ready
  // for both.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("tailscale", "system-namespace", false, ManifestLayer.FOUNDATION);

  public TailscaleSystemNamespaceManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    new ApiObject(
        scope,
        "namespace-" + TailscaleRefs.SYSTEM_NAMESPACE.name(),
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(TailscaleRefs.SYSTEM_NAMESPACE.name())
                    .annotations(
                        packageProfile.packageAnnotations(
                            "|Namespace|default|" + TailscaleRefs.SYSTEM_NAMESPACE.name()))
                    .build())
            .build());
  }
}
