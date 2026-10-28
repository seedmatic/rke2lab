package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.WireEnum;
import java.util.Arrays;
import java.util.Optional;

/**
 * The single source of truth for a bootstrap-layer component's IDENTITY — one enum constant per
 * component, carrying its wire {@link #slug()}, its baseline {@link #defaultVersion()} pin, and its
 * upstream {@link #source()} provenance. The manifests dual-realm host face: reachable typed in
 * BOTH realms (the manifest units read a resolved version by {@code Component}; the host {@code
 * versions} bumper reads {@link #values()} + each source to diff pins against GitHub) — the
 * dual-realm carrier is exactly what lets ONE typed enum cross the host↔OSGi line.
 *
 * <p>A {@link WireEnum}, so the codec maps it ↔ its {@code slug()} generically — the bump facet
 * carries a typed {@code Optional<Component>} filter that crosses the membrane by slug, never a
 * loose String. The slug is the stable external id (the former {@code ComponentVersions} field
 * name, and the Pulumi {@code rke2lab:components.<slug>.version} key).
 *
 * <p>Passive by design — it holds identity, provenance and the DEFAULT pin, and does NOTHING
 * dynamic (no HTTP, no version discovery): discovery is computed OSGi-side by the bumper (a {@code
 * VersionReport} keyed by {@code Component}), never here. The {@code defaultVersion()} literal is
 * the one the bumper rewrites in place; the resolved/effective version lives in {@link
 * ComponentVersions}.
 */
public enum Component implements WireEnum {
  TEKTON_OPERATOR(
      "tektonOperator",
      "v0.81.2",
      "tektoncd/operator",
      "release.yaml",
      "upstream/cicd/tekton-operator"),
  KUBE_VIP("kubeVip", "v1.3.1", "kube-vip/kube-vip"),
  OPENEBS_ZFS_CHART(
      "openebsZfsChart",
      "2.11.2",
      new ComponentSource.Upstream.HelmIndex(
          "https://openebs.github.io/zfs-localpv", "zfs-localpv")),
  KUBERNETES_REPLICATOR("kubernetesReplicator", "v2.12.4", "mittwald/kubernetes-replicator"),
  FLUX_OPERATOR("fluxOperator", "v0.58.1", "controlplaneio-fluxcd/flux-operator"),
  ENVOY_GATEWAY("envoyGateway", "v1.9.0", "envoyproxy/gateway"),
  // tailscale/tailscale — the operator CHART version, which can trail the release tags (v1.104.4
  // released, chart 1.104.2 the latest on 2026-10-28), so its source is the chart's own index.
  TAILSCALE(
      "tailscale",
      "1.102.3",
      new ComponentSource.Upstream.HelmIndex(
          "https://pkgs.tailscale.com/helmcharts", "tailscale-operator")),
  CLUSTER_API_OPERATOR(
      "clusterApiOperator",
      "v0.30.0",
      "kubernetes-sigs/cluster-api-operator",
      "operator-components.yaml",
      "upstream/clusterapi/operator"),
  CAPI_CORE("capiCore", "v1.14.0", "kubernetes-sigs/cluster-api"),
  CAPI_INCUS_PROVIDER("capiIncusProvider", "v0.9.0", "lxc/cluster-api-provider-incus"),
  CAPI_RKE2_PROVIDER("capiRke2Provider", "v0.27.1", "rancher/cluster-api-provider-rke2"),
  CERT_MANAGER("certManager", "v1.21.1", "cert-manager/cert-manager"),
  // isometry/github-token-manager — the OCI CHART version, versioned apart from the app's `v*`
  // release tags, so its source is the chart's own registry tags.
  GITHUB_TOKEN_MANAGER(
      "githubTokenManager",
      "1.6.1",
      new ComponentSource.Upstream.Oci("ghcr.io", "isometry/charts/github-token-manager")),
  // loft-sh/vcluster — the CHART version on charts.loft.sh, which drops the tag's `v`.
  VCLUSTER_CHART(
      "vclusterChart",
      "0.37.4",
      new ComponentSource.Upstream.HelmIndex("https://charts.loft.sh", "vcluster"));

  private final String slug;
  private final String defaultVersion;
  private final Optional<ComponentSource> source;

  /** A component pinned by a GitHub release, vendoring no upstream manifest. */
  Component(final String slug, final String defaultVersion, final String githubRepo) {
    this(slug, defaultVersion, new ComponentSource.Upstream.GitHub(githubRepo));
  }

  /** A component whose newer versions come from {@code upstream}, vendoring no manifest. */
  Component(
      final String slug, final String defaultVersion, final ComponentSource.Upstream upstream) {
    this(
        slug,
        defaultVersion,
        Optional.of(new ComponentSource(upstream, Optional.empty(), Optional.empty())));
  }

  /** A component pinned by a GitHub release whose asset is vendored as {@code release-<v>.yaml}. */
  Component(
      final String slug,
      final String defaultVersion,
      final String githubRepo,
      final String releaseAssetName,
      final String vendoredResourceDir) {
    this(
        slug,
        defaultVersion,
        Optional.of(
            new ComponentSource(
                new ComponentSource.Upstream.GitHub(githubRepo),
                Optional.of(releaseAssetName),
                Optional.of(vendoredResourceDir))));
  }

  Component(
      final String slug, final String defaultVersion, final Optional<ComponentSource> source) {
    this.slug = slug;
    this.defaultVersion = defaultVersion;
    this.source = source;
  }

  @Override
  public String slug() {
    return slug;
  }

  /** The baseline pin — the literal the bumper rewrites; {@link ComponentVersions} resolves it. */
  public String defaultVersion() {
    return defaultVersion;
  }

  /** The GitHub provenance, empty for a non-bumpable component (chart / container tag). */
  public Optional<ComponentSource> source() {
    return source;
  }

  /** The component with the given wire slug, empty when none matches. */
  public static Optional<Component> fromSlug(final String slug) {
    final String normalized = slug == null ? "" : slug.trim();
    return Arrays.stream(values()).filter(c -> c.slug.equals(normalized)).findFirst();
  }
}
