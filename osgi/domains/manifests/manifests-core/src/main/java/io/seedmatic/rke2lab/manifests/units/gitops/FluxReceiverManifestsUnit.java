package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
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
 * Wires a Flux {@code Receiver} so a GitHub push triggers an IMMEDIATE reconcile of the rendered
 * branch — killing the polling latency (the {@code GitRepository} otherwise fetches on its 1m
 * interval) and the manual {@code flux reconcile} force. Steady-state only (reconciled from the
 * branch, NOT the node-bootstrap lane): at bootstrap the polling auto-sync already brings every
 * Kustomization Ready, so the webhook is a pure operational accelerant.
 *
 * <p>Three objects, all in {@code flux-system}:
 *
 * <ul>
 *   <li>a {@code Receiver} ({@code type: github}) that, on a signed {@code push}, tells
 *       source-controller to reconcile the {@code rke2lab} {@code GitRepository} at once;
 *   <li>the {@code flux-webhook-token} Secret it HMAC-validates against — a replica stub filled by
 *       mittwald from the replicator source (the same pull pattern as {@code floxhub-token}); the
 *       operator seeds the value at {@code github.webhook.secret} in {@code .secrets} and
 *       configures the GitHub webhook's Secret field with the SAME value;
 *   <li>a Tailscale {@code Ingress} ({@code ingressClassName: tailscale}, {@code
 *       tailscale.com/funnel: "true"}) exposing the notification-controller's {@code
 *       webhook-receiver} Service publicly at {@code https://flux-webhook.<tailnet>.ts.net} with a
 *       Tailscale-provisioned Let's Encrypt cert — the ONE public door GitHub needs. Funnel comes
 *       from the Tailscale k8s operator (on Tailscale SaaS via {@code operator-oauth}), NOT the
 *       self-hosted headscale mesh (which has no funnel). Public but gated by the GitHub HMAC and
 *       only able to trigger a reconcile — the standard Flux+Funnel posture.
 * </ul>
 */
public final class FluxReceiverManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-receiver";

  private static final String NAMESPACE = "flux-system";

  /** The notification-controller Service that terminates webhook POSTs (Flux ships it). */
  private static final String WEBHOOK_RECEIVER_SERVICE = "webhook-receiver";

  /**
   * The HMAC token Secret / replicator source name (matches {@code .secrets
   * kubernetes.secrets.web-hook.name} + the stub). Public: the {@code cicd} PaC secret reads the
   * SAME operator-chosen {@code github.webhook.secret} from this replicator source for its {@code
   * webhook.secret} (single source of truth for the shared webhook HMAC).
   */
  public static final String WEBHOOK_TOKEN_SECRET = "flux-webhook-token";

  /** The Tailscale MagicDNS leaf; the funnel URL is https://<this>.<tailnet>.ts.net. */
  private static final String FUNNEL_HOSTNAME = "flux-webhook";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-receiver");

  public FluxReceiverManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    createTokenReplicaStub(scope);
    createReceiver(scope);
    createFunnelIngress(scope);
  }

  private void createTokenReplicaStub(final Construct scope) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-flux-webhook-token",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(WEBHOOK_TOKEN_SECRET)
                        .namespace(NAMESPACE)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + NAMESPACE + "|" + WEBHOOK_TOKEN_SECRET,
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    ClusterRefs.SECRETS_NAMESPACE + "/" + WEBHOOK_TOKEN_SECRET)))
                        .build())
                .build());
    // Empty stub — mittwald's replicate-from fills the `token` key from the source.
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }

  private void createReceiver(final Construct scope) {
    final ApiObject receiver =
        new ApiObject(
            scope,
            "receiver-github",
            ApiObjectProps.builder()
                .apiVersion("notification.toolkit.fluxcd.io/v1")
                .kind("Receiver")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("github-receiver")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "notification.toolkit.fluxcd.io|Receiver|"
                                    + NAMESPACE
                                    + "|github-receiver"))
                        .build())
                .build());
    receiver.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "type",
                "github",
                "events",
                new Object[] {"ping", "push"},
                "secretRef",
                Map.of("name", WEBHOOK_TOKEN_SECRET),
                "resources",
                new Object[] {
                  Map.of(
                      "apiVersion",
                      "source.toolkit.fluxcd.io/v1",
                      "kind",
                      "GitRepository",
                      "name",
                      FluxRootManifestsUnit.GIT_REPOSITORY_NAME,
                      "namespace",
                      NAMESPACE)
                })));
  }

  private void createFunnelIngress(final Construct scope) {
    final ApiObject ingress =
        new ApiObject(
            scope,
            "ingress-flux-webhook",
            ApiObjectProps.builder()
                .apiVersion("networking.k8s.io/v1")
                .kind("Ingress")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flux-webhook")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "networking.k8s.io|Ingress|" + NAMESPACE + "|flux-webhook",
                                // Funnel = public internet (vs a bare tailnet-private expose). The
                                // Tailscale operator provisions the funnel + Let's Encrypt cert.
                                // proxy-class opts this proxy into the per-funnel ProxyClass that
                                // pins a STABLE state Secret (FunnelStatePersistenceManifestsUnit)
                                // —
                                // so the device identity + cert survive a cold-start (re-attach, no
                                // LE re-issuance), same durable-funnel posture as the PaC webhook.
                                Map.of(
                                    "tailscale.com/funnel",
                                    "true",
                                    "tailscale.com/proxy-class",
                                    FunnelLeaf.FLUX_WEBHOOK.proxyClass())))
                        .build())
                .build());
    ingress.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ingressClassName",
                "tailscale",
                // tls.hosts[0] is the MagicDNS leaf → https://flux-webhook.<tailnet>.ts.net.
                "tls",
                new Object[] {Map.of("hosts", new Object[] {FUNNEL_HOSTNAME})},
                "defaultBackend",
                Map.of(
                    "service",
                    Map.of("name", WEBHOOK_RECEIVER_SERVICE, "port", Map.of("number", 80))))));
  }
}
