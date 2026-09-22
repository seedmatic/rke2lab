package io.seedmatic.rke2lab.manifests.ingress;

/**
 * Which Let's Encrypt environment the funnel certs are issued from — the fleet's posture, declared
 * in ONE place because it has TWO consequences that must never disagree.
 *
 * <p>A funnel cert is issued by the Tailscale operator, and the production budget is 5 certs per
 * exact FQDN per 168h. Proving the persistence/reuse chain takes several re-grows, so it runs on
 * {@link #STAGING}, whose limits are effectively unlimited. But a staging cert is UNTRUSTED, so
 * GitHub's webhook TLS handshake fails against it — and that is not a cosmetic loss: the in-cluster
 * RENDER is triggered by that webhook, so verification must be off for as long as staging is on or
 * the whole GitOps loop stalls.
 *
 * <p>Those two settings used to live apart — {@code useLetsEncryptStagingEnvironment} in the
 * tailscale render, {@code insecure_ssl} as a hardcoded {@code 0} in the webhook edge — which made
 * them a pair nothing could keep in step. Worse than drift: the edge re-asserted {@code 0} on every
 * grow, so a grow would silently undo an operator's manual "verification off" mid-window. One owner
 * removes both problems, and it makes the return to production self-correcting: flipping {@link
 * #current} back to {@link #PRODUCTION} turns verification on by construction, rather than relying
 * on someone remembering the other half.
 *
 * <p>Flipping it is the WHOLE gesture, including the cert already on disk. tailscaled renews on
 * dates alone and never compares the issuer, so a persisted staging cert would otherwise be served
 * for the rest of its ~90 days with the posture already back on production. So the backup Job
 * records this value beside the state and the restore Job drops a cert that does not match it —
 * keeping the node key, so the proxy still returns as the same tailnet device. One issuance, no
 * identity churn. See {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} §
 * funnel-durability.
 *
 * <p>Lives in the dual-realm {@code manifests.ingress} face (like {@link FunnelLeaf} and {@link
 * PacWebhookFunnel}) because both realms read it: the manifests units render the operator's toggle,
 * and the host's ghapp webhook scion sends the matching {@code insecure_ssl}.
 */
public enum FunnelCertIssuance {
  /**
   * Let's Encrypt staging — untrusted certs, effectively unlimited issuance. A validation posture.
   */
  STAGING,
  /**
   * Let's Encrypt production — publicly trusted certs, 5 per exact FQDN per 168h. The steady state.
   */
  PRODUCTION;

  /**
   * The fleet's posture. THIS is the one line to flip; everything else follows from it.
   *
   * <p>Currently {@link #STAGING}: the funnel-cert persistence chain — per-cluster dataset, the
   * controller-placed volume, restore-before-operator, backup-after-cert — has never been proven
   * end to end, and each attempt on production would spend a cert per FQDN.
   */
  public static FunnelCertIssuance current() {
    return STAGING;
  }

  /**
   * Whether certs come from the staging directory — read BOTH by the tailscale render (the
   * operator's {@code useLetsEncryptStagingEnvironment}) and by the ghapp webhook reconcile
   * (GitHub's {@code insecure_ssl}, since a staging cert cannot be verified).
   */
  public boolean staging() {
    return this == STAGING;
  }
}
