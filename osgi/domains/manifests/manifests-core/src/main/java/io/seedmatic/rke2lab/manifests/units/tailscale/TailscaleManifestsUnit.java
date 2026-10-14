// @codebase
package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.ingress.FunnelCertIssuance;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Tailscale operator + its oauth secret. NOTE: renders its resources as {@code Map.of} blobs across
 * private {@code createXxx} helpers — a de-soup candidate (see
 * docs/architecture/manifests/manifests-unit-lifecycle.adoc § Known debt).
 *
 * <p>There is deliberately NO {@code Connector} CR here, and re-adding one is a regression. It was
 * this cluster's subnet router, advertising the kube-vip VIP {@code /32} + the cilium LB {@code
 * /26} of itself and of every cluster it manages. Two things were wrong with that owner. The
 * addresses it advertised live on a {@code vmnet} bridge owned by the BARE-METAL, while the
 * advertiser was a pod INSIDE the cluster — so the route died with the cluster, which is exactly
 * when the comment's own justification ("the route is wanted while debugging a cluster that is
 * half-born") needs it. And a managed cluster on ANOTHER bare-metal would have been advertised by a
 * Connector that cannot forward to it: the "reaches a sibling VIP locally over the host" argument
 * only holds for bridges on the SAME host, so the next cluster ({@code nikopol-mgmt}) would have
 * been a black hole behind an elected primary subnet router.
 *
 * <p>So the role moved to the host that owns the bridges: ndh's {@code cluster-vmnet.nix}
 * advertises each {@code vmnet} segment it declares. One advertiser per prefix, no election, and
 * the route outlives the cluster because the host does. The Connector had no other role — no exit
 * node, no app connector — so the CR went with it, and with it two tailnet devices the purge Job
 * had to treat as un-persisted orphans on every cold start. The funnel proxies are separate
 * devices, unaffected.
 */
public final class TailscaleManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.TAILSCALE + "/tailscale";

  private static final String TAILSCALE_NAMESPACE = TailscaleRefs.SYSTEM_NAMESPACE.name();

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("tailscale", "tailscale");

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
            TailscaleSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            FunnelCertRestoreManifestsUnit.MANIFEST_UNIT_ID,
            TailnetPurgeManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    // tailscale-system is owned by TailscaleSystemNamespaceManifestsUnit (declared as this
    // unit's dependency). Do NOT re-render the Namespace here: the layered
    // kustomize build aggregates every unit's package dir and rejects a duplicate
    // Namespace/tailscale-system resource ("may not add resource with an already
    // registered id"). The HelmChart's own createNamespace=true is a runtime no-op
    // once the namespace exists.
    createSecret(scope);
    createHelmChart(scope);
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
                        // Connector". tailscale-system is created earlier in the foundation layer
                        // (TailscaleSystemNamespaceManifestsUnit), so it exists before this
                        // HelmChart.
                        .annotations(
                            packageProfile.packageAnnotations(
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
                # Projected from FunnelCertIssuance.current() — the ONE place the posture is declared.
                # A staging cert is untrusted, so GitHub's webhook TLS handshake fails against it, and
                # the in-cluster RENDER is triggered by that webhook: verification must be off for
                # exactly as long as this is on. Both halves now come from that one enum, so they
                # cannot be half-flipped, and returning to production turns verification back on by
                # construction.
                #
                # The cert already on disk is covered too: the backup records this posture beside the
                # state and the restore drops a cert that does not match it, keeping the node key. So
                # flipping this line IS the whole gesture.
                # See docs .../pac-in-cluster-render-spec.adoc § funnel-durability.
                useLetsEncryptStagingEnvironment: %s
                """
                    .formatted(FunnelCertIssuance.current().staging()),
                "version",
                version)));

    return helmChart;
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
                        // aborts the whole workloads/tailscale/tailscale apply, so the secret was
                        // never
                        // created and the operator never started (a circular deadlock the grow lost
                        // non-deterministically). In the operators cell it applies independently of
                        // the Connector; the replicator (a separate platform cell) fills it.
                        .annotations(
                            packageProfile.packageAnnotations(
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    ClusterRefs.SECRETS_NAMESPACE + "/operator-oauth",
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());

    // Empty stub — NO stringData: mittwald's replicate-from fills client_id/client_secret from the
    // source. A rendered empty stringData makes Flux (SSA, force-apply) reset those keys to "" on
    // every reconcile, clobbering the replicated values — a race that leaves the OAuth empty (401).
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }
}
