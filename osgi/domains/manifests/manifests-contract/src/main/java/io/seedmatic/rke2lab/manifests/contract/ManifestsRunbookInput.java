package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The wire contract for the manifests {@code runbook} trigger — the activation payload a sower must
 * supply to play the manifests scion. It is the INPUT twin of a reaped wire-record: the {@code
 * shape} meta-coordinate projects THIS record's JSON Schema so a sower learns the shape from the
 * broker door rather than compiling the class (see docs/architecture/osgi/seed-broker-spec.adoc §
 * introspection).
 *
 * <p>Its components carry five kinds of {@link Amendment}, each a SINGLE field its role binds by
 * value (the amont mapping the schema alone cannot express — see seed-broker-spec § @Amendment):
 *
 * <ul>
 *   <li>{@link Amendment#FACET} — {@link #facets} is the WHOLE {@code rke2lab:manifests:} concern
 *       map of {@code Pulumi.dev.yaml} ({@code {debug, delivery, workloadTargets}}), one composite
 *       component so the role binds to ONE field. The host contributes the yaml subtree verbatim as
 *       the FACET value (an {@link io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor} it
 *       registers into the world); the assembler gathers it at the amend door and the binder places
 *       it on {@code facets}, naming no manifests vocabulary — {@link Facets} mirroring the yaml is
 *       what keeps the copy blind. When no contributor offers FACET (a bare {@code shape} probe, a
 *       survey), it falls to {@link #defaults()}.
 *   <li>{@link Amendment#SOIL} — {@link #materializationRoot} is NOT in the yaml: it is the plot
 *       the scion materialises into, which only the host knows (it holds {@code BootstrapPaths}).
 *       The host fills it by role — the SOIL amendment — from its provisioning state (the
 *       staging-view {@code manifestsRoot}), never from a yaml key. {@link Optional#empty()} when
 *       unamended (a bare {@code shape} probe, or a survey run) — the scion then materialises into
 *       a temp dir; absence is an empty {@link Optional}, never a blank string.
 *   <li>{@link Amendment#IDENTITY} — {@link #identity} carries the cluster/node identity (see the
 *       {@code Identity} note below).
 *   <li>{@link Amendment#RENDER_MODE} — {@link #renderMode} is the render verb intent (seeded wins
 *       / HEAD wins / HEAD overlaid). {@link Optional#empty()} when unamended — seed-master's grow
 *       never sows it, so the scion reads {@code GROW} (its Pulumi facet stays authoritative); only
 *       the {@code manifests-cli} update/edit verbs sow it. Optional (not a compact-ctor default)
 *       so the amend door does not make it mandatory — an unsown mode is legitimately absent,
 *       exactly as for {@code SOIL}/{@code IDENTITY}.
 *   <li>{@link Amendment#IMAGE_STATE} — {@link #image} is the built node-base image's identity
 *       (alias, content fingerprint, build checksum, incus project/remote, baked RKE2 version) the
 *       incus scion COMPUTES from the freshly-built artifacts and forwards; the synthesis pins the
 *       {@code LXCMachineTemplate} image and the {@code RKE2ControlPlane} version from it. {@link
 *       Optional#empty()} when unsown (a bare survey / a render with no image built) — the image
 *       units then no-op.
 * </ul>
 *
 * <p>Because the host only fills amendments by role, ALL the domain knowledge lives in the scion
 * (OSGi-side): it decodes this record (jackson coerces the yaml's string {@code "true"} to {@code
 * boolean}), flattens the nesting, and translates into its own vocabulary — {@code FloxDebugPolicy}
 * (per-layer debug). The synth-time domain filter ({@link ManifestDomainPolicy}) is NOT carried
 * here: it follows the cluster's {@link ClusterRole} (parsed from the identity's clusterName),
 * structural to the cluster, not an operator toggle. The host names no {@code manifests.contract}
 * translation type.
 */
@SeedContract("runbook")
public record ManifestsRunbookInput(
    @Amendment(Amendment.FACET) Facets facets,
    @Amendment(Amendment.SOIL) Optional<String> materializationRoot,
    @Amendment(Amendment.IDENTITY) Optional<Identity> identity,
    @Amendment(Amendment.RENDER_MODE) Optional<RenderMode> renderMode,
    @Amendment(Amendment.IMAGE_STATE) Optional<ImageState> image) {

  public static Builder builder() {
    return new Builder();
  }

  /**
   * The network concern, read at the same depth as {@link #image} — it is STORED inside {@link
   * Facets} (that is what gets recorded on the branch and replayed by an in-cluster render), but a
   * consumer has no interest in which facet holds it, and {@code input.facets().network()} stutters
   * because the reader must then know both.
   */
  public Optional<NetworkFacet> network() {
    return facets().network();
  }

  /**
   * The complete facet with every concern at its default — the operator's usual posture, debug off,
   * and an UNAMENDED soil ({@link Optional#empty()} {@code materializationRoot} → the scion surveys
   * into a temp dir). The seed a scion holds before a sow arrives (never a partial instance): every
   * component is a complete sub-facet, so no incomplete state ever exists.
   */
  public static ManifestsRunbookInput defaults() {
    return builder().build();
  }

  /**
   * Named construction for the runbook input's three heterogeneous amendments. Field defaults are
   * the seed a scion holds before a sow arrives — default facets, an UNAMENDED soil, no identity —
   * so a caller names only the amendment it fills.
   */
  public static final class Builder {
    private Facets facets = Facets.defaults();
    private Optional<String> materializationRoot = Optional.empty();
    private Optional<Identity> identity = Optional.empty();
    private Optional<RenderMode> renderMode = Optional.empty();
    private Optional<ImageState> image = Optional.empty();

    private Builder() {}

    public Builder facets(Facets facets) {
      this.facets = facets;
      return this;
    }

    public Builder materializationRoot(String materializationRoot) {
      this.materializationRoot = Optional.of(materializationRoot);
      return this;
    }

    public Builder identity(Identity identity) {
      this.identity = Optional.of(identity);
      return this;
    }

    public Builder renderMode(RenderMode renderMode) {
      this.renderMode = Optional.of(renderMode);
      return this;
    }

    public Builder image(ImageState image) {
      this.image = Optional.of(image);
      return this;
    }

    public ManifestsRunbookInput build() {
      return new ManifestsRunbookInput(facets, materializationRoot, identity, renderMode, image);
    }
  }

  /**
   * The {@code rke2lab:manifests:} concern map, mirroring its yaml sub-map EXACTLY ({@code {debug,
   * delivery, workloadTargets}}) — the single {@link Amendment#FACET} component so the role binds
   * to ONE field (the binder rejects a role borne by several components as ambiguous). The host
   * contributes this whole subtree verbatim; the scion reads {@code facets().debug()} / {@code
   * facets().delivery()} / {@code facets().workloadTargets()} and flattens each. The compact
   * constructor coalesces any sub-facet the operator omitted to its default, so a partial yaml
   * decodes complete. Which manifest domains render is NOT here — it follows the cluster {@link
   * ClusterRole}.
   */
  public record Facets(
      DebugFacet debug,
      DeliveryFacet delivery,
      List<WorkloadTarget> workloadTargets,
      Optional<NetworkFacet> network) {

    /**
     * Coalesce absent sub-facets to their defaults — the host contributes the {@code
     * rke2lab:manifests:} yaml subtree VERBATIM, and jackson decodes a record component absent from
     * the yaml (a partial config that omits {@code delivery:}, {@code debug:} or {@code
     * workloadTargets:}) to {@code null}. The compact constructor makes every sub-facet non-null,
     * so a consumer reads a complete facet whatever the operator wrote (a partial yaml never NPEs
     * nor silently pushes). {@code workloadTargets} coalesces to an empty list — a management
     * cluster with no declared workloads.
     */
    public Facets {
      debug = debug != null ? debug : DebugFacet.disabled();
      delivery = delivery != null ? delivery : DeliveryFacet.defaults();
      workloadTargets = workloadTargets != null ? List.copyOf(workloadTargets) : List.of();
      // Coalesced to unknown(), NOT to a plausible bridge name: an absent parent renders a device
      // with an empty parent, visibly wrong in a manifest, where a default would silently attach a
      // node to the wrong bridge. It lives HERE rather than beside `image` because it is the same
      // KIND of fact as workloadTargets — a GROW-recorded coordinate the CLI's own facet never
      // sets.
      // Being inside Facets is what makes it travel: the branch records `facet:` verbatim, so an
      // in-cluster UPDATE replays it with no separate read/write path. As a top-level amendment it
      // was mandatory at the amend door and the in-cluster sower could not offer it, which broke
      // the
      // render the first time that path ever ran (measured 2026-09-28 — the stale PaC trigger had
      // hidden it).
      network = network != null ? network : Optional.empty();
    }

    public static Builder builder() {
      return new Builder();
    }

    public static Facets defaults() {
      return builder().build();
    }

    public static final class Builder {
      private DebugFacet debug = DebugFacet.disabled();
      private DeliveryFacet delivery = DeliveryFacet.defaults();
      private List<WorkloadTarget> workloadTargets = List.of();
      private Optional<NetworkFacet> network = Optional.empty();

      private Builder() {}

      public Builder network(NetworkFacet network) {
        this.network = Optional.of(network);
        return this;
      }

      public Builder debug(DebugFacet debug) {
        this.debug = debug;
        return this;
      }

      public Builder delivery(DeliveryFacet delivery) {
        this.delivery = delivery;
        return this;
      }

      public Builder workloadTargets(List<WorkloadTarget> workloadTargets) {
        this.workloadTargets = workloadTargets;
        return this;
      }

      public Facets build() {
        return new Facets(debug, delivery, workloadTargets, network);
      }
    }
  }

  /**
   * The {@code rke2lab:network:} concern subtree, mirroring its yaml EXACTLY. The render poses
   * every node's Incus devices and needs the FABRIC bridge those NICs attach to — an operator
   * declaration on the host side of the membrane. The vmnet bridge is NOT here: it follows the
   * cluster's role, so the blueprint derives it and restating it would duplicate a convention.
   *
   * <p>⚠️ There is no {@code unknown()} sentinel. It used to carry a BLANK parent to mean
   * "unamended", and a blank string is the same mistake as a null: a value that is PRESENT and
   * meaningless, which every consumer must then remember to test. Measured 2026-09-28 — one did
   * not, and the render published {@code parent=} for every node, a tree Flux would have applied
   * over a correct live value. So absence lives in the TYPE: {@link Facets#network()} is an {@link
   * Optional}, and a consumer that needs the bridge resolves it once and fails there.
   */
  public record NetworkFacet(String fabricBridgeParent) {

    public NetworkFacet {
      Objects.requireNonNull(fabricBridgeParent, "fabricBridgeParent");
      // Refused AT THE BOUNDARY — the canonical constructor is where jackson turns the recorded
      // yaml
      // into a typed value, so this is the one place that can guarantee the rest of the render
      // never
      // meets a present-but-meaningless bridge. Checking downstream instead is what let `parent=`
      // reach a published manifest.
      if (fabricBridgeParent.isBlank()) {
        throw new IllegalArgumentException(
            "fabricBridgeParent is blank — a NetworkFacet that exists must name a bridge; absence is"
                + " expressed by Optional.empty(), never by an empty string");
      }
    }
  }

  /**
   * The {@code manifests.delivery} concern — whether a rendered tree is force-pushed to its {@code
   * manifests/<cluster>} branch. {@code push} default OFF: the render crossing ALWAYS materialises
   * the tree into the render worktree and seals it with a signed commit (a local, reviewable act),
   * but only force-pushes to GitHub when the operator arms {@code rke2lab:manifests:push}. An
   * absent key defaults off, so a partial yaml never pushes by surprise.
   */
  public record DeliveryFacet(boolean push) {

    /** The safe default — render + commit locally, never push until the operator opts in. */
    public static DeliveryFacet defaults() {
      return new DeliveryFacet(false);
    }
  }

  /**
   * The cluster/node identity the gardener hands over as the {@link Amendment#IDENTITY} amendment —
   * the same neutral provisioning-scalar role the incus scion reconstructs its topology from. The
   * manifests scion needs only the identity subset (cluster + node name); the synthesis derives the
   * whole network topology from the cluster name (a pure function — see {@code
   * ClusterNetworkBlueprint.deriveRecipeModel}). A blind subtree mirroring the identity schema,
   * naming no other domain's type; the host's extra identity scalars are ignored on decode. EMPTY =
   * unamended (a bare survey / the direct CLI call) — the synthesis falls back to an unknown
   * identity.
   */
  public record Identity(String clusterName, String nodeName) {

    /**
     * The cluster identity {@code <host>-<role>} — the {@code clusterName} itself (e.g. {@code
     * bioskop-mgmt}). It is the rendered branch's name ({@code manifests/<clusterId>}) and its
     * render-worktree leaf — PER-CLUSTER, so a cluster's branch reads {@code
     * manifests/nikopol-mgmt} (no node suffix; every node of the cluster shares the one GitOps
     * branch).
     */
    public String clusterId() {
      return clusterName;
    }
  }

  /**
   * The {@code manifests.debug} concern, mirroring the yaml's {@code enabled}-wrapper nesting
   * exactly ({@code debug.mesh.enabled}, {@code debug.networking.enabled}, {@code
   * debug.nriPlugins.flox.enabled}). The scion flattens it into the three booleans {@code
   * FloxDebugPolicy} carries. An absent toggle defaults to off.
   */
  public record DebugFacet(Toggle mesh, Toggle networking, NriPlugins nriPlugins) {

    public static Builder builder() {
      return new Builder();
    }

    public static DebugFacet disabled() {
      return builder().build();
    }

    /**
     * Named construction over the three debug switches as plain booleans — the caller says {@code
     * mesh}/{@code networking}/{@code flox}, and the builder wraps them into the yaml-mirroring
     * {@link Toggle}/{@link NriPlugins} wire records so no call site handles that nesting. Every
     * switch defaults off.
     */
    public static final class Builder {
      private boolean mesh = false;
      private boolean networking = false;
      private boolean flox = false;

      private Builder() {}

      public Builder mesh(boolean mesh) {
        this.mesh = mesh;
        return this;
      }

      public Builder networking(boolean networking) {
        this.networking = networking;
        return this;
      }

      public Builder flox(boolean flox) {
        this.flox = flox;
        return this;
      }

      public DebugFacet build() {
        return new DebugFacet(
            new Toggle(mesh), new Toggle(networking), new NriPlugins(new Toggle(flox)));
      }
    }
  }

  /** The {@code {enabled: bool}} sub-object the yaml wraps each debug toggle in. */
  public record Toggle(boolean enabled) {}

  /** The {@code debug.nriPlugins} sub-map — a single {@code flox} toggle. */
  public record NriPlugins(Toggle flox) {}
}
