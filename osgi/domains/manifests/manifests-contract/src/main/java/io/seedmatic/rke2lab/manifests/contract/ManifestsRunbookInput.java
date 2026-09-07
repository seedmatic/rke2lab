package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;
import java.util.Optional;

/**
 * The wire contract for the manifests {@code runbook} trigger — the activation payload a sower must
 * supply to play the manifests scion. It is the INPUT twin of a reaped wire-record: the {@code
 * shape} meta-coordinate projects THIS record's JSON Schema so a sower learns the shape from the
 * broker door rather than compiling the class (see docs/architecture/osgi/seed-broker-spec.adoc §
 * introspection).
 *
 * <p>Its components carry four kinds of {@link Amendment}, each a SINGLE field its role binds by
 * value (the amont mapping the schema alone cannot express — see seed-broker-spec § @Amendment):
 *
 * <ul>
 *   <li>{@link Amendment#FACET} — {@link #facets} is the WHOLE {@code rke2lab:manifests:} concern
 *       map of {@code Pulumi.dev.yaml} ({@code {publish, debug, delivery, workloadTargets}}), one
 *       composite component so the role binds to ONE field. The host contributes the yaml subtree
 *       verbatim as the FACET value (an {@link
 *       io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor} it registers into the world);
 *       the assembler gathers it at the amend door and the binder places it on {@code facets},
 *       naming no manifests vocabulary — {@link Facets} mirroring the yaml is what keeps the copy
 *       blind. When no contributor offers FACET (a bare {@code shape} probe, a survey), it falls to
 *       {@link #defaults()}.
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
 * </ul>
 *
 * <p>Because the host only fills amendments by role, ALL the domain knowledge lives in the scion
 * (OSGi-side): it decodes this record (jackson coerces the yaml's string {@code "true"} to {@code
 * boolean}), flattens the nesting, and translates into its own vocabulary — {@link
 * ManifestDomainPolicy} (synth-time filter) + {@code FloxDebugPolicy} (per-layer debug) + the
 * {@code RKE2LAB_MANIFESTS_PUBLISH_*} publish-time env contributions. The host names no {@code
 * manifests.contract} translation type.
 */
@SeedContract("runbook")
public record ManifestsRunbookInput(
    @Amendment(Amendment.FACET) Facets facets,
    @Amendment(Amendment.SOIL) Optional<String> materializationRoot,
    @Amendment(Amendment.IDENTITY) Optional<Identity> identity,
    @Amendment(Amendment.RENDER_MODE) Optional<RenderMode> renderMode) {

  public static Builder builder() {
    return new Builder();
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

    public ManifestsRunbookInput build() {
      return new ManifestsRunbookInput(facets, materializationRoot, identity, renderMode);
    }
  }

  /**
   * The {@code rke2lab:manifests:} concern map, mirroring its yaml sub-map EXACTLY ({@code
   * {publish, debug, delivery}}) — the single {@link Amendment#FACET} component so the role binds
   * to ONE field (the binder rejects a role borne by several components as ambiguous). The host
   * contributes this whole subtree verbatim; the scion reads {@code facets().publish()} / {@code
   * facets().debug()} / {@code facets().delivery()} and flattens each. The compact constructor
   * coalesces any sub-facet the operator omitted to its default, so a partial yaml decodes
   * complete.
   */
  public record Facets(
      PublishFacet publish,
      DebugFacet debug,
      DeliveryFacet delivery,
      List<WorkloadTarget> workloadTargets) {

    /**
     * Coalesce absent sub-facets to their defaults — the host contributes the {@code
     * rke2lab:manifests:} yaml subtree VERBATIM, and jackson decodes a record component absent from
     * the yaml (a partial config that omits {@code delivery:}, {@code publish:}, {@code debug:} or
     * {@code workloadTargets:}) to {@code null}. The compact constructor makes every sub-facet
     * non-null, so a consumer reads a complete facet whatever the operator wrote (a partial yaml
     * never NPEs nor silently pushes). {@code workloadTargets} coalesces to an empty list — a
     * management cluster with no declared workloads.
     */
    public Facets {
      publish = publish != null ? publish : PublishFacet.defaults();
      debug = debug != null ? debug : DebugFacet.disabled();
      delivery = delivery != null ? delivery : DeliveryFacet.defaults();
      workloadTargets = workloadTargets != null ? List.copyOf(workloadTargets) : List.of();
    }

    public static Builder builder() {
      return new Builder();
    }

    public static Facets defaults() {
      return builder().build();
    }

    public static final class Builder {
      private PublishFacet publish = PublishFacet.defaults();
      private DebugFacet debug = DebugFacet.disabled();
      private DeliveryFacet delivery = DeliveryFacet.defaults();
      private List<WorkloadTarget> workloadTargets = List.of();

      private Builder() {}

      public Builder publish(PublishFacet publish) {
        this.publish = publish;
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
        return new Facets(publish, debug, delivery, workloadTargets);
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
   * The {@code manifests.publish} concern: which domain manifest layers the master publishes into
   * RKE2's server-manifests directory (a symlink/stow it auto-applies). Flat booleans, mirroring
   * the yaml sub-map exactly; the scion feeds them to {@link ManifestDomainPolicy.Builder} and the
   * {@code RKE2LAB_MANIFESTS_PUBLISH_*} overlay. An absent key defaults to the operator's usual
   * posture (everything on except {@code mesh}) so a partial yaml still yields a complete facet.
   */
  public record PublishFacet(
      boolean gitops,
      boolean networking,
      boolean clusterApi,
      boolean storage,
      boolean mesh,
      boolean highAvailability,
      boolean cicd) {

    public static Builder builder() {
      return new Builder();
    }

    /** The operator's usual posture — every domain on except {@code mesh}. */
    public static PublishFacet defaults() {
      return builder().build();
    }

    /**
     * Named construction for the seven publish toggles: the canonical constructor's positional
     * booleans do not say which flag is which, so every factory routes through here. Field defaults
     * mirror {@link #defaults()} — all on except {@code mesh} — so a caller names only what
     * diverges.
     */
    public static final class Builder {
      private boolean gitops = true;
      private boolean networking = true;
      private boolean clusterApi = true;
      private boolean storage = true;
      private boolean mesh = false;
      private boolean highAvailability = true;
      private boolean cicd = true;

      private Builder() {}

      public Builder gitops(boolean gitops) {
        this.gitops = gitops;
        return this;
      }

      public Builder networking(boolean networking) {
        this.networking = networking;
        return this;
      }

      public Builder clusterApi(boolean clusterApi) {
        this.clusterApi = clusterApi;
        return this;
      }

      public Builder storage(boolean storage) {
        this.storage = storage;
        return this;
      }

      public Builder mesh(boolean mesh) {
        this.mesh = mesh;
        return this;
      }

      public Builder highAvailability(boolean highAvailability) {
        this.highAvailability = highAvailability;
        return this;
      }

      public Builder cicd(boolean cicd) {
        this.cicd = cicd;
        return this;
      }

      public PublishFacet build() {
        return new PublishFacet(
            gitops, networking, clusterApi, storage, mesh, highAvailability, cicd);
      }
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
