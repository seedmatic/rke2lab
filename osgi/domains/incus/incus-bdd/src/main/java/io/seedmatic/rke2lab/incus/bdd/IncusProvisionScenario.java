package io.seedmatic.rke2lab.incus.bdd;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.seedmatic.rke2lab.auth.contract.AuthTokenContact;
import io.seedmatic.rke2lab.doctor.contract.Checkpoint;
import io.seedmatic.rke2lab.doctor.contract.ConsultingService;
import io.seedmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.seedmatic.rke2lab.doctor.contract.ObservationWire;
import io.seedmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.seedmatic.rke2lab.incus.contract.ImageBuilder;
import io.seedmatic.rke2lab.incus.contract.IncusCoordinate;
import io.seedmatic.rke2lab.incus.contract.IncusHarvest;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput.Facet;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput.Image;
import io.seedmatic.rke2lab.incus.core.GrowIdentityResolver;
import io.seedmatic.rke2lab.incus.core.GrowNetworkResolver;
import io.seedmatic.rke2lab.incus.core.GrowPlanAssembler;
import io.seedmatic.rke2lab.incus.core.LaunchSecretsWriter;
import io.seedmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowImageView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.seedmatic.rke2lab.incus.ingress.IncusGrowCoordinate;
import io.seedmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import io.seedmatic.rke2lab.incus.ingress.SplitImageFingerprint;
import io.seedmatic.rke2lab.netplan.contract.NetplanSynthesisService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GraftTag;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedBroker;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.worktree.WorktreeCoordinate;
import io.seedmatic.rke2lab.worktree.WorktreeFacts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The incus provisioning checkpoint, a production jGiven scenario told in the INCUS DOMAIN's own
 * vocabulary — it PREPARES the instance's material: the seed image built through {@link
 * ImageBuilder} and the manifests tree cultivated by consulting the manifests scion through the
 * broker, no host/Pulumi type. It does NOT make the instance grow nor probe its reachability:
 * creating the instance is the {@code com.pulumi} graph, which stays HOST (Shape C — the scion asks
 * the host to grow it through the broker), and verifying reachability is the responsibility of
 * whoever grows it (the host, post-push). Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code incus-bdd} over the {@code incus-contract} seam
 * plus {@code incus-core} (the host-tree logic — {@code BootstrapPaths}: the scion owns the tree,
 * so it reconstructs the topology and derives the per-cluster render path in-world, §
 * host-cellar-realisation CORRECTION 2026-07-14; the heavy Pulumi-bound instance grow stays
 * host-side). Not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>The bbox/systemd/cluster twin, MODE-BLIND: its collaborators are INJECTED from its OWN
 * bundle's registry by the {@link OsgiService} bridge — the {@link ImageBuilder}, the {@link
 * io.seedmatic.rke2lab.seed.broker.port.SeedBroker} (to consult manifests), and, on a failure, the
 * doctor's {@link ConsultingService} (an optional snapshot). It injects NO {@code RunGate}: the
 * frontier reads the ambient gate once and hands it the cultivating or surveying {@link
 * ImageBuilder} impl by LDAP filter on {@code rke2lab.gardening}. The scenario is identical live
 * and in test; only who published the collaborators differs (the {@code Cultivating}/{@code
 * SurveyingImageBuilder} pair the frontier picks between + the real broker, or the mocks a test
 * seeds).
 *
 * <p>Preview inertness lives at the FRONTIER, not in the scenario: under a surveying gate the scion
 * gets the {@link SurveyingImageBuilder} (plans the build, shells nothing) and no {@code
 * AuthTokenContact} at all (the optional CLI probe is tagged cultivating, so it resolves empty and
 * secrets fall back to the environment — no CLI). The manifests synthesis is pure FS, so it runs in
 * both modes. Every step renders PENDING via E9. The image markers are fixed (the offline mock
 * ignores them; the live config-derived plumbing is the same deferral the cluster/systemd twins
 * make for their kubeconfig / endpoint).
 */
