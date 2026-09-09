package io.seedmatic.rke2lab.manifests.ingress;

/**
 * The canonical registry of every Tailscale FUNNEL this cluster exposes — the single source of
 * truth for the public-door MagicDNS leaves, so the persistence machinery (per-funnel ProxyClass +
 * stable state Secret + backup/restore) and the tailnet purge iterate ONE list, and no funnel is
 * left un-persisted (the flux-webhook gap that made it re-register every grow).
 *
 * <p>Each funnel gets a STABLE state Secret ({@link #stateSecret}) pinned by a per-funnel {@link
 * #proxyClass} (via {@code TS_KUBE_SECRET}), so its device identity + Let's Encrypt cert survive a
 * cold-start in the persist volume and the proxy RE-ATTACHES as the same device (same FQDN, cert
 * reused, zero ACME) instead of registering a fresh one. The purge spares these leaves by name
 * ({@code --keep-host}) so it never deletes the very device the restore brings back.
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

  /** The MagicDNS leaf — the funnel hostname, {@code https://<leaf>.<tailnet>}. */
  public String leaf() {
    return leaf;
  }

  /**
   * The stable proxy-state Secret name the per-funnel ProxyClass pins {@code TS_KUBE_SECRET} to.
   */
  public String stateSecret() {
    return "ts-" + leaf + "-state";
  }

  /**
   * The per-funnel ProxyClass name the funnel Ingress opts into via {@code
   * tailscale.com/proxy-class}.
   */
  public String proxyClass() {
    return leaf;
  }

  /**
   * Whether this funnel adopts the LEGACY flat persist path {@code /persist/state.yaml} — the
   * single-funnel layout from before the per-leaf subdirs. Only {@code pipelines-webhook} (the sole
   * funnel that was persisted then) owns it, so its restore migrates that file into {@code
   * /persist/<leaf>/state.yaml} once, letting it re-attach + reuse its cert on the FIRST per-leaf
   * grow instead of re-issuing.
   */
  public boolean adoptsLegacyFlatState() {
    return this == PIPELINES_WEBHOOK;
  }
}
