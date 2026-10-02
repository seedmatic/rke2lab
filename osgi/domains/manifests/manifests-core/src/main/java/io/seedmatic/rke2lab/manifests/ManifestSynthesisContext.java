// @codebase
package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.ClusterCoordinate;
import io.seedmatic.rke2lab.manifests.contract.ClusterFleet;
import io.seedmatic.rke2lab.manifests.contract.ControlPlaneShape;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
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
import io.seedmatic.rke2lab.manifests.contract.profiles.TlsAuthorityCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadBootstrapBundlesMaterial;
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

  /**
   * The RepoTags of the images baked into the node, READ from what the build staged — see {@link
   * ContainerImageRefs} for why they cannot be literals. Not carried on the request: they are facts
   * of the build, identical for every run, so nothing amends them.
   */
  /**
   * The bridge every node's fabric NIC attaches to — the operator's {@code rke2lab:network:}
   * declaration, carried in the manifests facet and therefore RECORDED on the branch, which is what
   * lets an in-cluster render replay it.
   *
   * <p>⚠️ This is the ONE place its absence becomes an error, and the placement is deliberate. It
   * is resolved HERE, when a unit actually poses devices, not when the render is assembled: a
   * survey that renders nothing device-bearing never asks and must not fail. Resolving it eagerly
   * upstream broke exactly that — measured 2026-09-28 against the in-container scenario test, whose
   * surveyed materialiser is documented to render PENDING.
   *
   * <p>And it throws rather than answering a blank. A blank travelled once and the render published
   * {@code parent=} for every node, a tree Flux would have applied over a correct live value. A
   * failed render leaves the last good tree on the branch; a published one detaches every node.
   */
  public String fabricBridgeParent() {
    return request
        .fabricBridgeParent()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "this render poses node devices but has no fabric bridge:"
                        + " facet.network.fabricBridgeParent is absent from both the sown amendment"
                        + " and the branch's recorded facet. Re-render from the HOST once to seed"
                        + " it."));
  }

  public ContainerImageRefs containerImages() {
    return ContainerImageRefs.staged();
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
   * The clusters this render must emit CAPI CRs for — its OWN children, DERIVED from the declared
   * fleet by the owner rule ({@link ClusterFleet#ownedBy}) rather than enumerated by the operator.
   * The cluster-api units derive each child's {@code ClusterNetworkBlueprint} from its {@link
   * ClusterCoordinate#clusterName()}. Distinct from {@link #bootstrapIdentity()}, which stays the
   * render's own cluster — never in this list.
   *
   * <p>Empty when no fleet is declared (a bare survey / the standalone CLI) or when the subject
   * cluster is unknown — a render that does not know WHO it is cannot know what it owns, and
   * answering "everything" there is exactly the viewpoint error the derivation exists to remove.
   */
  /**
   * The cluster that ADOPTS {@code cluster} — derived from the declared fleet ({@link
   * ClusterFleet#adopterOf}), stated on the rendered intention so every plane can carry the same
   * federation view and derive its role from its position in it.
   *
   * <p>Empty when no fleet is declared (a bare survey / the standalone CLI). A caller must then
   * render NO intention: one without an adopter is the state that let a sub-plane adopt itself by
   * recognising its own name — the same discipline as an absent {@link #imageState()}, where the
   * unit renders nothing rather than a misleading placeholder.
   */
  public Optional<String> adopterOf(final String cluster) {
    return request.clusterFleet().map(fleet -> fleet.adopterOf(cluster));
  }

  /**
   * EVERY cluster the fleet declares — the UNIFORM federation view that lands on every plane's
   * branch. Empty when no fleet is declared (a bare survey / the standalone CLI).
   *
   * <p>A plane renders the DECLARATION for all of these and acts on only the ones it adopts ({@link
   * #adopts}). That is what "same view, different position" means concretely, and it subsumes what
   * used to be a render filter: a plane may carry an intention for a cluster whose Incus engine it
   * cannot reach, because it will not act on it.
   */
  /**
   * The control-plane shape every WORKLOAD cluster takes, from the declared fleet. Empty when no
   * fleet is declared (a bare survey), and then the caller renders no cluster anyway.
   */
  public Optional<ControlPlaneShape> workloadControlPlane() {
    return request.clusterFleet().map(ClusterFleet::workloadControlPlane);
  }

  public List<ClusterCoordinate> fleetClusters() {
    return request.clusterFleet().map(ClusterFleet::all).orElseGet(List::of);
  }

  /**
   * Whether the render SUBJECT is {@code cluster}'s adopter — the one question that decides what
   * this plane carries MATERIAL for. The declaration is common to every plane; credentials are not:
   * a plane holds them only for what it adopts.
   */
  public boolean adopts(final String cluster) {
    final String self = request.bootstrapIdentity().clusterName();
    if (BootstrapIdentity.UNKNOWN.equals(self)) {
      return false;
    }
    return adopterOf(cluster).map(self::equals).orElse(false);
  }

  public List<ClusterCoordinate> ownedChildren() {
    final Optional<ClusterFleet> fleet = request.clusterFleet();
    if (fleet.isEmpty()) {
      return List.of();
    }
    final String self = request.bootstrapIdentity().clusterName();
    if (BootstrapIdentity.UNKNOWN.equals(self)) {
      return List.of();
    }
    return fleet.orElseThrow().ownedBy(self);
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

  public Optional<TlsAuthorityCaMaterial> tlsAuthorityCa() {
    return request.tlsAuthorityCa();
  }

  public Optional<WorkloadClusterCasMaterial> workloadCas() {
    return request.workloadCas();
  }

  public Optional<ManagementClusterCaMaterial> managementCas() {
    return request.managementCas();
  }

  /**
   * The bootstrap bundle each workload target's OWN render pass carved — the only material not
   * revealed from a seal, produced in the same run by the manager's per-target pass.
   */
  public Optional<WorkloadBootstrapBundlesMaterial> workloadBootstrapBundles() {
    return request.workloadBootstrapBundles();
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
