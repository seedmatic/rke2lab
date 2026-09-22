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
 * <p>What does NOT gain the cluster is anything CLUSTER-LOCAL ({@link #leafName}): the persist
 * subdirectory, and the names of the objects that serve the funnel. The persist dataset is already
 * per-cluster ({@code tank/rke2lab/<role>/persist/funnel-cert}) and a Job lives in one cluster's
 * namespace, so qualifying either would say the same thing twice.
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
   * The bare leaf as a STRING — this funnel's short, cluster-local identifier. Two uses, one value:
   * the subdirectory its state lives in on the persist volume, and the name of the cluster-local
   * objects that serve it (the Jobs, the cdk8s construct ids).
   *
   * <p>Neither is cluster-qualified, and for the same reason: the persist volume is per-cluster
   * ALREADY, and a Job lives in one cluster's namespace — so adding the cluster would say the same
   * thing twice. What IS cluster-qualified is everything the TAILNET sees ({@link #hostname},
   * {@link #stateSecret}, {@link #proxyClass}), because that namespace is fleet-wide.
   *
   * <p>Named to contrast with {@link #leaf()}, which returns the ENUM. They differed only by return
   * type once, and string concatenation swallowed the difference: {@code "job-" + funnel.leaf()}
   * compiled and rendered {@code job-PIPELINES_WEBHOOK}, which Kubernetes rejects as a name.
   */
  public String leafName() {
    return leaf.leaf();
  }
}
