package io.seedmatic.rke2lab.manifests.units.platform;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class CertManagerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/cert-manager";

  // Foundation layer: cert-manager's chart registers the cert-manager.io CRDs + runs the issuer
  // that signs operator webhook certs, so it must be up before the operators layer — whose installs
  // bundle a Certificate that dry-runs against those CRDs.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("platform", "cert-manager", false, ManifestLayer.FOUNDATION);

  public CertManagerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String version =
        ManifestSynthesisContext.current().componentVersions().of(Component.CERT_MANAGER);

    // RKE2 helm-controller watches HelmChart CRs in kube-system and installs the chart into
    // targetNamespace. cert-manager is cluster-wide infrastructure deployed to kube-system and
    // provisions webhook-serving certs (e.g. capi-operator-webhook-service-cert) that the CAPI
    // and Tekton operators mount — without it those operator pods hang on FailedMount.
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-cert-manager",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cert-manager")
                        .namespace("kube-system")
                        .annotations(packageProfile.packageAnnotations())
                        .build())
                .build());

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "repo",
                "https://charts.jetstack.io",
                "chart",
                "cert-manager",
                "version",
                version,
                "targetNamespace",
                "kube-system",
                "valuesContent",
                """
                crds:
                  enabled: true
                """)));
  }
}
