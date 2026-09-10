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
      "v0.81.1",
      "tektoncd/operator",
      "release.yaml",
      "upstream/cicd/tekton-operator"),
  KUBE_VIP("kubeVip", "v0.9.2", "kube-vip/kube-vip"),
  OPENEBS_ZFS_CHART("openebsZfsChart", "2.8.0"),
  KUBERNETES_REPLICATOR("kubernetesReplicator", "v2.12.4", "mittwald/kubernetes-replicator"),
  FLUX_OPERATOR("fluxOperator", "v0.58.1", "controlplaneio-fluxcd/flux-operator"),
  ENVOY_GATEWAY("envoyGateway", "v1.9.0", "envoyproxy/gateway"),
  TAILSCALE("tailscale", "1.102.3", "tailscale/tailscale"),
  CLUSTER_API_OPERATOR(
      "clusterApiOperator",
      "v0.29.0",
      "kubernetes-sigs/cluster-api-operator",
      "operator-components.yaml",
      "upstream/clusterapi/operator"),
  CAPI_CORE("capiCore", "v1.14.0", "kubernetes-sigs/cluster-api"),
  CAPI_INCUS_PROVIDER("capiIncusProvider", "v0.9.0", "lxc/cluster-api-provider-incus"),
  CAPI_RKE2_PROVIDER("capiRke2Provider", "v0.25.2", "rancher/cluster-api-provider-rke2"),
  CERT_MANAGER("certManager", "v1.21.1", "cert-manager/cert-manager"),
  // isometry/github-token-manager — the OCI CHART version (1.6.1), which the publish aligns to the
  // app tag; NO GitHub bump source (the chart is versioned separately from the app release tags, so
  // the bumper cannot diff it against `v*` tags — it stays a manual chart pin, like
  // OPENEBS_ZFS_CHART).
  GITHUB_TOKEN_MANAGER("githubTokenManager", "1.6.1");

  private final String slug;
  private final String defaultVersion;
  private final Optional<ComponentSource> source;

  /** A component with NO GitHub release source (a chart or container tag — the bumper skips it). */
  Component(final String slug, final String defaultVersion) {
    this(slug, defaultVersion, Optional.empty());
  }

  /** A component pinned by a GitHub release, vendoring no upstream manifest. */
  Component(final String slug, final String defaultVersion, final String githubRepo) {
    this(
        slug,
        defaultVersion,
        Optional.of(new ComponentSource(githubRepo, Optional.empty(), Optional.empty())));
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
                githubRepo, Optional.of(releaseAssetName), Optional.of(vendoredResourceDir))));
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
