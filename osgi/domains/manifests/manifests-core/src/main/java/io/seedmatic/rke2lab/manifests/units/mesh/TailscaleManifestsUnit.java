// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
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

/**
 * Tailscale operator connector + oauth secret. NOTE: renders its resources as {@code Map.of} blobs
 * across private {@code createXxx} helpers — a de-soup candidate (see
 * docs/architecture/manifests/manifests-unit-lifecycle.adoc § Known debt).
 */
public final class TailscaleManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/tailscale";

  private static final String TAILSCALE_NAMESPACE = MeshRefs.MESH_SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "tailscale");

  public TailscaleManifestsUnit() {
    // Two STRUCTURAL gates Flux health-gates to COMPLETION before this operator provisions any
    // proxy:
    //   - funnel-cert-restore — the proxy then adopts the pre-seeded funnel state Secret (cert
    //     reuse, no re-issuance) instead of racing it (see FunnelCertRestoreManifestsUnit);
    //   - tailnet-purge — the tailnet is cleared of a prior cluster's stale devices FIRST, so the
    //     proxies never drift to a -1 MagicDNS suffix behind a lingering device (see
    //     TailnetPurgeManifestsUnit). One gate covers funnel + controlplane + the operator's
    // device.
    // docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc § the ordering is STRUCTURAL.
    super(
        MANIFEST_UNIT_ID,
        List.of(
            MeshSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            FunnelCertRestoreManifestsUnit.MANIFEST_UNIT_ID,
            TailnetPurgeManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    // mesh-system is owned by MeshSystemNamespaceManifestsUnit (declared as this
    // unit's dependency). Do NOT re-render the Namespace here: the layered
    // kustomize build aggregates every unit's package dir and rejects a duplicate
    // Namespace/mesh-system resource ("may not add resource with an already
    // registered id"). The HelmChart's own createNamespace=true is a runtime no-op
    // once the namespace exists.
    createSecret(scope);
    ApiObject helmChart = createHelmChart(scope);
    createConnector(scope, helmChart, context.nodeEnvContext().bootstrapIdentity().clusterName());
  }

  private ApiObject createHelmChart(final Construct scope) {
    final String version =
        ManifestSynthesisContext.current().componentVersions().of(Component.TAILSCALE);
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-tailscale-operator",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("tailscale-operator")
                        .namespace(TAILSCALE_NAMESPACE)
                        // The operator registers the tailscale.com CRDs at runtime, so it must land
                        // in the `operators` layer (like ClusterApiOperator/EnvoyGateway). The
                        // workloads layer dependsOn operators with wait:true, so the Connector CR
                        // (kept in workloads) only dry-runs once this HelmChart has registered its
                        // CRD — otherwise the whole workloads apply fails "no matches for kind
                        // Connector". mesh-system is created earlier in the foundation layer
                        // (MeshSystemNamespaceManifestsUnit), so it exists before this HelmChart.
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChart|${tailscale-namespace}|tailscale-operator",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart",
                "tailscale-operator",
                "createNamespace",
                true,
                "repo",
                "https://pkgs.tailscale.com/helmcharts",
                "targetNamespace",
                TAILSCALE_NAMESPACE,
                "valuesContent",
                """
                operatorConfig:
                  debug: true
                # PRODUCTION Let's Encrypt: funnel certs must be publicly trusted (staging certs make
                # the GitHub webhook TLS handshake fail). Flip to true ONLY to iterate on funnel-cert
                # persistence/reuse — staging has effectively unlimited rate limits, so a re-issuance
                # during iteration does not burn the production 5-cert/FQDN/168h budget — then flip
                # back before a real grow. See docs .../pac-in-cluster-render-spec.adoc § funnel-durability.
                useLetsEncryptStagingEnvironment: false
                """,
                "version",
                version)));

    return helmChart;
  }

  private void createConnector(
      final Construct scope, final ApiObject helmChart, final String clusterName) {
    ApiObject connector =
        new ApiObject(
            scope,
            "connector-controlplane",
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("Connector")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("controlplane")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    "config.kubernetes.io/depends-on",
                                    "helm.cattle.io/namespaces/"
                                        + TAILSCALE_NAMESPACE
                                        + "/HelmChart/tailscale-operator")))
                        .build())
                .build());
    connector.addDependency(helmChart);

    connector.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "hostname",
                clusterName + "-controlplane",
                "subnetRouter",
                Map.of("advertiseRoutes", List.of("10.80.7.10/32", "10.80.0.64/26")))));
  }

  private void createSecret(final Construct scope) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-operator-oauth",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("operator-oauth")
                        .namespace(TAILSCALE_NAMESPACE)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        // OPERATORS layer, WITH the HelmChart that consumes it — NOT the default
                        // workloads. The operator pod mounts this secret to start, so it must exist
                        // by the time the operator reconciles; leaving it in workloads put it in
                        // the
                        // SAME cell as the Connector CR, whose dry-run fails ("no matches for kind
                        // Connector") until the operator has registered the CRD — a failure that
                        // aborts the whole workloads/mesh/tailscale apply, so the secret was never
                        // created and the operator never started (a circular deadlock the grow lost
                        // non-deterministically). In the operators cell it applies independently of
                        // the Connector; the replicator (a separate platform cell) fills it.
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    "rke2lab-replicator-source/operator-oauth",
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());

    secret.addJsonPatch(
        JsonPatch.add("/type", "Opaque"),
        JsonPatch.add("/stringData", Map.of("client_id", "", "client_secret", "")));
  }
}
