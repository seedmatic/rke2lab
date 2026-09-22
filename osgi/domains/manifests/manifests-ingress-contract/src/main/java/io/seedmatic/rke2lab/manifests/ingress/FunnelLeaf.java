package io.seedmatic.rke2lab.manifests.ingress;

/**
 * The canonical registry of every Tailscale FUNNEL KIND a cluster exposes — the single source of
 * truth for the public-door leaves, so the persistence machinery (per-funnel ProxyClass + stable
 * state Secret + backup/restore) and the tailnet purge iterate ONE list, and no funnel is left
 * un-persisted (the flux-webhook gap that made it re-register every grow).
 *
 * <p>A leaf is NOT an identity. Pair it with a cluster — {@link Funnel} — to get one: the FQDN, the
 * state Secret and the ProxyClass all derive from the pair, because the Let's Encrypt budget this
 * machinery exists to protect is per exact FQDN and two clusters on one leaf name burn it together.
 *
 * <p>Lives in the dual-realm {@code manifests.ingress} face (like {@link PacWebhookFunnel}): a pure
 * String enum, JDK-only, consumed both OSGi-side (the tailscale persistence units, the funnel
 * Ingress units) and — via the leaf — host-side.
 */
public enum FunnelLeaf {
  /** The Pipelines-as-Code / Tekton webhook funnel (cicd). */
  PIPELINES_WEBHOOK("pipelines-webhook"),
  /** The Flux notification-controller webhook-receiver funnel (gitops). */
  FLUX_WEBHOOK("flux-webhook");

  private final String leaf;

  FunnelLeaf(final String leaf) {
    this.leaf = leaf;
  }

  /** The kind of funnel — the leaf a {@link Funnel}'s hostname is built from. */
  public String leaf() {
    return leaf;
  }
}