@SeedScenario
public class IncusProvisionScenario
    extends ScenarioTestBase<
        IncusProvisionScenario.Given, IncusProvisionScenario.When, IncusProvisionScenario.Then>
    implements CellarReceiver<ScenarioCellar>,
        InputReceiver<IncusRunbookInput>,
        ConsultationSource,
        ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code IncusRunbookHandler.seedFrom}) seeds the {@link
   * IncusRunbookInput} through and this scenario receives it from (via {@link InputReceiver}). It
   * is single-sourced here — the receiver owns the key + type — and referenced by the handler for
   * the seeding end ({@code INPUT.into(input)}). Registered as a {@link RegisterExtension} so its
   * {@code TestInstancePostProcessor} fires before the body reads {@link #input}, the way the
   * root's {@code SessionSeed} does.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<IncusRunbookInput> INPUT =
      new ScenarioInputSeed<>(IncusRunbookInput.class, "incus-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body (store→tag, not
  // registry-resolved).
  // Typed ScenarioCellar (not just Cellar): the manifests sub-sow reads transactionId() to pass the
  // run's tx on. @MonotonicNonNull: null until receiveCellar sets it (before the body), then read.
  @MonotonicNonNull private ScenarioCellar cellar;

  // The activation input the front-door seeds before the body (InputReceiver) — it carries the
  // @Amendment(SOIL) the scenario forwards to the manifests scion it consults. @MonotonicNonNull:
  // null until receiveInput sets it (before the body), then read.
  @MonotonicNonNull private IncusRunbookInput input;

  // Injected by the OsgiServiceExtension from THIS bundle's registry before the body. The edge
  // collaborators (imageBuilder, broker, netplan, authToken) moved to the stages that drive them
  // (@OsgiService there, filled by the stage creator). What stays on the scenario:
  // parcel — an ambient identity the body reads (Resolved.from) and threads to the Then; doctor —
  // await=false, the consult the body raises on a failure (empty when a world booted without it).
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  @OsgiService(await = false)
  private Optional<ConsultingService> doctor = Optional.empty();

  // The consultations the run raised on a failed build/unreachable instance — the
  // ScenarioOutcomeExtension PULLS them (ConsultationSource) at the run boundary. Set in the @Test
  // after the body (jGiven defers a failed step's throw to scenario-end); empty until then.
  private List<SeedEnvelope> consultations = List.of();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public void receiveInput(IncusRunbookInput input) {
    this.input = input;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Test
  void the_instance_is_prepared() throws IOException {
    final Parcel parcel = this.parcel.orElseThrow();
    final IncusRunbookInput input =
        Objects.requireNonNull(this.input, "the IncusRunbookInput was not seeded before the body");
    // The worktree root the scion anchors every path on — TAKEN from the cellar, where the worktree
    // soil harvested it at the first crossing. The worktree facts are immutable across the run, so
    // the scion fetches the snapshot rather than re-walking git live (fetch-not-push: harvest once,
    // take many). Present only for an AMENDED run (a survey has no facet, so it never dereferences
    // the harvest).
    final Optional<WorktreeFacts> worktreeFacts =
        cellar.fetch(parcel, WorktreeCoordinate.FACTS, WorktreeFacts.class);
    final Optional<Path> worktreeRoot =
        input.facet().isPresent()
            ? Optional.of(Path.of(worktreeFacts.orElseThrow().root()))
            : Optional.empty();
    // The scion reconstructs the provisioning topology from the FACET the host amended + the root
    // it
    // read (§ host-cellar-realisation, computed OSGi-side) and derives the paths the run needs —
    // the
    // per-cluster render plot (the SOIL forwarded to manifests), the liveRoot the host renders the
    // runbook into, and the worktree for provenance.
    final Optional<Resolved> resolved = Resolved.from(input.facet(), worktreeRoot);
    // The @Test body OWNS the observation sink (the same discipline as the other scions): the When
    // fills it, and the consult below reads THIS reference — independent of jGiven's stage state
    // after a fail-fast step, so a failed build still reaches the consult.
    final List<ObservationWire> observations = new ArrayList<>();
    // The two live handles the manifests graft folds the scion under — the same pair the root uses
    // per crossing (§ SowAndGraftStage): the current ScenarioModel, the trunk carrying the
    // rootstock
    // step "the manifests are cultivated" mid-run (jGiven appends the scenario to the ReportModel
    // only at scenario end, so getModel().getScenarios() is empty here), and the ReportModel, whose
    // live tag map the scion's within-run tags merge into.
    final ScenarioModel hostScenario = getScenario().getScenarioModel();
    final ReportModel hostTree = getScenario().getModel();
    // The seed node's hostname is the config-derived cluster-node identity (the incus INSTANCE is
    // named nodeName alone — see InstanceGrow); "seed" for an unamended survey with no worktree.
    final String seedNode =
        input.facet().map(f -> f.clusterName() + "-" + f.nodeName()).orElse("seed");
    given()
        .the_seed_node(seedNode)
        .and()
        .prepared_through(observations)
        .and()
        .consulting_manifests_through(resolved.map(Resolved::soil), cellar);
    when()
        .the_image_is_built(imageRequest(worktreeRoot, input.facet(), input.image()))
        .and()
        .the_manifests_are_cultivated(
            hostScenario, hostTree, input.facet(), input.image(), worktreeRoot)
        .and()
        .the_secrets_are_written(resolved)
        .and()
        .the_network_and_identity_are_resolved(resolved);
    then()
        .the_instance_is_prepared()
        .and()
        .the_prep_is_stored(resolved.map(Resolved::soil).orElse(""), cellar, parcel)
        .and()
        .the_instance_grow_plan_is_published(
            resolved, input.image().orElse(new Image("", "", "")), cellar, parcel);
    // Pose the live root the host renders the runbook into — a within-run fact whose layout
    // convention lives only here (§ seed-broker-spec, two cellars: the ephemeral cellar). The graft
    // merges this tag into the host tree; the host reads it via ScenarioGraft.graftedValue. Posed
    // on the model BEFORE it is stashed, so it rides the serialised runbook across the realm.
    resolved.ifPresent(r -> getScenario().getModel().addTag(GraftTag.LIVE_ROOT.of(r.liveRoot())));
    this.consultations = consultOnFailure(observations);
  }

  /**
   * The domain consults the doctor ITSELF on a failed build or unreachable instance (fork B: the
   * checkpoint owns its consult, not the host). Any observation carrying a symptom triggers it;
   * resolve the doctor's {@link ConsultingService} from THIS bundle's registry, build an {@code
   * incus-provision} SeedEnvelope around the observations, and consult. A clean provision raised no
   * symptom, so it consults no one (empty list).
   */
  private List<SeedEnvelope> consultOnFailure(List<ObservationWire> observations) {
    final boolean anySymptom = observations.stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return doctor
        .map(consulting -> List.of(consulting.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /** The {@code incus-provision} SeedEnvelope the domain hands the doctor — its observations. */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.INCUS_PROVISION.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The image-build request the scion drives. When both amendments are present (a live cultivating
   * run) it is derived from them: the incus daemon lives only on the remote {@code builderHost}
   * ({@code bioskop-nixos}), so from the seed-master host the edge streams the nix build over ssh
   * (nix alone resolves on the Mac, but no local daemon to import into). The workspace it {@code
   * cd}s into is the worktree root rebased onto the automount view ({@code
   * BootstrapPaths.asAutomountView}, e.g. {@code /net/bioskop.local/private/ …}), the SAME view the
   * Mac reads the artifacts back through. The artifact dir is that root's OWN subpath, so it rides
   * as a path RELATIVE to the workspace and the recipe joins the two — no second translation; the
   * artifact dir is the flat state root {@code .local.d} ({@code BootstrapPaths.STATE_DIR}).
   * Unamended (a bare survey or the offline scenario) it falls to a blank marker the surveying/mock
   * builder ignores.
   */
  private ImageBuildRequest imageRequest(
      Optional<Path> worktreeRoot, Optional<Facet> maybeFacet, Optional<Image> maybeImage) {
    if (maybeFacet.isEmpty() || maybeImage.isEmpty()) {
      return new ImageBuildRequest("nix", "/srv/host/incus-build", "artifacts", "", "", "", "");
    }
    final Facet facet = maybeFacet.orElseThrow();
    final Image image = maybeImage.orElseThrow();
    final Path localRoot = worktreeRoot.orElseThrow();
    final Path remoteRoot =
        BootstrapPaths.fromLocalWorktree(localRoot)
            .asAutomountView(facet.automount(), facet.netPrefix())
            .worktreeRoot();
    final Path artifactUnderWorktree = Path.of(BootstrapPaths.STATE_DIR);
    return new ImageBuildRequest(
        image.builderBinary(),
        localRoot.toString(),
        localRoot.resolve(artifactUnderWorktree).toString(),
        image.builderHost(),
        remoteRoot.toString(),
        artifactUnderWorktree.toString(),
        facet.incusProject());
  }

  /**
   * The topology the scion resolves ONCE from the worktree scalars — the inversion made concrete:
   * the scion owns the tree and derives every path the run needs. {@code soil} is the per-cluster
   * RENDER plot forwarded to the manifests scion ({@code .local.d/render/<cluster>}, the orphan
   * {@code manifests/<cluster>} worktree it prepares and force-pushes from — no rotating staging
   * slot); {@code liveRoot} is where the host renders the runbook; {@code worktreeRoot} the base
   * for the git provenance. {@link #from(Optional, Optional)} returns EMPTY for an unamended survey
   * (a bare {@code shape} probe) — absence is an empty {@link Optional}, never a record carried
   * with blank fields; a present {@code Resolved} always holds a real topology.
   */
  private record Resolved(
      String soil,
      String liveRoot,
      Path worktreeRoot,
      Path secretsFile,
      String clusterName,
      String nodeName,
      boolean automount,
      String netPrefix) {

    static Optional<Resolved> from(Optional<Facet> maybeFacet, Optional<Path> worktreeRoot) {
      if (maybeFacet.isEmpty()) {
        return Optional.empty();
      }
      final Facet facet = maybeFacet.orElseThrow();
      final Path root = worktreeRoot.orElseThrow();
      final BootstrapPaths local = BootstrapPaths.fromLocalWorktree(root);
      return Optional.of(
          new Resolved(
              // The manifests plot is now the per-cluster RENDER worktree the manifests scion
              // prepares (the `manifests/<cluster>` checkout the branch is pushed from) — no
              // rotating staging slot, no host.live.d promotion. The cluster identity is
              // <host>-<role> = clusterName-nodeName (the same identity the mDNS FQDN and the
              // seed-node name carry).
              local.renderRoot().resolve(facet.clusterName() + "-" + facet.nodeName()).toString(),
              local.liveRoot().toString(),
              root,
              local.secretsFile(),
              facet.clusterName(),
              facet.nodeName(),
              facet.automount(),
              facet.netPrefix()));
    }
  }

  /** Given: the seed node, the image builder, the observation sink, and the door. */
  static class Given extends Stage<Given> {

    @ProvidedScenarioState List<ObservationWire> observations;
    @ProvidedScenarioState Optional<String> soil;
    // This scion's working cellar (the seam type Cellar here), carried on to the manifests sub-sow
    // so it inherits the same tx (txId + in-flight entries). Resolved by TYPE — the only Cellar in
    // stage state.
    @ProvidedScenarioState Cellar cellar;

    public Given the_seed_node(@Quoted String name) {
      return self();
    }

    @Hidden
    public Given prepared_through(List<ObservationWire> observations) {
      this.observations = observations;
      return self();
    }

    @Hidden
    public Given consulting_manifests_through(Optional<String> soil, Cellar cellar) {
      this.soil = soil;
      this.cellar = cellar;
      return self();
    }
  }

  /**
   * When: the scion drives its image builder (mode-blind — the frontier chose cultivating or
   * surveying), and ALWAYS consults manifests, recording each facet's {@link ObservationWire} (ok,
   * or failed with a typed {@link SymptomKind}) into the shared sink, fail-fast on the first
   * failure. It PREPARES the instance's material (image + manifests); the host makes the instance
   * grow and verifies its reachability (Shape C — the gRPC push and its post-push probe are
   * host-side).
   *
   * <p>The split by NATURE of effect now lives at the frontier, not in the scenario: the image
   * build is an edge effect, so a surveying run gets the {@code SurveyingImageBuilder} (contacts
   * nothing, the plan renders PENDING via E9); the manifests synthesis is a pure FS materialisation
   * into {@code host.N.staging.d}, inert against the live instance, so it runs in both modes — a
   * survey run materialises its staging replica and its host-manifest (traceable from the cellar),
   * only the rsync into {@code host.live.d} is gated at reconcile (I6). So consulting manifests is
   * unconditional.
   */
  static class When extends Stage<When> {

    @ExpectedScenarioState List<ObservationWire> observations;
    @ExpectedScenarioState Optional<String> soil;
    @ExpectedScenarioState Cellar cellar;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario as step params. The edge collaborators the WHEN drives.
    @OsgiService private Optional<ImageBuilder> imageBuilder = Optional.empty();
    @OsgiService private Optional<SeedBroker> broker = Optional.empty();
    @OsgiService private Optional<NetplanSynthesisService> netplan = Optional.empty();

    @OsgiService(await = false)
    private Optional<AuthTokenContact> authToken = Optional.empty();

    // The flat network + identity views the scion projects for the host GROW — assembled here (WHEN
    // fabricates), sealed into the plan by the THEN. Null for an unamended survey (no cluster to
    // resolve). Both derive from the ONE netplan blueprint, so they are produced by the same WHEN.
    @ProvidedScenarioState GrowNetworkView networkView;
    @ProvidedScenarioState GrowIdentityView identityView;

    private final SeedCodec codec = new SeedCodec();
    private final ScenarioGraft graft = new ScenarioGraft();

    public When the_image_is_built(@Hidden ImageBuildRequest request) {
      // Mode-blind: the frontier already chose the builder — CultivatingNixosImageBuilder
      // (shells nix/ssh) or SurveyingImageBuilder (plans, shells nothing). The scion just
      // drives it; a surveying run touches nothing and the step renders PENDING.
      final ImageBuilder builder = imageBuilder.orElseThrow();
      try {
        builder.build(request);
      } catch (RuntimeException failed) {
        // The edge throws with its message AND (where it wraps one) its cause; chain it so the
        // runbook shows the reason and the stack, not a bare summary string.
        record("incus image", false, SymptomKind.IMAGE_BUILD_FAILED, failed.getMessage());
        throw new IncusImageBuildError(request, failed);
      }
      record("incus image", true, SymptomKind.IMAGE_BUILD_FAILED, null);
      return self();
    }

    /**
     * incus consults the manifests scion through the broker — the first metier scion→scion. The
     * instance mounts a materialised tree, which manifests cultivates; incus forwards its OWN
     * {@code @Amendment(SOIL)} as the manifests SOIL (a plot, never a fingerprint — a harvest is
     * fetched, not pushed). Two sows: AMEND reconciles the neutral role into the manifests input at
     * the door (incus names no manifests field), then RUNBOOK plays the synthesis with the amended
     * input. NOT gated: the synthesis writes {@code host.N.staging.d}, a pure FS materialisation
     * the host-tree model wants at preview too (only the rsync into {@code host.live.d} is gated).
     * The manifests scion picks a temp dir itself when the SOIL is blank (a bare survey).
     */
    public When the_manifests_are_cultivated(
        @Hidden ScenarioModel hostScenario,
        @Hidden ReportModel hostTree,
        @Hidden Optional<Facet> facet,
        @Hidden Optional<Image> image,
        @Hidden Optional<Path> worktreeRoot) {
      // AMEND: hand the broker {soil → path} by neutral role; the manifests amend reflector binds
      // it
      // onto ManifestsRunbookInput and returns the reconciled input, still under the runbook
      // coordinate.
      final ObjectNode roleValues = JsonNodeFactory.instance.objectNode();
      soil.ifPresent(s -> roleValues.put(Amendment.SOIL, s));
      // Forward the cluster/node identity as the manifests WORKTREE amendment — the SAME neutral
      // provisioning identity this scion reads from its own FACET. The manifests synthesis derives
      // addressing for THIS cluster from the handed-over name (no hardcoded literal); the extra
      // facet scalars it does not need are ignored on decode. Absent = a bare survey.
      facet.ifPresent(f -> roleValues.set(Amendment.IDENTITY, codec.decode(codec.encode(f))));
      // Forward the built node-base image's identity as the manifests IMAGE_STATE amendment — alias
      // +
      // split-image fingerprint + buildChecksum (from the SAME GrowPlanAssembler the THEN seals the
      // plan with) + the nix-emitted rke2.version — so the workload CRs pin the exact image the
      // master
      // booted and its RKE2 version. Absent for a survey / a run that built no artifacts.
      imageStateAmendment(facet, image, worktreeRoot)
          .ifPresent(node -> roleValues.set(Amendment.IMAGE_STATE, node));
      final SeedEnvelope amended =
          broker
              .orElseThrow()
              .sow(
                  new AmendCoordinate("manifests"),
                  cellar,
                  new SeedEnvelope("manifests", "runbook", codec.encode(roleValues)));
      // RUNBOOK: play the manifests synthesis with the reconciled input; the fresh tree is the
      // graft
      // the instance will mount (consumed at once, never cellared — cultivated fresh). The reaped
      // envelope carries the manifests RunbookEnvelope, so GRAFT its runbook under THIS step: the
      // manifests scion renders as a nested sub-tree of "the manifests are cultivated", and the
      // runbook stays faithful to the operation it names (§ decision a — every scion played is
      // rendered under its crossing). Pull the runbook JSON the way the host Gardening.sow does,
      // rebuild the model in THIS realm (only flat JSON crosses), and fold it under the rootstock —
      // a scion GRAFTING a sub-scion in-world, the same mechanism the root uses host-side. A
      // manifests failure rides IN the model: graftUnder marks this step FAILED and fail-fasts the
      // steps after it, so a broken synthesis is no longer silently green.
      final SeedEnvelope reaped =
          broker.orElseThrow().sow(new RunbookCoordinate("manifests"), cellar, amended);
      final String runbookJson = codec.decode(reaped.payload()).path("runbook").asText();
      graft.graftUnder(
          hostScenario,
          hostTree,
          "manifests",
          "the manifests are cultivated",
          graft.rebuild(runbookJson));
      return self();
    }

    /**
     * The built node-base image's identity as the {@code IMAGE_STATE} amendment JSON (blind,
     * mirroring the {@code ImageState} wire record's fields), or empty when the artifacts / the
     * emitted {@code rke2.version} were not produced (a survey / preview run). Computed from the
     * SAME {@link GrowPlanAssembler} the THEN seals the plan with — the alias, the two artifact
     * paths and the {@code buildChecksum} — plus the split-image fingerprint over those two
     * artifacts and the {@code rke2.version} beside them. The image-source remote is the node-base
     * image's home host, {@code https://<host>-nixos:8443}, derived from the cluster name (the
     * deterministic convention, matching the host bootstrap config + the netplan {@code
     * nixosHost}).
     */
    private Optional<ObjectNode> imageStateAmendment(
        Optional<Facet> maybeFacet, Optional<Image> maybeImage, Optional<Path> maybeWorktreeRoot) {
      if (maybeFacet.isEmpty() || maybeImage.isEmpty() || maybeWorktreeRoot.isEmpty()) {
        return Optional.empty();
      }
      final Facet facet = maybeFacet.orElseThrow();
      final Image image = maybeImage.orElseThrow();
      final Path worktreeRoot = maybeWorktreeRoot.orElseThrow();
      final GrowImageView view =
          new GrowPlanAssembler(
                  image.alias(),
                  image.builderBinary(),
                  image.builderHost(),
                  imageBuilder.orElseThrow().recipeDigest(),
                  worktreeRoot,
                  BootstrapPaths.fromLocalWorktree(worktreeRoot).stateRoot())
              .imageView();
      final Path metadata = Path.of(view.metadataPath());
      final Path rootfs = Path.of(view.dataPath());
      final Path versionFile = metadata.getParent().resolve("rke2.version");
      if (!Files.isRegularFile(metadata)
          || !Files.isRegularFile(rootfs)
          || !Files.isRegularFile(versionFile)) {
        // A survey / preview run built no artifacts — leave IMAGE_STATE unsown (the units no-op).
        return Optional.empty();
      }
      final String rke2Version;
      try {
        rke2Version = Files.readString(versionFile).strip();
      } catch (IOException ex) {
        throw new UncheckedIOException(
            "cannot read the emitted rke2.version at " + versionFile, ex);
      }
      final String host = facet.clusterName().split("-", 2)[0];
      final ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.put("imageAlias", view.imageAlias());
      node.put("imageFingerprint", SplitImageFingerprint.of(metadata, rootfs));
      node.put("imageBuildChecksum", view.buildChecksum());
      node.put("incusProject", facet.incusProject());
      node.put("incusRemoteAddress", "https://" + host + "-nixos:8443");
      node.put("rke2Version", rke2Version);
      return Optional.of(node);
    }

    /**
     * Upsert the flox launch token into the worktree's {@code .secrets} (§ provisioning-slice delta
     * #10) — a WHEN because it FABRICATES material (a file materialisation), BEFORE the {@code
     * worktree.dir} mount binds it into the instance. {@link LaunchSecretsWriter} resolves the
     * token from the environment first, else the {@link AuthTokenContact} edge. Mode-blind: the
     * inertness lives at the frontier — the {@code AuthTokenContact} CLI probe is tagged {@code
     * cultivating}, so a surveying run resolves it empty (no {@code flox} shelled) and the writer
     * falls back to the environment; the step renders PENDING via E9. Skipped only for an unamended
     * survey (no worktree {@code .secrets} to upsert). (GitHub is no longer written here — its
     * credential flows from the App via {@code ghapp}, sealed in the cellar.)
     */
    public When the_secrets_are_written(@Hidden Optional<Resolved> maybeResolved) {
      if (maybeResolved.isEmpty()) {
        return self();
      }
      final Resolved resolved = maybeResolved.orElseThrow();
      new LaunchSecretsWriter(authToken).ensureTokensPresent(resolved.secretsFile());
      return self();
    }

    /**
     * Resolve the network + identity views the host GROW poses — the two NIC hwaddrs + the {@code
     * vmnet} bridge's dnsmasq config, AND the four per-node identity scalars (name, {@code
     * <cluster>-<node>} hostname, server/agent kind, id), all projected flat from the ONE netplan
     * blueprint (§ the scion-projects/host-actualises rule). A WHEN because it FABRICATES the
     * values the THEN seals into the grow plan. NOT gated: a pure OSGi computation off the {@link
     * NetplanSynthesisService}, no edge contacted — inert at preview like the manifests synthesis.
     * Skipped for an unamended survey (no cluster to resolve).
     */
    public When the_network_and_identity_are_resolved(@Hidden Optional<Resolved> maybeResolved) {
      if (maybeResolved.isEmpty()) {
        return self();
      }
      final Resolved resolved = maybeResolved.orElseThrow();
      this.networkView =
          new GrowNetworkResolver(netplan.orElseThrow())
              .resolve(resolved.clusterName(), resolved.nodeName());
      this.identityView =
          new GrowIdentityResolver(netplan.orElseThrow())
              .resolve(resolved.clusterName(), resolved.nodeName());
      return self();
    }

    private void record(String facet, boolean ok, SymptomKind failureSymptom, String detail) {
      if (ok) {
        observations.add(
            new ObservationWire("ok", facet, Optional.empty(), Map.of("facet", facet)));
        return;
      }
      observations.add(
          new ObservationWire(
              "failed",
              facet + ": " + (detail == null ? "not ready" : detail),
              Optional.of(failureSymptom),
              Map.of("facet", facet)));
    }
  }

  /**
   * Then: the instance's material is prepared — reached only once the image built and manifests
   * were cultivated (a failure throws in the When). The readable closing line; the host then makes
   * the instance grow (Shape C) and verifies its reachability, not this scion.
   */
  static class Then extends Stage<Then> {

    // The network + identity views the WHEN produced — read here to seal them into the grow plan.
    // Null for an unamended survey (the_network_and_identity_are_resolved skipped).
    @ExpectedScenarioState GrowNetworkView networkView;
    @ExpectedScenarioState GrowIdentityView identityView;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // the Then reads its recipe digest, no longer threaded from the scenario.
    @OsgiService private Optional<ImageBuilder> imageBuilder = Optional.empty();

    public Then the_instance_is_prepared() {
      return self();
    }

    /**
     * The scion harvests AND stores — the reversal made concrete (§ host-cellar-realisation,
     * every-scion-contributes), the twin of the bbox scion's {@code the_harvest_is_stored}. It
     * folds the prep INTENTION into an {@link IncusHarvest} — the {@link
     * ImageBuilder#recipeDigest() recipe digest} (stable across a closed gate, the host's
     * image-cache key) and the {@code soil} the manifests tree was cultivated under — and stores it
     * at the {@code incus-prep} coordinate under the current {@link Parcel}. On the Pulumi
     * realisation this store PRODUCES the incus-prep resource; the host FETCHES the harvest and
     * grows the instance (Shape C). The store is unconditional: the cellar consults the RunGate
     * itself to route conserve ({@code up}) vs pre-reserve ({@code preview}), so the scion never
     * picks the mode.
     */
    public Then the_prep_is_stored(
        @Hidden String soil, @Hidden Cellar cellar, @Hidden Parcel parcel) {
      final IncusHarvest harvest =
          new IncusHarvest(imageBuilder.orElseThrow().recipeDigest(), soil);
      cellar.store(parcel, IncusCoordinate.INCUS_PREP, harvest);
      return self();
    }

    /**
     * Seal the ONE immutable {@link InstanceGrowPlan} the host GROW fetches — the scion-projects
     * part of the scion-projects/host-actualises rule (§ host-cellar-realisation,
     * the-grow-anatomy). The network + identity views the {@code
     * the_network_and_identity_are_resolved} WHEN produced, plus the image view the {@link
     * GrowPlanAssembler} computes OSGi-side (the {@code buildChecksum} folding the edge {@code
     * recipeDigest} with the image scalars and the nix source digest, and the readable artifact
     * paths). Stored at {@link IncusGrowCoordinate#INSTANCE_GROW_PLAN} — the single record the host
     * decodes host-side via the dual-realm codec. Skipped for an unamended survey (no view
     * resolved). The store is unconditional on the gate: the cellar routes conserve vs pre-reserve,
     * so a preview run still records the plan.
     */
    public Then the_instance_grow_plan_is_published(
        @Hidden Optional<Resolved> maybeResolved,
        @Hidden Image image,
        @Hidden Cellar cellar,
        @Hidden Parcel parcel) {
      if (maybeResolved.isEmpty() || networkView == null) {
        return self();
      }
      final Resolved resolved = maybeResolved.orElseThrow();
      // The NixOS node-base substrate bakes the node's config, so the instance takes no host disk
      // mounts and no cloud-init seed — the plan carries the network + image + identity views, the
      // last posed as user.rke2lab.node-* keys the guest reads over devlxd.
      final InstanceGrowPlan plan =
          new GrowPlanAssembler(
                  image.alias(),
                  image.builderBinary(),
                  image.builderHost(),
                  imageBuilder.orElseThrow().recipeDigest(),
                  resolved.worktreeRoot(),
                  BootstrapPaths.fromLocalWorktree(resolved.worktreeRoot()).stateRoot())
              .assemble(networkView, identityView);
      cellar.store(parcel, IncusGrowCoordinate.INSTANCE_GROW_PLAN, plan);
      return self();
    }
  }
}
