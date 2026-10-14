// @codebase
package io.seedmatic.rke2lab.manifests.contract;

/**
 * The metadata annotation KEYS rke2lab stamps on rendered manifests — a closed vocabulary typed as
 * an enum (was loose {@code String} constants on a {@code ManifestAnnotations} record) so a key is
 * referenced by a compile-checked constant, never a literal. The keys drive manifest exploding
 * (splitting consolidated YAML into per-resource files by domain/package/layer) + the
 * node-bootstrap lane. Values for {@link #MANIFEST_LAYER} come from {@link ManifestLayer}.
 *
 * <p>Unlike a single-namespace enum, each key carries its full value: most are on {@code
 * io.seedmatic.rke2lab/…}, {@link #LOCAL_CONFIG} is the KRM {@code config.kubernetes.io}
 * convention, and {@link #NODE_FLOX_RUNTIME_LABEL} is on the {@code flox.seedmatic.io} domain
 * (shared with the CRD group; migrated from the upstream {@code flox.dev/enabled}).
 */
public enum ManifestAnnotation {

  /** The domain a rendered resource belongs to (exploder routing). */
  DOMAIN("io.seedmatic.rke2lab/domain"),

  /** The package within a domain (exploder routing). */
  PACKAGE("io.seedmatic.rke2lab/package"),

  /**
   * Marks a manifest the cluster must not apply (KRM convention). {@code
   * rke2lab-manifests-install.sh} skips symlinking these; the exploder writes them as dotfiles.
   */
  LOCAL_CONFIG("config.kubernetes.io/local-config"),

  /**
   * Marks a ConfigMap whose {@code .data} entries are RKE2 server config fragments — the exploder
   * writes them verbatim so {@code rke2lab-config-install.sh} can glob them into {@code
   * config.yaml.d}.
   */
  RKE2_CONFIG("io.seedmatic.rke2lab/rke2-config"),

  /**
   * Marks the per-unit group inventory ConfigMap emitted by {@code AbstractManifestsUnit} (also
   * carries {@link #LOCAL_CONFIG}); identifies it as an inventory marker, not a real resource.
   */
  MANIFEST_GROUP("io.seedmatic.rke2lab/manifest-group"),

  /**
   * Marks a resource on the node-side bootstrap lane — the small set the host seeds into the node's
   * RKE2 {@code server/manifests} at grow time (Flux operator/instance/root, bootstrap Secrets, the
   * cilium {@code HelmChartConfig}) so the CNI + Flux come up before the rendered branch is
   * reachable. The exploder collects these into {@code .bootstrap/rke2lab-bootstrap.yaml} outside
   * the committed tree — delivered node-side, never committed nor applied from the branch.
   */
  NODE_BOOTSTRAP("io.seedmatic.rke2lab/node-bootstrap"),

  /**
   * The reconcile {@link ManifestLayer} a rendered resource belongs to — absent ⇒ {@link
   * ManifestLayer#WORKLOADS}; a {@code CustomResourceDefinition} is forced to {@link
   * ManifestLayer#CRDS} by kind regardless.
   */
  MANIFEST_LAYER("io.seedmatic.rke2lab/layer"),

  /**
   * Node label marking a node that runs the flox runtime — the NRI plugin injects there and the
   * flox-controller node-agent reconciles there (the flox DaemonSet's {@code nodeSelector}). The
   * SINGLE SOURCE of the label; POSED at boot by the nixos oneshot {@code rke2lab-node-labels}
   * (kubelet applies {@code --node-labels} only at first registration), guarded against Java↔nix
   * drift by the {@code node-label-concord} flake check.
   */
  NODE_FLOX_RUNTIME_LABEL("flox.seedmatic.io/enabled");

  private final String key;

  ManifestAnnotation(final String key) {
    this.key = key;
  }

  /** The annotation key. */
  public String key() {
    return key;
  }
}
