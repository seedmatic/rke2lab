package io.seedmatic.rke2lab.manifests.ingress;

/**
 * The Pipelines-as-Code webhook FUNNEL identity — the single source of truth for the Tailscale
 * MagicDNS leaf the PaC controller is funnel-exposed under, shared by the two parties that must
 * agree on it: the manifests {@code PacWebhookManifestsUnit} (which names the Ingress + its {@code
 * tls.hosts} leaf, letting the Tailscale operator provision {@code https://<leaf>.<tailnet>}) and
 * the host ghapp webhook scion (which points the GitHub App's webhook at that same {@code url}).
 *
 * <p>The funnel is PER-CLUSTER ({@link Funnel}), so this record carries the cluster. ⚠️ A GitHub
 * App has ONE webhook URL, and every cluster exposes its own PaC funnel — so the App can point at
 * exactly one of them. Which cluster receives PaC events is therefore a decision, not a derivation;
 * today it is the management cluster's funnel.
 *
 * <p>It lives in the {@code manifests.ingress} DUAL-REALM face precisely because both realms
 * consume it: OSGi-side the manifest units read {@link #LEAF}, host-side the {@code seed-master}
 * grow builds {@link #url()} to sow the webhook scion and the {@code ghapp} CLI pre-fills the
 * registration form. A {@code manifests.contract} home would be bundle-only — a flat-realm
 * reference from the host would break the realm-boundary law. It qualifies for the dual-realm rule:
 * a pure String record, JDK-only, manifests-owned, no service reference.
 *
 * <p>A record over the {@code tailnet} (the host-config suffix, e.g. {@code mammoth-skate.ts.net} —
 * it ALREADY carries {@code .ts.net}, so {@link #url()} does not re-append it): {@link #url()} is
 * the funnel endpoint {@code https://<leaf>.<tailnet>}. Only the host holds the tailnet (Tailscale
 * appends it at runtime; it is never on the in-container synthesis context), so the manifests side
 * uses only {@link #LEAF} and the host builds the full {@code url()}.
 */
public record PacWebhookFunnel(String cluster, String tailnet) {

  /**
   * This cluster's PaC funnel. The leaf is NOT redeclared here — it comes from {@link
   * FunnelLeaf#PIPELINES_WEBHOOK}, which was always the same string in two places.
   */
  public Funnel funnel() {
    return Funnel.of(cluster, FunnelLeaf.PIPELINES_WEBHOOK);
  }

  /** The public funnel endpoint the GitHub App posts its webhook events to. */
  public String url() {
    return "https://" + funnel().hostname() + "." + tailnet;
  }
}
