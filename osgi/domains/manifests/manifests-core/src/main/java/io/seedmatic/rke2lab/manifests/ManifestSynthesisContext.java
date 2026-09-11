// @codebase
package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.WorkloadTarget;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ClusterIssuerCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ManagementClusterCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.seedmatic.rke2lab.manifests.contract.profiles.OperatorPkiMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.SigningKeyMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.ingress.ComponentVersions;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Synthesis-scoped context exposing per-synth policies and identity data to layer code. The
 * synthesizer publishes the context for the duration of {@code synthesize(...)} and resets it on
 * exit; layers reach it through {@link AbstractManifestsUnit} accessors without keeping a static
 * dependency on a process-wide singleton.
 *
 * <p>The context wraps the {@link ManifestSynthesisRequest} (the host→OSGi frontier type) and
 * delegates each synthesis slice to it, so a new slice on the request needs no change here. Layers
 * reach only for what they need:
 *
 * <ul>
 *   <li>{@link FloxDebugPolicy} — flox NRI debug toggle (image / command / env swap).
 *   <li>{@link BootstrapIdentity} — cluster + node identity (cluster name, id, token, Incus
 *       remote/identity, …).
 *   <li>{@link NetworkTopology} — CIDRs, interface names, gateway addresses.
 *   <li>{@link ComponentVersions} — kube-vip, tailscale, envoy-gateway, … versions.
 *   <li>{@link ImageState} — Stage A → Stage B control-node image identity for the image-state
 *       ConfigMap.
 *   <li>{@link IncusIdentityMaterial} — Stage A → Stage B Incus identity for the identity Secret.
 * </ul>
 *
 * <p>When no synthesis is in progress (direct unit tests of a Layer without {@code synthesize}),
 * {@link #current()} returns a default context whose slices are all-disabled / unknown / empty.
 */
public final class ManifestSynthesisContext {

  private static final ManifestSynthesisContext DEFAULT =
      new ManifestSynthesisContext(
          ManifestSynthesisRequest.builder(Path.of("."), Path.of("manifests.yaml")).build());

  private static final ThreadLocal<ManifestSynthesisContext> CURRENT = new ThreadLocal<>();

  private final ManifestSynthesisRequest request;

  /**
   * The age key, resolved by the synthesis service's pre-synthesis step (read the SSH key, convert
   * it via the {@code SshToAgeConverter} edge) and bound here. Unlike the request-borne profiles,
   * this is NOT supplied by the host across the frontier — it is derived inside the OSGi world, so
   * it is a context field of its own, {@link Optional#empty()} when no key-store was present.
   */
  private final Optional<SopsAgeMaterial> sopsAgeMaterial;

  /**
   * The commit-signing key, resolved by the same pre-synthesis step (from the ndh key-store, ONLY
   * as OPERATOR) and bound here — the twin of {@link #sopsAgeMaterial}, {@link Optional#empty()}
   * when no key-store was present or the render runs in-cluster.
   */
  private final Optional<SigningKeyMaterial> signingKeyMaterial;

  private ManifestSynthesisContext(ManifestSynthesisRequest request) {
    this(request, Optional.empty(), Optional.empty());
  }

  private ManifestSynthesisContext(
      ManifestSynthesisRequest request,
      Optional<SopsAgeMaterial> sopsAgeMaterial,
      Optional<SigningKeyMaterial> signingKeyMaterial) {
    this.request = Objects.requireNonNull(request, "request");
    this.sopsAgeMaterial = Objects.requireNonNull(sopsAgeMaterial, "sopsAgeMaterial");
    this.signingKeyMaterial = Objects.requireNonNull(signingKeyMaterial, "signingKeyMaterial");
  }

  public static ManifestSynthesisContext of(ManifestSynthesisRequest request) {
    return new ManifestSynthesisContext(request);
  }

  /** Context carrying the pre-synthesis-resolved age key + signing key alongside the request. */
  public static ManifestSynthesisContext of(
      ManifestSynthesisRequest request,
      Optional<SopsAgeMaterial> sopsAgeMaterial,
      Optional<SigningKeyMaterial> signingKeyMaterial) {
    return new ManifestSynthesisContext(request, sopsAgeMaterial, signingKeyMaterial);
  }

  public static ManifestSynthesisContext current() {
    final ManifestSynthesisContext current = CURRENT.get();
    return current != null ? current : DEFAULT;
  }

  /**
   * Publishes THIS context for the calling thread. Returns an {@link AutoCloseable} that restores
   * the previous binding (which may be the default) when closed — wrap in try-with-resources to
   * keep the scope tight and exception-safe. The context publishes itself; the {@link #CURRENT}
   * ThreadLocal stays a private detail of the type that owns it.
   */
  public Scope bind() {
    final ManifestSynthesisContext previous = CURRENT.get();
    CURRENT.set(this);
    return new Scope(previous);
  }

  public FloxDebugPolicy floxDebugPolicy() {
    return request.floxDebugPolicy();
  }

  public BootstrapIdentity bootstrapIdentity() {
    return request.bootstrapIdentity();
  }

  /**
   * The render's network topology — a PURE FUNCTION of {@link #bootstrapIdentity()} (the cluster +
   * node name), derived through the same {@link DefaultNodeEnvContext} blueprint the node-env units
   * use. Deriving here rather than carrying a separate request slice keeps ONE source of truth: the
   * identity. (The former request-borne slice was never populated by any render driver, so a reader
   * — kube-vip's VIP, the CAPI kubeconfig endpoint — silently got a blank address.)
   */
  public NetworkTopology networkTopology() {
    return new DefaultNodeEnvContext(request.bootstrapIdentity()).networkTopology();
  }

  public ComponentVersions componentVersions() {
    return request.componentVersions();
  }

  /**
   * The workload clusters this (management) render must emit CAPI CRs for — the cluster-api units
   * derive each target's {@code ClusterNetworkBlueprint} from its {@link
   * WorkloadTarget#clusterName()}. Empty on a mgmt-only or standalone run. Distinct from {@link
   * #bootstrapIdentity()}, which stays the render's own (management) cluster.
   */
  public List<WorkloadTarget> workloadTargets() {
    return request.workloadTargets();
  }

  public Optional<ImageState> imageState() {
    return request.imageState();
  }

  public Optional<IncusIdentityMaterial> incusIdentity() {
    return request.incusIdentity();
  }

  public Optional<OperatorPkiMaterial> operatorPki() {
    return request.operatorPki();
  }

  public Optional<ClusterIssuerCaMaterial> clusterIssuerCa() {
    return request.clusterIssuerCa();
  }

  public Optional<WorkloadClusterCasMaterial> workloadCas() {
    return request.workloadCas();
  }

  public Optional<ManagementClusterCaMaterial> managementCas() {
    return request.managementCas();
  }

  public Optional<GithubAppMaterial> githubApp() {
    return request.githubApp();
  }

  public Optional<ReplicatorSourceSecretsMaterial> replicatorSources() {
    return request.replicatorSources();
  }

  public Optional<SopsAgeMaterial> sopsAgeMaterial() {
    return sopsAgeMaterial;
  }

  public Optional<SigningKeyMaterial> signingKeyMaterial() {
    return signingKeyMaterial;
  }

  /** Restores the previous binding when closed. */
  public static final class Scope implements AutoCloseable {
    private final ManifestSynthesisContext previous;

    private Scope(ManifestSynthesisContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
