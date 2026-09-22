package io.seedmatic.rke2lab.manifests.ingress;

import java.util.Objects;

/**
 * ONE funnel of ONE cluster — the pair that is a funnel's actual identity.
 *
 * <p>{@link FunnelLeaf} alone was the identity while there was one cluster, and that is exactly
 * what broke. A funnel's identity is the triple <em>(FQDN, tailnet device, cert)</em>, and the
 * budget the whole persistence mechanism protects is per EXACT FQDN — 5 certs / 168h. So two
 * clusters sharing a leaf name is not untidy, it defeats the mechanism for both at once: both
 * proxies overwrite one state file, both come back as new devices, both re-issue, both walk into
 * the {@code 429}.
 *
 * <p>And worse, deterministically: the second cluster cannot even claim the bare name. The tailnet
 * suffixes it ({@code flux-webhook-1}), and a drifted duplicate never matches the bare {@code
 * --keep-host} name — so the FIRST cluster's purge Job would delete the second's funnel device on
 * every run, forever.
 *
 * <p>The cluster comes FIRST in the hostname, like every other composite name in this project
 * ({@code bioskop-mgmt-master}, {@code <cluster>-server-manifests}): a fleet's funnels then group
 * by cluster in {@code tailscale status} instead of interleaving.
 *
 * <p>What does NOT gain the cluster is the persist SUBDIRECTORY ({@link #persistSubdir}): the
 * persist dataset is already per-cluster ({@code tank/rke2lab/<role>/persist/funnel-cert}), so
 * qualifying the path inside it would say the same thing twice.
 *
 * <p>See {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} §
 * funnel-per-cluster.
 */
public record Funnel(String cluster, FunnelLeaf leaf) {

  public Funnel {
    if (cluster == null || cluster.isBlank()) {
      throw new IllegalArgumentException(
          "a funnel's cluster must be set — the leaf alone is not an identity");
    }
    Objects.requireNonNull(leaf, "leaf");
  }

  /** This cluster's funnel for {@code leaf}. */
  public static Funnel of(final String cluster, final FunnelLeaf leaf) {
    return new Funnel(cluster, leaf);
  }

  /**
   * The MagicDNS label — the public door {@code https://<hostname>.<tailnet>}, the name the cert is
   * issued for, and the name the tailnet purge spares by {@code --keep-host}.
   */
  public String hostname() {
    return cluster + "-" + leaf.leaf();
  }

  /**
   * The stable proxy-state Secret the per-funnel ProxyClass pins {@code TS_KUBE_SECRET} to. Named
   * after the funnel, so it carries the cluster for the same reason the hostname does — one naming
   * rule, not two.
   */
  public String stateSecret() {
    return "ts-" + hostname() + "-state";
  }

  /**
   * The per-funnel ProxyClass name the funnel Ingress opts into via {@code
   * tailscale.com/proxy-class}.
   */
  public String proxyClass() {
    return hostname();
  }

  /**
   * The subdirectory this funnel's state lives in on the persist volume — the BARE leaf. The volume
   * is per-cluster already, and one PV serves every funnel of the cluster (the dependency graph
   * serialises restore and backup, so they never contend for the single RWO claim).
   */
  public String persistSubdir() {
    return leaf.leaf();
  }
}
