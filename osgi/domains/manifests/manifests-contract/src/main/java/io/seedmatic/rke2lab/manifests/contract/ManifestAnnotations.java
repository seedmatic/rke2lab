// @codebase
package io.seedmatic.rke2lab.manifests.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata annotations for rke2lab manifest resources.
 *
 * <p>These annotations enable manifest exploding (splitting consolidated YAML into per-resource
 * files organized by domain/package).
 *
 * <p>A stateless record — the annotation keys are compile-time constants (composable at
 * static-init), and {@link #packageAnnotations} is the pure map-building behavior a manifest unit
 * calls. Record, not a plain class, so it stays on the contract face (contract-purity: the seam
 * carries records / enums / interfaces, never a concrete class).
 */
public record ManifestAnnotations() {

  public static final String DOMAIN = "io.seedmatic.rke2lab/domain";
  public static final String PACKAGE = "io.seedmatic.rke2lab/package";

  /**
   * Marks a manifest that the cluster must not apply (KRM convention). {@code
   * rke2lab-manifests-install.sh} skips symlinking these into the RKE2 server manifests dir, and
   * the exploder writes them as hidden dotfiles by default.
   */
  public static final String LOCAL_CONFIG = "config.kubernetes.io/local-config";

  /**
   * Marks a ConfigMap whose {@code .data} entries are RKE2 server config fragments. The exploder
   * writes these verbatim (visible {@code <name>}, no dotfile) so {@code rke2lab-config-install.sh}
   * can glob them into {@code /etc/rancher/rke2/config.yaml.d}.
   */
  public static final String RKE2_CONFIG = "io.seedmatic.rke2lab/rke2-config";

  /**
   * Marks the per-unit group inventory ConfigMap emitted by {@link AbstractManifestsUnit}. Carries
   * {@link #LOCAL_CONFIG} too, so it is never applied to the cluster; this annotation identifies it
   * as an inventory marker rather than a real local-config resource.
   */
  public static final String MANIFEST_GROUP = "io.seedmatic.rke2lab/manifest-group";

  /**
   * Marks a resource on the node-side bootstrap lane — the small set the host seeds into the node's
   * RKE2 {@code server/manifests} over devlxd at grow time (Flux operator/instance/root, the
   * bootstrap Secrets, the cilium {@code HelmChartConfig}), so the CNI and Flux come up before the
   * rendered branch is reachable. The exploder collects these into a single {@code
   * .bootstrap/rke2lab-bootstrap.yaml} multi-doc file OUTSIDE the committed branch tree, rather
   * than the per-resource cluster-apply tree — they are delivered node-side, never committed nor
   * applied from the branch.
   */
  public static final String NODE_BOOTSTRAP = "io.seedmatic.rke2lab/node-bootstrap";

  /**
   * The reconcile LAYER a rendered resource belongs to — the exploder routes it into {@code
   * <layer>/<domain>/<package>/…} and {@code FluxRootManifestsUnit} emits one {@code Kustomization}
   * per layer, chained by {@code dependsOn} so a CR's CRD (rendered OR registered at runtime by an
   * operator/installer in an earlier layer) exists before the CR is dry-run. Absent ⇒ {@link
   * #LAYER_WORKLOADS}; a {@code CustomResourceDefinition} is forced to {@link #LAYER_CRDS} by kind
   * regardless. See {@code docs/architecture/cluster-api/manifests-rendered-branches.adoc} §layers.
   */
  public static final String MANIFEST_LAYER = "io.seedmatic.rke2lab/layer";

  /** Layer 1 — rendered {@code CustomResourceDefinition}s (applied first). */
  public static final String LAYER_CRDS = "crds";

  /**
   * Layer 2 — cluster-wide providers that later operators depend on (cert-manager, whose HelmChart
   * registers the {@code cert-manager.io} CRDs + runs the issuer that signs operator webhook
   * certs). It sits before {@link #LAYER_OPERATORS} because an operator's install bundles a {@code
   * Certificate} whose CRD must be registered — and controller running — before it dry-runs.
   */
  public static final String LAYER_FOUNDATION = "foundation";

  /** Layer 3 — operator/installer resources that register CRDs / controllers at runtime. */
  public static final String LAYER_OPERATORS = "operators";

  /** Layer 4 (default) — the CRs that depend on a CRD from an earlier layer. */
  public static final String LAYER_WORKLOADS = "workloads";

  /**
   * Node label marking a node that runs the flox runtime — the NRI plugin injects there and the
   * flox-controller node-agent reconciles there. Consumed as the {@code nodeSelector} of the flox
   * DaemonSet (FloxControllerManifestsUnit). On the {@code flox.seedmatic.io} domain (shared with
   * the CRD group + the injection annotation), migrated from the upstream {@code flox.dev/enabled}.
   *
   * <p>This constant is the SINGLE SOURCE of the label. It is POSED on the node at boot by the
   * nixos oneshot {@code rke2lab-node-labels} (nixos/rke2.nix) — not a manifest, since kubelet
   * applies {@code --node-labels} only at first registration. The two worlds (Java ↔ nix) can't
   * import each other, so the {@code node-label-concord} flake check ({@code nix flake check})
   * guards them against drift: it fails if nixos/rke2.nix stops carrying this exact value.
   */
  public static final String NODE_FLOX_RUNTIME_LABEL = "flox.seedmatic.io/enabled";

  public Map<String, String> packageAnnotations(final String domain, final String packageName) {
    return packageAnnotations(domain, packageName, Map.of());
  }

  public Map<String, String> packageAnnotations(
      final String domain, final String packageName, final Map<String, String> extraAnnotations) {
    LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(DOMAIN, domain);
    annotations.put(PACKAGE, packageName);
    annotations.putAll(extraAnnotations);
    return Map.copyOf(annotations);
  }
}
