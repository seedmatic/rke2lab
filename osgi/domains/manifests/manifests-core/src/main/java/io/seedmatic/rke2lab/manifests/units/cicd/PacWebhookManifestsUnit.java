package io.seedmatic.rke2lab.manifests.units.cicd;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
import io.seedmatic.rke2lab.manifests.ingress.PacWebhookFunnel;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The one public door Pipelines-as-Code needs: a Tailscale funnel {@code Ingress} exposing the PaC
 * controller's webhook endpoint so GitHub's App webhook can reach it. The upstream twin of the Flux
 * receiver funnel ({@code FluxReceiverManifestsUnit}) — same posture, different backend: here the
 * backend is the {@code pipelines-as-code-controller} Service (port 8080, the webhook receiver) in
 * {@code tekton-pipelines}.
 *
 * <p>Funnel = public internet (vs a bare tailnet-private expose), provisioned by the Tailscale k8s
 * operator with a Let's Encrypt cert — NOT the self-hosted headscale mesh (which has no funnel).
 * Public but gated: PaC validates the App webhook's signature against the {@code webhook.secret} in
 * {@code pipelines-as-code-secret}, and a webhook can only start a PipelineRun the {@code
 * Repository} CR admits — the standard PaC+Funnel posture, mirroring Flux+Funnel.
 */
public final class PacWebhookManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CICD + "/pac-webhook";

  private static final String NAMESPACE = "tekton-pipelines";

  /** The PaC controller Service that terminates webhook POSTs (PaC ships it); port 8080. */
  private static final String CONTROLLER_SERVICE = "pipelines-as-code-controller";

  private static final int CONTROLLER_PORT = 8080;

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cicd", "pac-webhook");

  public PacWebhookManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ApiObject ingress =
        new ApiObject(
            scope,
            "ingress-pac-webhook",
            ApiObjectProps.builder()
                .apiVersion("networking.k8s.io/v1")
                .kind("Ingress")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PacWebhookFunnel.LEAF)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "networking.k8s.io|Ingress|" + NAMESPACE + "|pac-webhook",
                                // Funnel = public internet; the Tailscale operator provisions the
                                // funnel + Let's Encrypt cert. proxy-class opts this proxy into the
                                // stable TS_KUBE_SECRET so its identity/cert persists across grows
                                // (FunnelStatePersistenceManifestsUnit) — no LE re-issuance.
                                Map.of(
                                    "tailscale.com/funnel",
                                    "true",
                                    "tailscale.com/proxy-class",
                                    FunnelLeaf.PIPELINES_WEBHOOK.proxyClass())))
                        .build())
                .build());
    ingress.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ingressClassName",
                "tailscale",
                // tls.hosts[0] is the MagicDNS leaf → https://pac-webhook.<tailnet>.ts.net.
                "tls",
                new Object[] {Map.of("hosts", new Object[] {PacWebhookFunnel.LEAF})},
                "defaultBackend",
                Map.of(
                    "service",
                    Map.of(
                        "name", CONTROLLER_SERVICE, "port", Map.of("number", CONTROLLER_PORT))))));
  }
}
