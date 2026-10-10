package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.auth.contract.GithubAppTokens;
import io.seedmatic.rke2lab.auth.contract.GithubReaderTokenMint;
import io.seedmatic.rke2lab.auth.contract.GithubWriterTokenMint;
import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterIssuerCa;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiCoordinate;
import io.seedmatic.rke2lab.clusterpki.contract.ManagementClusterCa;
import io.seedmatic.rke2lab.clusterpki.contract.WorkloadClusterCas;
import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.manifests.contract.ClusterCoordinate;
import io.seedmatic.rke2lab.manifests.contract.ClusterFleet;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import io.seedmatic.rke2lab.manifests.contract.RenderMode;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.TlsAuthorityCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadBootstrapBundlesMaterial;
import io.seedmatic.rke2lab.manifests.ingress.NodeGithubToken;
import io.seedmatic.rke2lab.manifests.ingress.NodeGithubTokenCoordinate;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsBundle;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsCoordinate;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.Persistence;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.worktree.GitBotIdentities;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The manifests synthesis scenario, a production jGiven scenario told in the MANIFESTS DOMAIN's own
 * vocabulary — the operator's activation facet ({@link ManifestsRunbookInput}: debug + delivery +
 * workload targets) translated into the synthesis input and materialised, no host/Pulumi type.
 * Played IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it is the
 * fifth scion, on the trio pattern the four contact scions share, with the differences its nature
 * forces (see docs/architecture/osgi/manifests-bdd-spec.adoc § what makes it different): it
 * synthesises + materialises rather than probing a live system, it READS its trigger (the others
 * ignore theirs), and it consults no doctor (a synthesis failure is a build defect, not a symptom).
 *
 * <p>The WHEN stage is the transposition of {@code HostSlotManifest.Builder.policy()} — the
 * projection the incus-bootstrap demolition orphaned — now OSGi-side: it derives the {@link
 * ManifestDomainPolicy} (synth-time filter, which layers synthesise) from the cluster's {@link
 * ClusterRole} (parsed from the identity's clusterName), owning the {@link ManifestDomainCatalog}.
 * So the control-plane policy is reactivated INSIDE synthesis, structural to the role: the {@link
 * ManifestSynthesisRequest} carries it, and the {@link ManifestSynthesisService} materialises only
 * the layers the role publishes — invisible at the master frontier.
 *
 * <p>A run plays <strong>N+1 passes</strong>, not one: a {@link ClusterRole#WRKLD} pass per {@link
 * ClusterCoordinate} — each onto that child's own {@code manifests/<cluster>} branch — and then the
 * managing pass, which consumes what those carved ({@link WorkloadBootstrapBundlesMaterial}) to
 * render each target's {@code <cluster>-server-manifests} Secret. A cluster being born cannot
 * render its own first branch (no Tekton, no Flux, and no pod at all until its CNI is configured —
 * itself a bootstrap-set resource), so the manager renders it. See
 * docs/architecture/cluster-api/manifests-rendered-branches.adoc § per-target-pass.
 *
 * <p>Its collaborator is INJECTED from its OWN bundle's registry by the {@link OsgiService} bridge:
 * the {@link ManifestSynthesisService} (the SCR-published synthesis). MODE-BLIND — it injects no
 * run gate: manifests is a pure FS materialiser with no live touch (no {@code Cultivating}/{@code
 * Surveying} pair), so it runs identically in both modes; the materialisation target is carried by
 * the SOIL amendment alone (the real tree when the host amended a plot, a temp dir for a bare
 * survey), and rendering the run PENDING under a surveying gate is the frontier's business (the
 * engine's survey executor), not the scenario's. The activation facet is seeded by the front-door
 * via the inbound {@link #INPUT} channel and received here ({@link InputReceiver}) before the play;
 * the outbound {@code ScenarioOutcome} channel harvests the played runbook.
 */
@SeedScenario
public class ManifestSynthesisScenario
    extends ScenarioTestBase<
        ManifestSynthesisScenario.Given,
        ManifestSynthesisScenario.When,
        ManifestSynthesisScenario.Then>
    implements InputReceiver<ManifestsRunbookInput>,
        CellarReceiver<ScenarioCellar>,
        ScenarioPlayer.Playable {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  /**
   * The inbound channel the runbook handler ({@code ManifestsRunbookHandler.seedFrom}) seeds the
   * {@link ManifestsRunbookInput} facet through and this scenario receives it from (via {@link
   * InputReceiver}). Single-sourced here — the receiver owns the key + type — and referenced by the
   * handler for the seeding end ({@code INPUT.into(facet)}). Registered as a {@link
   * RegisterExtension} so its {@code TestInstancePostProcessor} fires before the body reads {@link
   * #input}.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<ManifestsRunbookInput> INPUT =
      new ScenarioInputSeed<>(ManifestsRunbookInput.class, "manifests-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The activation facet the front-door seeds before the body (InputReceiver) — debug + delivery +
  // workload targets + identity the WHEN translates (the domain set follows the role, not the
  // facet). @MonotonicNonNull: null until receiveInput sets it (before the body), then read.
  @MonotonicNonNull private ManifestsRunbookInput input;

  // The shared in-container cellar (injected by ScenarioCellarExtension before the body) + the
  // current plot — the seam through which the sealed cases other scions filed are revealed as their
  // owners' records, in-container, never crossing the host membrane.
  @MonotonicNonNull private ScenarioCellar cellar;

  // await=false: the parcel is genuinely OPTIONAL — a bare survey or a run before the seal filed
  // has
  // none, and revealOperatorPki() guards on parcel.isEmpty() for exactly that. A required (await)
  // injection would contradict that guard, throwing before the body on any parcel-less play.
  @OsgiService(await = false)
  private Optional<Parcel> parcel = Optional.empty();

  // The rendered-branch delivery seam and the ndh key-store, both OPTIONAL (await=false): a bare
  // survey / the standalone manifests-cli renders into a temp dir with no branch and no signature,
  // so
  // absence is honest — the render still materialises, only the git delivery is skipped. Present in
  // a
  // provisioning run (worktree-core + ndh-core embedded), where the render lands in the linked
  // worktree the GROW mounts and is sealed with a signed commit.
  @OsgiService(await = false)
  private Optional<LinkedWorktrees> linkedWorktrees = Optional.empty();

  // The SOURCE worktree — the checkout the process runs in (the Tekton FETCH_HEAD clone in-cluster,
  // the grow's worktree otherwise). Its jgit provenance (HEAD sha + dirty) stamps the render's
  // commit subject and the recorded manifest, so a rendered branch is traceable to the exact
  // rke2lab
  // rev that synthesised it — no wrapper plumbing, jgit is already in the boat. Same await=false
  // shape as linkedWorktrees: present together (LinkedWorktrees is built @Reference Worktree),
  // absent
  // on a bare survey.
  @OsgiService(await = false)
  private Optional<Worktree> sourceWorktree = Optional.empty();

  @OsgiService(await = false)
  private Optional<NdhKeystoreReader> keystore = Optional.empty();

  // The ambient enclosure gate (host-published, § pac-in-cluster-render-spec auth): the
  // deterministic
  // fork for the ndh key-store reads. await=false + OPERATOR default (absent → read the key-store,
  // the
  // standalone/test path); an in-cluster render always publishes it (inCluster=true).
  @OsgiService(await = false)
  private Optional<EnclosureGate> enclosure = Optional.empty();

  // The on-demand push-token mint (auth-edge, cultivating). OPERATOR mints a FRESH WRITER token
  // here at the moment of the push, through GithubAppTokens, from the App ghapp sealed — the
  // ephemeral (~1 h) token is never sealed, so it can't go stale between a mint and a much later
  // reveal. Absent under a survey/preview frontier → the push is skipped.
  @OsgiService(await = false)
  private Optional<GithubWriterTokenMint> writerTokenMint = Optional.empty();

  // The on-demand READ-token mint (auth-edge, cultivating), the least-privilege twin: OPERATOR
  // mints
  // a FRESH contents:read token from the same durable App creds, filed SEALED + TRANSIENT for the
  // node to fetch its rendered branch (fileNodeGithubToken). Absent under a survey/preview → no
  // token
  // filed (an in-cluster render's workload node takes its config from CAPRKE2, not a github fetch).
  @OsgiService(await = false)
  private Optional<GithubReaderTokenMint> readerTokenMint = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(ManifestsRunbookInput input) {
    this.input = input;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  // The cluster-pki seal's sealed cases, revealed as the OWNER's records and handed to synthesis as
  // they are: the operator's admin PKI, the cluster-issuer CA, the workload clusters' BYO-CA sets
  // and the management cluster's own. Empty when no cellar/plot (a bare survey), before the seal
  // filed, or on a secret-blind in-cluster render (EphemeralCellar) — the delivering unit then
  // renders no Secret onto the branch, the material riding the durable NODE_BOOTSTRAP lane.
  private Optional<AdminCredentials> revealOperatorPki() {
    return reveal(ClusterPkiCoordinate.ADMIN_CREDENTIALS, AdminCredentials.class);
  }

  private Optional<ClusterIssuerCa> revealClusterIssuerCa() {
    return reveal(ClusterPkiCoordinate.CLUSTER_ISSUER_CA, ClusterIssuerCa.class);
  }

  private Optional<WorkloadClusterCas> revealWorkloadCas() {
    return reveal(ClusterPkiCoordinate.WORKLOAD_CLUSTER_CAS, WorkloadClusterCas.class);
  }

  private Optional<ManagementClusterCa> revealManagementCas() {
    return reveal(ClusterPkiCoordinate.MANAGEMENT_CLUSTER_CAS, ManagementClusterCa.class);
  }

  private <T> Optional<T> reveal(SeedCoordinate coordinate, Class<T> type) {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(parcel.orElseThrow(), coordinate, type);
  }

  // Reveal the CAPN provider incus identity (assembled + sealed by the incus-identity seal scion)
  // via the shared IncusIdentityCase coordinate. Empty on a bare survey / before the seal filed / a
  // secret-blind in-cluster render → ClusterApiWorkloadManifestsUnit renders no
  // <host>-incus-identity
  // Secret (it rides the durable NODE_BOOTSTRAP lane).
  private Optional<IncusIdentityMaterial> revealIncusIdentity() {
    return reveal(IncusIdentityCase.INCUS_IDENTITY, IncusIdentityMaterial.class);
  }

  /**
   * The one org-owned App's credentials the ghapp registration sealed, revealed as the owner's
   * record so the {@code githubapp} Secret unit renders them for Flux's native App auth. Empty on a
   * bare survey / before the registration filed — the unit then renders nothing.
   */
  private Optional<GithubAppCredentials> revealGithubApp() {
    return reveal(GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class);
  }

  /**
   * The mittwald-replicator SOURCE secrets the {@code replicator-secrets} seal rehydrated from
   * {@code .secrets} and filed SEALED, revealed from the cellar in-container so {@code
   * ReplicatorManifestsUnit} renders them onto the node-bootstrap lane. Empty on a bare survey /
   * before the seal filed (an empty material seals nothing) → the unit renders no source secrets.
   */
  private Optional<ReplicatorSourceSecretsMaterial> revealReplicatorSources() {
    return reveal(ReplicatorSecretsCase.REPLICATOR_SECRETS, ReplicatorSourceSecretsMaterial.class);
  }

  private static final String TAILNET_AUTHORITY = NdhKeystoreCatalog.TAILNET_AUTHORITY.entryName();

  // The tailnet authority DOMAIN (the key-store's authorities.mammoth-skate.domain). IN_CLUSTER the
  // sops key-store is unreadable, so the bot identity takes this constant — the same deployment
  // coupling TAILNET_AUTHORITY already carries.
  private static final String TAILNET_DOMAIN = "mammoth-skate.ts.net";
  private static final String SIGNING_KEY = NdhKeystoreCatalog.SIGNING_KEY.entryName();

  // The env a mounted Secret feeds the IN_CLUSTER commit-signing key through (the NODE_BOOTSTRAP →
  // replicator lane), the twin of the PaC-provided RKE2LAB_PUSH_TOKEN.
  private static final String SIGNING_KEY_ENV = "RKE2LAB_SIGNING_KEY";
  private static final String RENDER_TOOL = "manifests-render";
  private static final String BRANCH_PREFIX = "manifests/";

  // What a worktree keeps for itself, the same rule every worktree of this project follows. The
  // render commits it into its branch, so a render in-cluster (no operator-side ignore) keeps its
  // intermediates out of the branch too.
  private static final String WORKTREE_IGNORE =
      NodeBootstrapArtifact.LOCAL_DIR + "/\n" + ".scratchpad.d/\n";

  // The cluster's FIRST control node — the one a per-target pass renders as. Taken from the
  // blueprint's canonical roster, never spelled "master" here.
  private static final String FIRST_CONTROL_NODE =
      ClusterNetworkBlueprint.CANONICAL_NODE_NAMES.get(0);

  /**
   * Prepare the rendered-branch worktree for THIS run's cluster — an orphan linked worktree at the
   * SOIL path on branch {@code manifests/<cluster>}, into which the synthesis materialises the
   * rendered tree (the GROW then mounts this worktree). Present only when the delivery seam is
   * reachable (a provisioning run) AND the host amended a SOIL plot AND the cluster identity is
   * known; a bare survey / the standalone CLI leaves it empty and the synthesis falls back to a
   * temp dir, with no branch and no commit.
   */
  private Optional<LinkedWorktree> prepareRenderWorktree(ManifestsRunbookInput facet) {
    if (linkedWorktrees.isEmpty()
        || facet.materializationRoot().isEmpty()
        || facet.identity().isEmpty()) {
      return Optional.empty();
    }
    final String cluster = facet.identity().orElseThrow().clusterId();
    final Path worktreePath =
        Path.of(facet.materializationRoot().orElseThrow()).toAbsolutePath().normalize();
    return Optional.of(
        linkedWorktrees
            .orElseThrow()
            .prepare(worktreePath, BRANCH_PREFIX + cluster, revealFetchToken()));
  }

  // Reads the branch HEAD's recorded facet — a plain YAMLMapper, native record binding (jackson
  // reads the record component names). Symmetric with the Then's write of the same {source, facet}.
  // Tolerant of unknown keys: a branch recorded before the publish facet was removed still carries
  // a `publish:` sub-map under `facet:`; the domain set is now role-derived, so that key is stale
  // and ignored rather than failing the replay decode.
  // UNTYPED only: this parses the recorded yaml into a tree. Turning that tree into a wire record
  // is
  // the CODEC's job, because decoding one is a SET of rules — Optional via Jdk8Module, seam enums,
  // Instant, unknown keys tolerated — and a hand-configured mapper is one more place to get them
  // wrong. Measured 2026-09-28: it was, the moment a sub-facet component became Optional
  // (`Optional<NetworkFacet> not supported by default: add Module jackson-datatype-jdk8`).
  // SeedCodec's
  // own javadoc names this reader among what it supersedes: its tolerance is "the contract the
  // hand-rolled *Reader classes had".
  private static final YAMLMapper FACET_READER =
      (YAMLMapper)
          new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /** The ONE set of wire-record decoding rules — never a bare mapper. */
  private static final SeedCodec CODEC = new SeedCodec();

  // The name the Then records the facet under at the branch root (Then.RENDER_FACET_FILE).
  private static final String RENDERED_FACET_FILE = "manifest.yaml";

  /**
   * The facet a render should USE, per the {@link RenderMode} the sower carried:
   *
   * <ul>
   *   <li>{@code GROW}/{@code INIT} — the SEEDED facet is authoritative (the grow's Pulumi stack /
   *       the CLI args). {@code INIT} additionally GUARDS that the branch is new: a recorded facet
   *       at HEAD means it already exists, so it fails loud rather than silently reset it.
   *   <li>{@code UPDATE} — the branch's recorded HEAD facet wins (debug + workload targets); the
   *       seeded {@code delivery} is kept, as it carries the verb's push intent. Guards the branch
   *       EXISTS.
   *   <li>{@code EDIT} — the recorded HEAD facet OVERLAID with the sparse operator overrides
   *       ({@link RenderMode#overrides}); seeded {@code delivery} kept. Guards the branch EXISTS.
   *       The overlaid facet is what the THEN re-records, so it becomes the new HEAD (one shot).
   * </ul>
   *
   * <p>The recorded facet at HEAD is the presence signal for the guards: a fresh (orphan) branch
   * carries only the null-base README, no {@code manifest.yaml}, so {@link #recordedFacets} is
   * empty. Absent a rendered worktree (a survey) the seeded facet always stands (GROW default).
   */
  private ManifestsRunbookInput resolveFacet(
      ManifestsRunbookInput seeded, Optional<LinkedWorktree> rendered) {
    final RenderMode mode = seeded.renderMode().orElseGet(RenderMode::grow);
    final RenderMode.Verb verb = mode.verb();
    final Optional<String> headManifest =
        rendered.flatMap(worktree -> worktree.readAtHead(RENDERED_FACET_FILE));
    // EXISTENCE only — deliberately not a decode. The guards below ask whether the branch already
    // records a facet, which is a question about the FILE, and answering it by decoding made a GROW
    // hostage to a recording it is about to overwrite: measured 2026-09-28, a branch carrying a
    // value
    // the rules now reject (a blank bridge parent) refused the very grow that would have replaced
    // it.
    // The same trap, one field over, as the ImageState note below — which is why this is separated.
    final boolean headRecordsFacet = headManifest.filter(this::recordsFacet).isPresent();
    switch (verb) {
      case INIT -> {
        if (headRecordsFacet) {
          throw new IllegalStateException(
              "init: manifests/<cluster> already has a recorded facet — use update or edit");
        }
      }
      case UPDATE, EDIT -> {
        if (!headRecordsFacet) {
          throw new IllegalStateException(
              verb.name().toLowerCase(java.util.Locale.ROOT)
                  + ": manifests/<cluster> has no recorded facet yet — use init");
        }
      }
      case GROW -> {
        // No guard: the grow's facet is the SSOT, applied whether the branch is new or not.
      }
    }
    // The node-base ImageState the last grow recorded at HEAD is replayed into a steady-state
    // UPDATE/EDIT render, because the incus scion (the live IMAGE_STATE amendment) runs ONLY at the
    // grow — without it the in-cluster render is ImageState-blind and would empty the image-pinned
    // CR set.
    //
    // ★ Decoded INSIDE the arms that use it, never before the switch. A GROW carries a live
    // ImageState and overwrites the recording, so it must not be held hostage by one it is about to
    // replace: read eagerly, an undecodable HEAD (a recording predating a field this ImageState
    // requires) refused the very grow that would have healed it. Measured 2026-09-28 on a cold
    // start.
    return switch (verb) {
      case GROW, INIT -> seeded;
      case UPDATE ->
          withDebug(
              seeded,
              headManifest.flatMap(this::recordedFacets).orElseThrow(),
              headManifest.flatMap(this::recordedImage));
      case EDIT ->
          withDebug(
              seeded,
              overlay(headManifest.flatMap(this::recordedFacets).orElseThrow(), mode.overrides()),
              headManifest.flatMap(this::recordedImage));
    };
  }

  /**
   * A copy of {@code seeded} taking debug + the fleet from {@code facets} (HEAD), keeping only the
   * seeded {@code delivery}. Rationale: debug/incusTargets are GROW-recorded coordinates (the CLI's
   * facet never sets incusTargets — it comes from the grow's Pulumi config), so HEAD wins; only
   * {@code delivery} is verb-carried (the CLI's push intent). An earlier version took the targets
   * from {@code seeded} and a steady-state render — whose seeded facet has none — stripped them off
   * the recorded manifest, emptying the workload CR set. The domain set is no longer replayed here:
   * it is a function of the cluster ROLE (see {@link ClusterRole}), derived fresh from the identity
   * on every render.
   */
  private ManifestsRunbookInput withDebug(
      ManifestsRunbookInput seeded,
      ManifestsRunbookInput.Facets facets,
      Optional<ImageState> recordedImage) {
    // ★ Assembled through the BUILDER, by NAME. The positional constructor swapped
    // rootIncusHost with workloadControlPlane here — both are Optional<String>, so it compiled
    // silently and the in-cluster render died on `unknown control-plane shape 'bioskop'` (measured
    // 2026-09-30). Two adjacent same-typed components make a positional call a trap that no
    // compiler can catch, which is exactly the case the builder discipline exists for.
    final ManifestsRunbookInput.Facets.Builder merged =
        ManifestsRunbookInput.Facets.builder()
            // HEAD wins for debug/incusTargets: they are GROW-recorded coordinates (the CLI's facet
            // never sets incusTargets — it comes from the grow's Pulumi config). Only `delivery` is
            // verb-carried, so it is the seeded one.
            .debug(facets.debug())
            .delivery(seeded.facets().delivery())
            .incusTargets(facets.incusTargets());
    // The three optional coordinates below share ONE rule — HEAD wins, the SOWER is the fallback —
    // because each was added after branches already existed: a branch recorded before the field
    // falls back to what the sower declares, and a sower that declares one is never overridden.
    // Left ABSENT when neither has it, so the record's own default applies rather than a value
    // invented here.
    facets
        .rootIncusHost()
        .or(() -> seeded.facets().rootIncusHost())
        .ifPresent(merged::rootIncusHost);
    facets
        .workloadControlPlane()
        .or(() -> seeded.facets().workloadControlPlane())
        .ifPresent(merged::workloadControlPlane);
    // The fabric bridge is likewise GROW-recorded. An earlier version took the SEEDED value on the
    // premise that "a branch does not record it" — true only while the sower was a host. The
    // in-cluster render has no host to declare it, so recording it is what makes that render
    // possible at all.
    facets.network().or(() -> seeded.facets().network()).ifPresent(merged::network);
    return new ManifestsRunbookInput(
        merged.build(),
        seeded.materializationRoot(),
        seeded.identity(),
        seeded.renderMode(),
        // A live seeded image (a grow) wins; else replay the ImageState the grow recorded at HEAD,
        // so a steady-state render pins the same node-base image instead of emptying the CR set.
        seeded.image().or(() -> recordedImage));
  }

  /**
   * Overlay the sparse EDIT overrides onto a base facet: each entry is a dotted JSON path into the
   * facet ({@code debug.mesh.enabled}, {@code debug.networking.enabled}) → boolean. Applied at the
   * JSON level — the CLI owns the arg → path mapping, here it is generic — then decoded back to a
   * facet. No {@code publish.*} path: the domain set follows the cluster ROLE, not a facet.
   */
  private ManifestsRunbookInput.Facets overlay(
      ManifestsRunbookInput.Facets base, java.util.Map<String, Boolean> overrides) {
    // Both ends through the CODEC, so the round-trip obeys ONE set of rules: a bare mapper here
    // would
    // drop an Optional sub-facet on the way out and fail to read it back on the way in.
    final ObjectNode json = FACET_READER.valueToTree(CODEC.toMap(base));
    overrides.forEach((path, value) -> setBooleanAtPath(json, path, value));
    try {
      return CODEC.fromMap(json, ManifestsRunbookInput.Facets.class);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("cannot overlay the edit facet: " + overrides, ex);
    }
  }

  private void setBooleanAtPath(ObjectNode root, String dottedPath, boolean value) {
    final String[] parts = dottedPath.split("\\.");
    ObjectNode node = root;
    for (int i = 0; i < parts.length - 1; i++) {
      final JsonNode next = node.get(parts[i]);
      node = (next instanceof ObjectNode object) ? object : node.putObject(parts[i]);
    }
    node.put(parts[parts.length - 1], value);
  }

  /**
   * Decode the {@code facet} sub-tree of a recorded {@code manifest.yaml} — THREE-valued, like
   * {@link #recordedImage}: empty only when nothing was recorded, and a refusal when something was
   * but this build cannot read it.
   *
   * <p>⚠️ It used to answer empty for both. That is the two-valued probe this repo has now paid for
   * twice: a sub-facet whose constructor rejects its recorded value (a blank bridge parent, say)
   * surfaces as a {@code JsonProcessingException} — an {@code IOException} — so "undecodable"
   * collapsed into "absent", and the render would then drop the recorded debug AND the fleet with
   * it, emptying the workload CR set on a branch that was merely too old. Absence is legitimate;
   * unreadability is a bug that must not be answered with a plausible empty.
   */
  /**
   * Does this recording carry a {@code facet:} at all — asked WITHOUT decoding it. The verb guards
   * need existence, not a value, and conflating the two is what let a recording the current rules
   * reject refuse a GROW that was about to overwrite it.
   */
  private boolean recordsFacet(String manifestYaml) {
    try {
      final JsonNode facet = FACET_READER.readTree(manifestYaml).path("facet");
      return !facet.isMissingNode() && !facet.isNull();
    } catch (IOException unreadableContext) {
      throw new IllegalStateException(
          "the render context recorded at HEAD is unreadable, so whether it records a facet cannot be"
              + " decided; refusing to guess",
          unreadableContext);
    }
  }

  private Optional<ManifestsRunbookInput.Facets> recordedFacets(String manifestYaml) {
    final JsonNode facet;
    try {
      facet = FACET_READER.readTree(manifestYaml).path("facet");
    } catch (IOException unreadableContext) {
      throw new IllegalStateException(
          "the render context recorded at HEAD is unreadable, so the facet it records cannot be"
              + " replayed; refusing to render as though the branch carried no policy",
          unreadableContext);
    }
    if (facet.isMissingNode() || facet.isNull()) {
      return Optional.empty();
    }
    try {
      return Optional.of(CODEC.fromMap(facet, ManifestsRunbookInput.Facets.class));
    } catch (IllegalArgumentException undecodable) {
      throw new IllegalStateException(
          "HEAD records a facet this build cannot decode (a recording that predates a field this"
              + " Facets requires, or a value it now rejects); refusing to render as though the"
              + " branch carried no policy",
          undecodable);
    }
  }

  /**
   * Decode the {@code image} sub-tree of a recorded {@code manifest.yaml} — the node-base {@link
   * ImageState} the grow recorded, so a steady-state in-cluster {@code UPDATE}/{@code EDIT} render
   * replays it (the incus scion, hence a live {@code IMAGE_STATE} amendment, runs ONLY at the
   * grow). Empty if absent/unreadable (a branch recorded before this landed, or a grow that built
   * no image).
   */
  private Optional<ImageState> recordedImage(String manifestYaml) {
    final JsonNode image;
    try {
      image = FACET_READER.readTree(manifestYaml).path("image");
    } catch (IOException unreadableContext) {
      throw new IllegalStateException(
          "the render context recorded at HEAD is unreadable, so the image it records cannot be"
              + " replayed; refusing to render as though the cluster had none",
          unreadableContext);
    }
    // ABSENT — no image was ever recorded here (a branch older than this field, or a grow that
    // built none). The only legitimate empty: there is nothing to replay.
    if (image.isMissingNode() || image.isNull()) {
      return Optional.empty();
    }
    try {
      return Optional.of(CODEC.fromMap(image, ImageState.class));
    } catch (IllegalArgumentException undecodable) {
      throw new IllegalStateException(
          "HEAD records an image this build cannot decode (a recording that predates a field this"
              + " ImageState requires, or a shape change); refusing to render as though the cluster"
              + " had no image",
          undecodable);
    }
  }

  /**
   * The fabric bridge, PASSED THROUGH as an Optional rather than resolved here. Its absence becomes
   * an error in {@link ManifestSynthesisContext#fabricBridgeParent()}, when a unit actually poses
   * devices — resolving it at this point failed renders that never needed it, the surveyed
   * materialiser among them.
   *
   * <p>Only the FABRIC bridge is recorded, and the asymmetry with vmnet is not an oversight: a
   * bare-metal has ONE fabric bridge shared by every cluster it hosts, while the vmnet bridge is
   * per-cluster — and a manager's branch renders the pools of SEVERAL clusters, so there is no
   * single vmnet value at this scope. The blueprint derives that one per cluster instead.
   */
  private static Optional<String> fabricBridgeParent(ManifestsRunbookInput input) {
    return input.network().map(ManifestsRunbookInput.NetworkFacet::fabricBridgeParent);
  }

  /**
   * The delivery plan for a prepared worktree — the bot identity + signing key the rendered commit
   * carries, whether the operator armed the push, and (only then) the revealed GitHub token. Empty
   * when there is no worktree (a survey / CLI render). The commit is ALWAYS signed as the rke2lab
   * bot; the force-push is opt-in ({@code rke2lab:manifests:push}, default off) and needs the
   * sealed token.
   */
  private Optional<Delivery> deliveryPlan(
      ManifestsRunbookInput facet, Optional<LinkedWorktree> rendered) {
    if (rendered.isEmpty()) {
      return Optional.empty();
    }
    final String cluster = facet.identity().orElseThrow().clusterId();
    final boolean push = facet.facets().delivery().push();
    // No token when push is off (survey / preview). When armed, revealGithubToken resolves it — the
    // mint fails loud at its OWN frontier (GithubWriterTokenMintEdge throws rather than returning a
    // blank token), so a present token is always usable and the caller holds no empty-token case.
    final Optional<String> token = push ? revealGithubToken() : Optional.empty();
    // The bot identity + signing key are enclosure-resolved (§ pac-in-cluster-render-spec, auth):
    // OPERATOR reads the sops-smudged ndh key-store at hand; IN_CLUSTER the git tree is
    // sops-encrypted
    // at rest, so the signing key rides the mounted Secret (revealSigningKey, RKE2LAB_SIGNING_KEY)
    // and
    // the authority domain is the code constant. The commit is ALWAYS signed, in both enclosures.
    final GitIdentity bot;
    final String signingKey;
    if (enclosure.map(EnclosureGate::inCluster).orElse(false)) {
      bot = new GitBotIdentities(TAILNET_DOMAIN).forTool(RENDER_TOOL);
      signingKey =
          revealSigningKey()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "in-cluster render has no "
                              + SIGNING_KEY_ENV
                              + " — the signing-key Secret was not mounted into render-publish"));
    } else {
      final NdhKeystoreReader ks =
          keystore.orElseThrow(
              () ->
                  new IllegalStateException("no ndh key-store — cannot sign the rendered commit"));
      bot = new GitBotIdentities(ks.authorityDomain(TAILNET_AUTHORITY)).forTool(RENDER_TOOL);
      signingKey = ks.sshPrivate(SIGNING_KEY);
    }
    return Optional.of(new Delivery(renderCommitMessage(cluster), bot, signingKey, push, token));
  }

  /**
   * The render commit subject, stamped with the SOURCE provenance so the branch is traceable to the
   * rke2lab rev that synthesised it: {@code render <cluster> manifests @ <short-sha>[ (dirty)]}.
   * The sha rides the worktree domain's jgit provenance (no wrapper plumbing); an empty sha (a
   * first run with no {@code .git}) or an absent Worktree falls back to the bare subject.
   */
  private String renderCommitMessage(String cluster) {
    final String subject = "render " + cluster + " manifests";
    return sourceWorktree
        .map(Worktree::provenance)
        .filter(provenance -> !provenance.sha().isBlank())
        .map(
            provenance ->
                subject
                    + " @ "
                    + provenance.sha().substring(0, Math.min(12, provenance.sha().length()))
                    + (provenance.dirty() ? "-dirty" : ""))
        .orElse(subject);
  }

  /**
   * The commit-signing SSH private key for the IN_CLUSTER enclosure — the twin of {@link
   * #revealGithubToken()}. OPERATOR reads it from the ndh key-store in {@link #deliveryPlan}; this
   * reveals the in-cluster source: the {@code RKE2LAB_SIGNING_KEY} env a mounted Secret feeds (the
   * signing key rides the {@code NODE_BOOTSTRAP} → replicator lane, never the sops-encrypted git
   * tree the Tekton clone lands at rest). Empty when unmounted — the delivery then fails loud.
   */
  private Optional<String> revealSigningKey() {
    return Optional.ofNullable(System.getenv(SIGNING_KEY_ENV))
        .map(String::trim)
        .filter(key -> !key.isEmpty());
  }

  /**
   * The GitHub token for the fast-forward push, resolved by CONTAINER (the two lanes the {@code
   * host/host-runtime} {@code ExecutionEnclosure} FACT names) through {@link GithubAppTokens}, the
   * one revealer every consumer shares: OPERATOR mints a fresh {@code WRITER} token from the sealed
   * App at the moment of the push; IN_CLUSTER there is no cellar and no mint edge, and the token is
   * the one Pipelines-as-Code minted and the {@code render-publish} step exported. Empty when
   * neither is present (a survey / preview, or a render that isn't a push) — the push is then
   * skipped. The OPERATOR mint wins when both are reachable.
   */
  private Optional<String> revealGithubToken() {
    final GithubAppTokens tokens = tokens();
    return cellar == null ? tokens.pipeline() : tokens.writerOrPipeline(cellar, parcel);
  }

  /**
   * The token the rendered branch is FETCHED with, by the same two lanes: OPERATOR mints a fresh
   * {@code contents:read} token from the sealed App; IN_CLUSTER it is the pipeline's, which
   * Pipelines-as-Code minted from the same App. Empty when neither is present — the fetch then goes
   * anonymous, never through a credential the machine holds.
   */
  private Optional<String> revealFetchToken() {
    final GithubAppTokens tokens = tokens();
    return cellar == null ? tokens.pipeline() : tokens.readerOrPipeline(cellar, parcel);
  }

  /**
   * The token revealer, built from this scenario's injected mints: they are only known once the
   * stage creator has resolved them, so it is built at use rather than held.
   */
  private GithubAppTokens tokens() {
    return new GithubAppTokens(writerTokenMint, readerTokenMint, System.getenv());
  }

  /** The rendered-branch delivery plan carried from the scenario into the THEN. */
  private record Delivery(
      String message,
      GitIdentity identity,
      String signingKey,
      boolean push,
      Optional<String> token) {

    /**
     * The same plan for another cluster's branch — identity, signing key and token are per-RUN (one
     * bot, one keystore read, one minted token), only the commit subject names the cluster. So the
     * manager's per-target passes derive their plans from the managing one instead of re-minting a
     * token per target.
     */
    Delivery forBranchOf(String message) {
      return new Delivery(message, identity, signingKey, push, token);
    }

    /**
     * Seal {@code worktree} with this plan: write the worktree convention's {@code .gitignore} (its
     * intermediates stay local), stage the whole rendered tree, commit it SIGNED as the rke2lab
     * bot, and force-push only when the operator armed the push AND the token was revealed (the
     * gardening gate). Behaviour of the plan itself, so the managing branch's THEN and each
     * per-target pass deliver through ONE path — a change to the push discipline has one site.
     */
    void seal(LinkedWorktree worktree) {
      try {
        Files.writeString(worktree.path().resolve(".gitignore"), WORKTREE_IGNORE);
      } catch (IOException ex) {
        throw new UncheckedIOException(
            "cannot write the render's .gitignore in " + worktree.path(), ex);
      }
      worktree.stageAll();
      worktree.commit(message, identity, Optional.of(signingKey));
      if (push) {
        token.ifPresent(worktree::push);
      }
    }
  }

  /**
   * The sealed materials ONE render pass consumes, grouped so the managing pass and every
   * per-target pass take the identical set — revealed once per run, at the scenario, and handed
   * down. Grouped rather than passed as seven positional Optionals (the multi-parameter
   * discipline): the group is the unit of meaning, and adding a slice touches one record instead of
   * every pass signature.
   */
  private record Materials(
      Optional<AdminCredentials> operatorPki,
      Optional<GithubAppCredentials> githubApp,
      Optional<ReplicatorSourceSecretsMaterial> replicatorSources,
      Optional<ClusterIssuerCa> clusterIssuerCa,
      Optional<TlsAuthorityCaMaterial> tlsAuthorityCa,
      Optional<WorkloadClusterCas> workloadCas,
      Optional<ManagementClusterCa> managementCas,
      Optional<IncusIdentityMaterial> incusIdentity) {}

  /**
   * One synthesis pass — what makes a pass DIFFER from its siblings: whose cluster it renders,
   * which domain set that role publishes, where it materialises, and what it may emit for OTHER
   * clusters. A workload pass carries no targets and no bundles (a workload manages nothing); the
   * managing pass carries both.
   */
  private record Pass(
      ManifestDomainPolicy policy,
      BootstrapIdentity identity,
      Path root,
      Optional<ImageState> image,
      // The bridge every node's fabric NIC attaches to — the operator's rke2lab:network:
      // declaration,
      // carried because the render poses the devices and cannot derive this one (the vmnet bridge
      // it
      // derives from the cluster's role).
      Optional<String> fabricBridgeParent,
      // The declared fleet, from which the render DERIVES the children it owns. Empty on a
      // per-child
      // pass: a parent lays the child's branch down as a bootstrap, and the child's own render is
      // what adds ITS children (the alternative — the parent rendering its grandchildren — would
      // put
      // a CR-set on a plane whose cluster-pki never sealed that cluster's CAs).
      Optional<ClusterFleet> fleet,
      Optional<WorkloadBootstrapBundlesMaterial> bundles) {}

  /**
   * A workload target's own render pass: the target, the linked worktree of its {@code
   * manifests/<cluster>} branch, and the plan that seals it. Prepared by the scenario (which owns
   * the delivery seam) and played by the WHEN (which owns the synthesis).
   */
  private record TargetPass(ClusterCoordinate child, LinkedWorktree worktree, Delivery delivery) {}

  @Test
  void the_manifests_are_synthesized_from_the_activation_facet() {
    final ManifestsRunbookInput facet =
        Objects.requireNonNull(input, "the activation facet was not seeded before the body");
    // Prepare the rendered-branch worktree (a provisioning run) — the synthesis materialises INTO
    // it, and the THEN seals + delivers it. Empty for a bare survey / the standalone CLI: the
    // synthesis then falls back to a temp dir with no branch, and the delivery THEN is a no-op.
    final Optional<LinkedWorktree> rendered = prepareRenderWorktree(facet);
    // Rehydrate the in-cluster cellar asset (§ in-cluster-cellar-asset): a secret-blind in-cluster
    // publish sows no seal, so the IN_CLUSTER reveals below would find nothing and Flux would prune
    // the branch secrets — reading the prior render's asset back onto the overlay makes them
    // resolve
    // as if the operator's seal had run. A no-op on a first grow (no asset yet) / a survey; on a
    // re-grow the fresh seal wins (importSealed skips a coordinate the overlay already carries).
    rehydrateInClusterAsset(rendered);
    // Preserve the reflector's reflections/ documents across the wholesale render: prepare emptied
    // the tree, so without this the cluster→git reflector's committed PoolReflection files would be
    // staged as deletions and pushed away. The reflector owns those files' content; the render owns
    // their SURVIVAL (the "escape" carve-out — a reflector-owned subtree the render never generates
    // but must not delete). Tolerant of the dir being absent (a first render). See the reflector
    // design in docs/architecture/cluster-api/cluster-seeding-controller.adoc.
    rendered.ifPresent(worktree -> worktree.restoreFromHead(REFLECTIONS_DIR));
    // Resolve the effective facet per the sower's RenderMode: GROW/INIT keep the seeded facet
    // (the grow's Pulumi SSOT / the CLI args), UPDATE follows the branch HEAD, EDIT overlays the
    // sparse operator overrides on HEAD — so a steady-state render never silently resets the branch
    // to the operator default (the mesh-drop footgun) yet the grow stays authoritative. INIT/UPDATE
    // /EDIT also guard branch existence. Absent a worktree (a survey) the seeded facet stands.
    final ManifestsRunbookInput effective = resolveFacet(facet, rendered);
    // ONE delivery plan for the whole run: it carries the frontier's verdict (a token only when the
    // gardening gate is open), so no consumer re-derives "did we push" from the config intent.
    // Resolved BEFORE the passes, because each per-target pass delivers its own branch inside the
    // WHEN and re-subjects THIS plan to it — one keystore read and one token mint per run, not one
    // per branch. The OPERATOR token lives ~1 h, so covering the synthesis is well inside its life.
    final Optional<Delivery> delivery = deliveryPlan(effective, rendered);
    // The manager's PER-TARGET passes: each workload target's own branch, rendered with the WRKLD
    // unit set, sealed, and its carved bootstrap bundle handed back — the material the managing
    // pass
    // renders as that target's <cluster>-server-manifests Secret. A cluster being born has no pass
    // of its own, which is the whole reason this one exists (see the rendered-branches model,
    // § per-target-pass).
    final List<TargetPass> workloadPasses = prepareWorkloadPasses(effective, rendered, delivery);
    given().the_activation_facet(effective);
    final Optional<ClusterIssuerCa> issuerCa = revealClusterIssuerCa();
    final Materials materials =
        new Materials(
            revealOperatorPki(),
            revealGithubApp(),
            revealReplicatorSources(),
            issuerCa,
            // DERIVED, not revealed a second time: the fleet's trust anchor is the root our own
            // issuer chain ends at, so one reveal feeds both and the two cannot disagree. This is
            // also what makes it available IN_CLUSTER, where the ndh key-store — the obvious source
            // — is unreachable.
            issuerCa.map(m -> TlsAuthorityCaMaterial.rootOf(m.caCertChainPem())),
            revealWorkloadCas(),
            revealManagementCas(),
            revealIncusIdentity());
    when()
        .the_policy_is_derived_from_the_facet()
        .and()
        .the_workload_targets_are_rendered(workloadPasses, materials)
        .and()
        .the_manifests_are_synthesized(materials, rendered);
    // Extract the IN_CLUSTER-reaching sealed materials to the branch asset (§ in-cluster-cellar
    // -asset): the operator's grow captures its fresh seals, a publish re-captures the rehydrated
    // set (a fixpoint). Written plaintext into the tree; the git clean filter sops-encrypts it at
    // stageAll, so the passphrase-sealed payloads gain age protection at rest. Before delivery so
    // the THEN's stageAll picks it up. A no-op when nothing reaches in-cluster (a mgmt-only run).
    extractInClusterAsset(rendered);
    then()
        .every_enabled_domain_produced_its_units()
        .and()
        .the_manifests_file_is_written()
        .and()
        .the_rendered_branch_is_delivered(rendered, delivery);
    fileNodeBootstrap(rendered);
    fileNodeGithubToken(effective, rendered, delivery);
  }

  /**
   * Prepare one render pass per workload target — a linked worktree of its {@code
   * manifests/<cluster>} branch beside the managing render, plus the plan that seals it.
   *
   * <p>Empty unless the managing render itself has a worktree: a bare survey / the standalone CLI
   * renders the one cluster into a temp dir with no branch, and a target's pass exists only to
   * produce a branch and a bundle. Present ⟹ a {@link Delivery} was resolved (both hang off the
   * same worktree seam), so each target's plan is the managing one re-subjected to its own branch.
   *
   * <p>Each target renders at {@code <worktrees-root>/<cluster>} — beside the managing worktree,
   * each on its own branch {@code manifests/<cluster>}. The render's intermediates (the
   * consolidated {@code manifests.yaml}, the carved {@code .bootstrap/rke2lab-bootstrap.yaml}) live
   * in each tree's own ignored {@link NodeBootstrapArtifact#LOCAL_DIR}, so two passes never write
   * the same file and a target's carve can never be sealed as the managing node's bundle.
   *
   * <p>Not closed, for the same reason the managing worktree is not — and {@code prepare} is
   * idempotent, so a re-run starts clean while the last render stays inspectable on disk.
   */
  /**
   * The branches this render WRITES — the TRANSITIVE closure of the owner rule ({@link
   * ClusterFleet#renderedBy}), not one level of it.
   *
   * <p>★ The two questions are not the same. Writing a branch needs no reachability — it is a git
   * push — where DRIVING a cluster's machines does, and that is what {@code ownedBy} answers for
   * the CR-emitting units. Conflating them is what left a sub-plane's workload undeclarable: the
   * walk stopped one level short, so {@code nikopol-wrkld}'s branch was never produced, its
   * bootstrap bundle never carved, and its control-node pool waited for a Secret nobody would
   * write.
   *
   * <p>Note what that one level was NOT: a reachability limit. Seen from nikopol one level already
   * suffices — {@code ownedBy(nikopol-mgmt) = [nikopol-wrkld]}, and nikopol's own render carves
   * that bundle onto its own branch. What is missing is that nikopol never RENDERS, and the closure
   * is what lets the root do it in its place.
   *
   * <p>Empty when no fleet is declared (a bare survey / the standalone CLI) or when the render has
   * no identity: a pass that does not know WHICH cluster it renders for cannot know what that
   * cluster owns, and answering "all of them" there is precisely the viewpoint error the derivation
   * removes.
   */
  private static List<ClusterCoordinate> renderedChildren(final ManifestsRunbookInput effective) {
    final Optional<ClusterFleet> fleet = effective.facets().clusterFleet();
    if (fleet.isEmpty()) {
      return List.of();
    }
    return effective
        .identity()
        .map(identity -> fleet.orElseThrow().renderedBy(identity.clusterName()))
        .orElseGet(List::of);
  }

  private List<TargetPass> prepareWorkloadPasses(
      ManifestsRunbookInput effective,
      Optional<LinkedWorktree> rendered,
      Optional<Delivery> delivery) {
    // DEEPEST-FIRST. `renderedBy` is breadth-first, so parents precede children; reversing it puts
    // every child before the pass that ADOPTS it, which is what lets a bundle exist by the time its
    // adopter renders. On a tree that reversal IS a post-order, and the adopter relation is a tree.
    final List<ClusterCoordinate> children = new ArrayList<>(renderedChildren(effective));
    Collections.reverse(children);
    if (rendered.isEmpty() || delivery.isEmpty() || children.isEmpty()) {
      return List.of();
    }
    final Path worktreesRoot = rendered.orElseThrow().path().getParent();
    if (worktreesRoot == null) {
      return List.of();
    }
    final LinkedWorktrees branch = linkedWorktrees.orElseThrow();
    final Delivery plan = delivery.orElseThrow();
    final Optional<String> fetchToken = revealFetchToken();
    final List<TargetPass> passes = new ArrayList<>();
    for (final ClusterCoordinate child : children) {
      final String cluster = child.clusterName();
      final Path soil = worktreesRoot.resolve(cluster);
      passes.add(
          new TargetPass(
              child,
              branch.prepare(soil, BRANCH_PREFIX + cluster, fetchToken),
              plan.forBranchOf(renderCommitMessage(cluster))));
    }
    return List.copyOf(passes);
  }

  /**
   * Mint a FRESH {@code contents:read} github token (from the durable App creds this scenario
   * already reveals) and file it SEALED + TRANSIENT under {@link
   * NodeGithubTokenCoordinate#NODE_GITHUB_TOKEN} — the token the standalone GROW poses into the
   * node's cloud-init so its {@code rke2lab-rke2-config} oneshot can fetch the private {@code
   * manifests/<cluster>} branch it just pushed. TRANSIENT: read by the GROW this run, evicted at
   * the drain, never durable → never stale (the App creds are the durable source, the token is
   * not).
   *
   * <p>A token is needed ONLY for a real, PUSHED, OPERATOR delivery of a MANAGEMENT cluster — the
   * standalone node the GROW itself provisions and poses config onto via devlxd. So it files
   * nothing when there is no worktree (a survey / CLI materialise), no push (nothing on the remote
   * to fetch), the render is IN_CLUSTER (a re-render with no standalone grow behind it), or the
   * cluster is a WORKLOAD ({@link ClusterRole#WRKLD}) — a CAPI-provisioned node takes its config +
   * read token from the CAPRKE2 {@code RKE2Config} that seed-incluster bootstrap-injects, never
   * this cellar→devlxd pose (the standalone-GROW mechanism only). But once past those guards it is
   * a real grow: a pushed OPERATOR render already minted a WRITER token for the push, so the reader
   * edge MUST be present too — an empty mint is a wiring defect, and a silent skip would only
   * surface as a cryptic node-boot fetch failure. So: fail LOUD.
   */
  private void fileNodeGithubToken(
      ManifestsRunbookInput facet, Optional<LinkedWorktree> rendered, Optional<Delivery> delivery) {
    // The role is only ever asked one question below — "is this a WORKLOAD?" — so an ABSENT
    // identity
    // must stay absent rather than be flattened into a blank name. The previous `.orElse("")`
    // handed
    // that blank to `ClusterRole.of`, which answered MGMT through a catch-all: a meaningless value
    // fabricated to satisfy a parser, and the same shape that let the netplan projection read hosts
    // as MGMT clusters and lose half of them in silence. `of` is loud now, so say it here instead.
    final Optional<ClusterRole> role =
        facet
            .identity()
            .map(ManifestsRunbookInput.Identity::clusterName)
            .filter(name -> !name.isBlank())
            .map(ClusterRole::of);
    if (rendered.isEmpty()
        || cellar == null
        || parcel.isEmpty()
        // A push ACTUALLY happened, not merely armed in config. the_rendered_branch_is_delivered
        // pushes on `plan.push() && plan.token().isPresent()`, and the token is present only when
        // the gardening gate is open — so in a survey/preview the config intent stays true while
        // nothing was pushed. Reading the intent here is what made this demand a reader token the
        // same closed gate had withheld, and call the absence a wiring defect.
        || delivery.filter(plan -> plan.push() && plan.token().isPresent()).isEmpty()
        || role.filter(r -> r == ClusterRole.WRKLD).isPresent()
        || enclosure.map(EnclosureGate::inCluster).orElse(false)) {
      return;
    }
    final ScenarioCellar tx = cellar;
    final Parcel plot = parcel.orElseThrow();
    final String token =
        revealNodeGithubToken()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "an OPERATOR push render could not mint the node's contents:read github"
                            + " token — the reader-mint edge must be present alongside the writer"
                            + " edge, or the grown node's rke2-config fetch will fail"));
    tx.store(
        plot,
        NodeGithubTokenCoordinate.NODE_GITHUB_TOKEN,
        new NodeGithubToken(token),
        Sensitivity.SEALED,
        Persistence.TRANSIENT);
  }

  /** The node's fresh {@code contents:read} token, minted from the sealed App, or empty. */
  private Optional<String> revealNodeGithubToken() {
    return cellar == null ? Optional.empty() : tokens().reader(cellar, parcel);
  }

  /**
   * File the node-side bootstrap set the exploder carved out ({@code .bootstrap/rke2lab-bootstrap
   * .yaml}, a sibling of the rendered tree — never committed to the branch) into the transactional
   * cellar under {@link ServerManifestsCoordinate#SERVER_MANIFESTS}, SEALED (it carries the App
   * private key and the cluster age identity). The host GROW reveals it a few steps on and poses it
   * on the instance's {@code user.rke2lab.server-manifests} devlxd key — the same cross-realm seam
   * the cluster-pki cases ride. A no-op on a bare survey / the standalone CLI (no worktree, no
   * parcel) and when no unit marked anything node-bootstrap (the file is absent).
   */
  private void fileNodeBootstrap(Optional<LinkedWorktree> rendered) {
    if (rendered.isEmpty() || cellar == null || parcel.isEmpty()) {
      return;
    }
    final Path bootstrapFile = NodeBootstrapArtifact.MANIFESTS.in(rendered.orElseThrow().path());
    if (!Files.exists(bootstrapFile)) {
      return;
    }
    try {
      cellar.store(
          parcel.orElseThrow(),
          ServerManifestsCoordinate.SERVER_MANIFESTS,
          new ServerManifestsBundle(Files.readString(bootstrapFile)),
          Sensitivity.SEALED);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "cannot read the node-bootstrap manifests: " + bootstrapFile, ex);
    }
  }

  // The in-cluster cellar asset at the branch ROOT (§ in-cluster-cellar-asset): a hidden Secret
  // carrier holding the SEALED IN_CLUSTER-reaching envelopes as one stringData scalar. The
  // `.secret-`
  // prefix matches the exploder's `.gitattributes` (`**/.secret-*.yml filter=sops-yaml`), so the
  // git
  // clean filter sops-encrypts the stringData at commit; local-config + its root placement (outside
  // every Flux Kustomization path) keep it unapplied — pure operator/render carrier.
  private static final String IN_CLUSTER_ASSET_FILE = ".secret-in-cluster-cellar.yml";
  // The cluster→git reflector's subtree on the managing branch — a reflector-owned path the render
  // never generates but PRESERVES across its wholesale regeneration (the escape carve-out).
  private static final String REFLECTIONS_DIR = "reflections";
  private static final String ASSET_ENVELOPES_KEY = "envelopes";

  /**
   * Rehydrate the prior render's in-cluster asset onto the run's overlay so the IN_CLUSTER reveals
   * resolve on a secret-blind publish (§ in-cluster-cellar-asset). Reads the COMMITTED asset back
   * THROUGH the sops smudge filter ({@link LinkedWorktree#smudgeFromHead}, since jgit's {@code
   * readAtHead} would hand back the encrypted blob), unwraps its {@code stringData} scalar, and
   * hands the sealed envelopes to the cellar. A no-op on a survey / no cellar / no asset at HEAD.
   */
  private void rehydrateInClusterAsset(Optional<LinkedWorktree> rendered) {
    if (rendered.isEmpty() || cellar == null || parcel.isEmpty()) {
      return;
    }
    final ScenarioCellar tx = cellar;
    final Parcel plot = parcel.orElseThrow();
    rendered
        .orElseThrow()
        .smudgeFromHead(IN_CLUSTER_ASSET_FILE)
        .flatMap(this::assetEnvelopesJson)
        .ifPresent(json -> tx.importSealed(plot, json));
  }

  /**
   * Extract the IN_CLUSTER-reaching sealed materials to the branch asset — the grow captures its
   * fresh seals, a publish re-captures the rehydrated set (a fixpoint). Written PLAINTEXT into the
   * tree; the git clean filter sops-encrypts the {@code stringData} at {@code stageAll}. A no-op on
   * a survey / no cellar / nothing reaching in-cluster (a mgmt-only run leaves the branch
   * untouched).
   */
  private void extractInClusterAsset(Optional<LinkedWorktree> rendered) {
    if (rendered.isEmpty() || cellar == null || parcel.isEmpty()) {
      return;
    }
    final ScenarioCellar tx = cellar;
    tx.exportReaching(parcel.orElseThrow(), io.seedmatic.rke2lab.seed.broker.port.Reach.IN_CLUSTER)
        .ifPresent(json -> writeInClusterAsset(rendered.orElseThrow().path(), json));
  }

  /**
   * The {@code stringData.envelopes} scalar of a smudged asset carrier; empty if absent/unreadable.
   */
  private Optional<String> assetEnvelopesJson(String secretYaml) {
    try {
      final JsonNode envelopes =
          FACET_READER.readTree(secretYaml).path("stringData").path(ASSET_ENVELOPES_KEY);
      return envelopes.isMissingNode() || envelopes.isNull()
          ? Optional.empty()
          : Optional.of(envelopes.asText());
    } catch (IOException ex) {
      return Optional.empty();
    }
  }

  /**
   * Write the asset carrier (a local-config Secret) at the branch root, {@code envelopesJson}
   * plain.
   */
  private void writeInClusterAsset(Path root, String envelopesJson) {
    final ObjectNode secret = FACET_READER.createObjectNode();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    final ObjectNode metadata = secret.putObject("metadata");
    metadata.put("name", "in-cluster-cellar");
    metadata.putObject("annotations").put("config.kubernetes.io/local-config", "true");
    secret.put("type", "Opaque");
    secret.putObject("stringData").put(ASSET_ENVELOPES_KEY, envelopesJson);
    try {
      Files.writeString(
          root.resolve(IN_CLUSTER_ASSET_FILE), FACET_READER.writeValueAsString(secret));
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "cannot write the in-cluster cellar asset at the branch root", ex);
    }
  }

  /** Given: the activation facet and the synthesis collaborators. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ManifestsRunbookInput facet;

    @Hidden
    public Given the_activation_facet(ManifestsRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /**
   * When: the transposition of {@code HostSlotManifest.Builder.policy()}. Derives the {@link
   * ManifestDomainPolicy} + {@link FloxDebugPolicy} from the facet and synthesises. The policy
   * drives the synth-time domain filter (which layers synthesise). Mode-blind: the materialisation
   * target follows the SOIL amendment alone (a temp dir here), never a run gate.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ManifestsRunbookInput facet;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario through the Given as a step param.
    @OsgiService private Optional<ManifestSynthesisService> synthesis = Optional.empty();

    // The source worktree (see the scenario field): its jgit provenance stamps the recorded render
    // manifest with the rke2lab rev + dirty bit that synthesised this tree.
    @OsgiService(await = false)
    private Optional<Worktree> sourceWorktree = Optional.empty();

    @ProvidedScenarioState ManifestDomainPolicy domainPolicy;
    @ProvidedScenarioState ManifestSynthesisResult result;

    // The flake.lock reader the install-config flake render delegates its nixpkgs pin to.
    private final FlakeLock flakeLock = new FlakeLock();

    public When the_policy_is_derived_from_the_facet() {
      // The domain set is a FUNCTION of the cluster's ROLE (parsed from the clusterName), not an
      // operator toggle: base infra always on, Cluster API MGMT-only, mesh + cicd WRKLD-only. The
      // role is stable across a grow and any in-cluster re-render (it lives in the branch name), so
      // the policy replays deterministically without a recorded publish facet.
      final String clusterName =
          facet.identity().map(ManifestsRunbookInput.Identity::clusterName).orElse("");
      this.domainPolicy = ClusterRole.of(clusterName).domainPolicy(CATALOG);
      return self();
    }

    // The bundles the per-target passes carved, handed on to the managing pass so it renders each
    // target's <cluster>-server-manifests Secret. Empty until that step runs, and empty when it had
    // no target to render — a field rather than a step param because ONE step produces it and the
    // next consumes it, within the one stage instance.
    private Optional<WorkloadBootstrapBundlesMaterial> workloadBundles = Optional.empty();

    /**
     * The manager's PER-TARGET passes: for each workload target, render the {@link
     * ClusterRole#WRKLD} unit set into that target's own branch worktree, seal it, and harvest the
     * node-side bootstrap bundle the exploder carved out of it.
     *
     * <p>This is what breaks the outer chicken-and-egg: a cluster cannot render its own first
     * branch — it has no Tekton, no Flux, and until its CNI is configured (itself a bootstrap-set
     * resource) no pod runs there at all. So the manager renders it, with the same unit set, the
     * same materials, the same exploder and the same delivery discipline as any other render; only
     * the SUBJECT differs, which is exactly what {@link Pass} carries. A target renders no targets
     * and no bundles of its own: a workload manages nothing.
     *
     * <p>A no-op with no passes — a survey, the standalone CLI, or a manager with no targets.
     */
    public When the_workload_targets_are_rendered(
        @Hidden List<TargetPass> passes, @Hidden Materials materials) {
      // Carved bundles, keyed by the cluster they belong to, so each can be ROUTED to the pass of
      // whoever adopts it. The passes arrive DEEPEST-FIRST, so a child's bundle is already in here
      // by
      // the time the pass of its adopter runs.
      final Map<String, String> carvedByCluster = new LinkedHashMap<>();
      for (final TargetPass pass : passes) {
        final String cluster = pass.child().clusterName();
        final Path root = pass.worktree().path();
        synthesize(
            new Pass(
                // The CHILD's own role, not WRKLD. This was hardcoded, which was right only while
                // every child was a workload: measured 2026-09-27, nikopol-mgmt's branch came out
                // with no cluster-api at all — no flux/, no crds/, no operators/ for it — because
                // the WRKLD policy makes Cluster API MGMT-exclusive-and-therefore-absent. A
                // management cluster birthed by another one could then never self-adopt, which is
                // the whole second half of model B. The role now arrives TYPED on the coordinate,
                // parsed once and loudly where the fleet was decoded.
                pass.child().role().domainPolicy(CATALOG),
                BootstrapIdentity.builder()
                    .clusterName(cluster)
                    .nodeName(FIRST_CONTROL_NODE)
                    .build(),
                root,
                facet.image(),
                fabricBridgeParent(facet),
                // The SAME fleet as the managing pass — NOT empty. Every plane carries the same
                // federation view and derives its ROLE from its POSITION in it, so a child's branch
                // must show the whole fleet: its own intention (which needs the fleet to name its
                // adopter) and the children IT owns.
                //
                // ⚠️ Empty here made the child's OWN intention VANISH the moment the adopter became
                // required — measured 2026-09-30: manifests/nikopol-mgmt came out with no
                // ClusterIntention at all. It was a leftover of the earlier reading, where a parent
                // deliberately rendered no grandchild. Under a shared view that reading is wrong:
                // whoever renders, the result must be IDENTICAL, which is what makes the two copies
                // of a sub-plane's intention agree by construction instead of by coincidence.
                facet.facets().clusterFleet(),
                // The bundles THIS child adopts — carved by the passes below it, which ran first
                // because the closure is walked deepest-first. Empty for a workload (it adopts
                // nobody) and for a sub-plane whose own child carved nothing.
                //
                // ⚠️ This was hardcoded empty, which was right only while a child was always a
                // leaf.
                // A child that is itself a MANAGEMENT plane adopts its host's workload, and its
                // branch is the ONLY place that workload's `<cluster>-server-manifests` belongs —
                // the Secret its control-node pool waits for.
                bundlesAdoptedBy(cluster, carvedByCluster)),
            materials);
        // The branch records the facet that produced it, INCLUDING the full fleet, so a later
        // steady-state render of THIS branch derives the children IT owns.
        //
        // ⚠️ This used to record an empty target list, reasoned as "MINUS the targets, since it
        // manages none". That held only while every child was a workload. Since a child can be a
        // management plane (model B), the empty list is what made manifests/nikopol-mgmt
        // structurally CHILDLESS — measured 2026-09-30: its cluster-api-workload package contained
        // nothing but the group ConfigMap, so nikopol-wrkld could never be declared. The fleet is
        // host-agnostic and the owner rule does the narrowing, so propagating it verbatim is both
        // simpler and correct.
        // Through the BUILDER, like the merge in withDebug — the two sites that assemble a Facets
        // must not stand in two different forms, or the positional trap that swapped
        // rootIncusHost/workloadControlPlane there survives here for the next reader.
        final ManifestsRunbookInput.Facets.Builder childFacets =
            ManifestsRunbookInput.Facets.builder()
                .debug(facet.facets().debug())
                .delivery(facet.facets().delivery())
                .incusTargets(facet.facets().incusTargets());
        facet.facets().rootIncusHost().ifPresent(childFacets::rootIncusHost);
        facet.facets().workloadControlPlane().ifPresent(childFacets::workloadControlPlane);
        // The network rides along: a CHILD branch renders in-cluster too, and its own render has no
        // host to declare the fabric parent either. Dropping it here would leave exactly the hole
        // that broke the manager's render, one branch further down.
        facet.network().ifPresent(childFacets::network);
        recordRenderFacet(root, childFacets.build(), facet.image());
        recordSopsPolicy(root);
        recordInstallConfigFlake(root);
        pass.delivery().seal(pass.worktree());
        carvedBundle(root).ifPresent(yaml -> carvedByCluster.put(cluster, yaml));
      }
      // The managing pass renders the bundles of the children the RENDERER adopts — NOT every
      // bundle
      // carved. Handing it all of them is what put a grandchild's `<cluster>-server-manifests` (a
      // CA
      // private key) on the root's branch instead of its adopter's, and left the adopter's branch
      // without the one Secret its pool waits for.
      this.workloadBundles =
          facet
              .identity()
              .map(ManifestsRunbookInput.Identity::clusterName)
              .flatMap(renderer -> bundlesAdoptedBy(renderer, carvedByCluster));
      return self();
    }

    /**
     * The carved bundles whose cluster is ADOPTED by {@code adopter} — the routing that replaced
     * "hand every bundle to the managing pass".
     *
     * <p>★ This is "material follows the adopter" stated correctly. The rule was implemented as
     * "only the adopter RENDERS it", which is the easy way to guarantee it and the reason a
     * grandchild could not be declared at all. What the rule actually demands is that the
     * material's PLACEMENT follow the adopter — whoever renders. Same guarantee, without pruning
     * the render.
     *
     * <p>Empty rather than an empty material: absent and present-but-empty are different facts
     * downstream, and only one of them means "this pass adopts nobody".
     */
    private Optional<WorkloadBootstrapBundlesMaterial> bundlesAdoptedBy(
        final String adopter, final Map<String, String> carvedByCluster) {
      final Optional<ClusterFleet> fleet = facet.facets().clusterFleet();
      if (fleet.isEmpty() || carvedByCluster.isEmpty()) {
        return Optional.empty();
      }
      final List<WorkloadBootstrapBundlesMaterial.Entry> mine =
          carvedByCluster.entrySet().stream()
              .filter(entry -> fleet.orElseThrow().adopterOf(entry.getKey()).equals(adopter))
              .map(
                  entry ->
                      new WorkloadBootstrapBundlesMaterial.Entry(entry.getKey(), entry.getValue()))
              .toList();
      return mine.isEmpty()
          ? Optional.empty()
          : Optional.of(new WorkloadBootstrapBundlesMaterial(mine));
    }

    /**
     * The bootstrap bundle the exploder carved out of a rendered pass, or empty when that pass
     * marked nothing {@code NODE_BOOTSTRAP} (a secret-blind render, whose Flux/CNI secrets have no
     * material). Empty is honest and propagates: no bundle ⟹ no Secret ⟹ {@code seed-incluster}'s
     * material gate keeps the pool waiting instead of provisioning a node whose CNI cannot come up.
     */
    private Optional<String> carvedBundle(Path root) {
      final Path bundle = NodeBootstrapArtifact.MANIFESTS.in(root);
      if (!Files.exists(bundle)) {
        return Optional.empty();
      }
      try {
        return Optional.of(Files.readString(bundle));
      } catch (IOException ex) {
        throw new UncheckedIOException(
            "cannot read the carved node-bootstrap bundle: " + bundle, ex);
      }
    }

    public When the_manifests_are_synthesized(
        @Hidden Materials materials, @Hidden Optional<LinkedWorktree> rendered) {
      // Materialise INTO the rendered-branch worktree when one was prepared (a provisioning run —
      // the GROW mounts it and the THEN seals + delivers it), else a temp dir (a survey / the
      // standalone CLI). Mode-blind: whether the run is a survey is the frontier's business.
      final Path root = rendered.map(LinkedWorktree::path).orElseGet(this::freshTempDir);
      // The managing pass runs LAST, after the per-target ones, because it consumes what they
      // carve:
      // its OWNED CHILDREN are the DIFFERENT clusters whose CAPI CR set lands on
      // manifests/<host>-mgmt (model B — the CRs live where CAPI runs), and workloadBundles are
      // those
      // same clusters' bootstrap bundles, rendered here as their <cluster>-server-manifests
      // Secrets.
      this.result =
          synthesize(
              new Pass(
                  domainPolicy,
                  identity(),
                  root,
                  facet.image(),
                  fabricBridgeParent(facet),
                  facet.facets().clusterFleet(),
                  workloadBundles),
              materials);
      // Record the facet that produced this tree at the branch ROOT, so the branch is
      // self-describing and a later in-cluster render reads it back (the facet follows the grow —
      // see docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc § render-config). Only
      // for
      // a real delivery worktree (root = the branch root the THEN stages + pushes); a bare survey /
      // temp-dir render records nothing. This is written by the FLOW, not a ManifestsUnit: only
      // here
      // is the raw facet (incl. delivery) in hand, and the exploder has no root path.
      rendered.ifPresent(
          linkedWorktree -> {
            recordRenderFacet(linkedWorktree.path(), facet.facets(), facet.image());
            recordSopsPolicy(linkedWorktree.path());
            recordInstallConfigFlake(linkedWorktree.path());
          });
      return self();
    }

    /**
     * The cross-frontier identity view reaped at this scion via the WORKTREE amendment — the
     * synthesis root threads one NodeEnvContext derived from it to every unit. Absent (a bare
     * survey / no worktree amended) ⟹ unknown, and the synthesis renders a clearly-blank cluster.
     */
    private BootstrapIdentity identity() {
      return facet
          .identity()
          .map(
              w ->
                  BootstrapIdentity.builder()
                      .clusterName(w.clusterName())
                      .nodeName(w.nodeName())
                      .build())
          .orElseGet(BootstrapIdentity::unknown);
    }

    /**
     * Play ONE synthesis pass. The {@link Pass} carries what differs between passes (subject
     * cluster, its role's domain set, where it materialises, what it emits for OTHER clusters);
     * {@code materials} are the run's, revealed once and identical for every pass — the units
     * decide what each role actually renders from them.
     *
     * <p>{@code manifests.yaml} is the INTERMEDIATE aggregate, never part of the committed tree: it
     * sits in the root's ignored {@link NodeBootstrapArtifact#LOCAL_DIR}, beside the carved
     * bootstrap set.
     */
    private ManifestSynthesisResult synthesize(Pass pass, Materials materials) {
      final ManifestsRunbookInput.DebugFacet debug = facet.facets().debug();
      final FloxDebugPolicy floxDebug =
          new FloxDebugPolicy(
              debug.mesh().enabled(),
              debug.networking().enabled(),
              debug.nriPlugins().flox().enabled());
      final Path manifestFile =
          pass.root().resolve(NodeBootstrapArtifact.LOCAL_DIR).resolve("manifests.yaml");
      final ManifestSynthesisRequest request =
          ManifestSynthesisRequest.builder(pass.root(), manifestFile)
              .manifestDomainPolicy(Optional.of(pass.policy()))
              .floxDebugPolicy(floxDebug)
              .bootstrapIdentity(pass.identity())
              // The built node-base image's identity, forwarded by the incus scion as the
              // IMAGE_STATE
              // amendment (empty on a survey / a render with no image built): the image-state
              // ConfigMap and the workload CR units pin the image fingerprint + RKE2 version from
              // it.
              .imageState(pass.image())
              .fabricBridgeParent(pass.fabricBridgeParent())
              .clusterFleet(pass.fleet())
              .workloadBootstrapBundles(pass.bundles())
              // The materials revealed from the cellar, each empty on a bare survey / a
              // secret-blind
              // in-cluster render — and then the unit that needs one renders nothing rather than an
              // empty placeholder Flux would prune.
              .operatorPki(materials.operatorPki())
              .githubApp(materials.githubApp())
              .replicatorSources(materials.replicatorSources())
              .clusterIssuerCa(materials.clusterIssuerCa())
              .tlsAuthorityCa(materials.tlsAuthorityCa())
              .workloadCas(materials.workloadCas())
              .managementCas(materials.managementCas())
              .incusIdentity(materials.incusIdentity())
              .build();
      try {
        return synthesis.orElseThrow().synthesize(request);
      } catch (IOException ex) {
        throw new UncheckedIOException("manifests synthesis failed", ex);
      }
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-manifests-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the synthesis outdir", ex);
      }
    }

    // The self-describing render manifest at the branch root: a k8s ConfigMap carrying the
    // effective
    // facet, annotated config.kubernetes.io/local-config so nothing applies it (it also sits
    // outside
    // every Flux Kustomization path). The facet is serialised with the same field-named records the
    // amend reflector binds, so read → decode → re-serialise is a fixpoint (no empty-commit churn).
    private static final String RENDER_FACET_FILE = "manifest.yaml";

    // A human-readable YAML doc, NOT a ConfigMap: it sits at the branch root, outside every Flux
    // Kustomization path, so nothing ever applies it — and native YAML reads far better than a JSON
    // blob stuffed in a scalar. Deterministic serialisation (record component order, no doc-start
    // marker, minimal quotes) keeps the empty-commit guard's fixpoint: same source + facet →
    // identical bytes → no commit.
    private static final YAMLMapper YAML_MAPPER =
        YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            // Jdk8Module so the recorded image (Optional<ImageState>) serialises as its value /
            // null,
            // registered EXPLICITLY like SeedCodec (never findAndRegisterModules — OSGi
            // classloading).
            .addModule(new Jdk8Module())
            .build();

    private void recordRenderFacet(
        Path root, ManifestsRunbookInput.Facets facets, Optional<ImageState> image) {
      // source: the rke2lab rev that synthesised this tree (worktree jgit provenance — sha +
      // dirty); facet: the effective policy the read side decodes verbatim; image: the node-base
      // ImageState the grow recorded so a steady-state UPDATE render replays it (the incus scion
      // runs only at the grow). A local record so all land in declaration order. Read → decode →
      // re-serialise is a fixpoint (the UPDATE render re-records the replayed image identically),
      // so
      // no empty-commit churn.
      record RenderContext(
          Provenance source, ManifestsRunbookInput.Facets facet, Optional<ImageState> image) {}
      try {
        final Provenance source =
            sourceWorktree.map(Worktree::provenance).orElse(new Provenance("", false));
        Files.writeString(
            root.resolve(RENDER_FACET_FILE),
            YAML_MAPPER.writeValueAsString(new RenderContext(source, facets, image)));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot record the render facet at the branch root", ex);
      }
    }

    /**
     * Copy the source repo's {@code .sops.yaml} policy verbatim to the rendered branch ROOT — the
     * declarative half of the two static sops assets (the exploder writes the other, {@code
     * .gitattributes}, marking {@code *-secret-*.yml filter=sops-yaml}). This policy names the
     * fields to encrypt ({@code encrypted_regex: ^(data|stringData)$}) and the age recipients —
     * crucially the cluster key, so Flux decrypts the committed Secrets in-cluster. Verbatim from
     * source, so the recipients stay SSOT (a key rotation propagates on the next render). The
     * git-sops clean filter (run when the THEN stages) reads it from the worktree root (its CWD). A
     * no-op on a bare survey or a source with no readable {@code .sops.yaml}.
     */
    private void recordSopsPolicy(Path root) {
      sourceWorktree
          .flatMap(worktree -> worktree.readAtHead(".sops.yaml"))
          .ifPresent(
              policy -> {
                try {
                  Files.writeString(root.resolve(".sops.yaml"), policy);
                } catch (IOException ex) {
                  throw new UncheckedIOException(
                      "cannot record the sops policy at the branch root", ex);
                }
              });
    }

    // The RKE2 config installer, written at the branch root so the branch is a self-installing
    // flake: the SELF/root control-plane node (grown standalone by seed-outcluster) runs `nix run
    // <this-branch>#install-rke2-config` at boot; the app globs THIS branch tree for the
    // RKE2_CONFIG-annotated ConfigMaps (RuntimeRke2ConfigManifestsUnit renders them as normal
    // Flux-applied ConfigMaps) and extracts each `.data` into /etc/rancher/rke2/config.yaml.d.
    // Because a management branch now carries the config of EVERY cluster it manages (one namespace
    // rke2lab-<cluster> per cluster), the app filters to THIS node's cluster — derived from its
    // hostname <cluster>-<node> — so a mgmt node never installs a co-located workload's config.
    // (Managed workload nodes take their config from CAPRKE2 via seed-incluster, not this app.)
    // The install LOGIC is cluster-invariant, so flake.nix is a STATIC asset (like .sops.yaml) —
    // the per-cluster variance lives entirely in the ConfigMap data the app reads from ${self}. The
    // one render-time bind is the nixpkgs pin: taken from the SOURCE flake.lock so the installer's
    // yq-go is the node-base's own nixpkgs (a store cache-hit at boot, no cold fetch).
    private static final String NIXPKGS_REV_TOKEN = "@NIXPKGS_REV@";

    private void recordInstallConfigFlake(Path root) {
      final String rev =
          sourceWorktree
              .flatMap(worktree -> worktree.readAtHead("flake.lock"))
              .flatMap(flakeLock::nixpkgsRev)
              .filter(sha -> !sha.isBlank())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "cannot render install-rke2-config flake: the source flake.lock has no"
                              + " nixpkgs rev to pin the installer against"));
      try {
        Files.writeString(
            root.resolve("flake.nix"), INSTALL_CONFIG_FLAKE.replace(NIXPKGS_REV_TOKEN, rev));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot record the install-rke2-config flake", ex);
      }
    }

    private static final String INSTALL_CONFIG_FLAKE =
        """
        {
          description = "rke2lab RKE2 config installer — extracts the RKE2_CONFIG ConfigMaps of \
        THIS node's cluster (namespace rke2lab-<cluster>, derived from the hostname) from this \
        management branch into /etc/rancher/rke2/config.yaml.d. Run at boot by the self/root \
        control-plane node (seed-outcluster): nix run <this-branch>#install-rke2-config.";

          # Pinned to the node-base's own nixpkgs rev (injected at render from the source
          # flake.lock) so the installer's yq-go is a store cache-hit on the node — no cold fetch.
          inputs.nixpkgs.url = "github:NixOS/nixpkgs/@NIXPKGS_REV@";

          outputs = { self, nixpkgs }:
            let
              systems = [ "aarch64-linux" "x86_64-linux" ];
              forEachSystem = f:
                nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
            in {
              apps = forEachSystem (pkgs:
                let
                  installer = pkgs.writeShellApplication {
                    name = "install-rke2-config";
                    runtimeInputs = [ pkgs.yq-go pkgs.coreutils pkgs.findutils ];
                    text = ''
                      dest=/etc/rancher/rke2/config.yaml.d
                      install -d -m 0755 "$dest"
                      # This node's cluster, from its hostname (<cluster>-<node>): a management branch
                      # carries the config of EVERY cluster it manages (one namespace rke2lab-<cluster>
                      # each), so install ONLY this node's fragments — never a co-located cluster's.
                      cluster="$(cat /proc/sys/kernel/hostname)"
                      cluster="''${cluster%-*}"
                      # Reinstall from the branch: wipe the fragments this installer owns first. The
                      # per-node oneshots (node-labels, provider-id, node-ip) write drop-ins AFTER this.
                      find "$dest" -maxdepth 1 -type f \\( -name '*.yaml' -o -name '*.yml' \\) -delete
                      count=0
                      while IFS= read -r -d "" manifest; do
                        marked="$(yq eval -r \
                          '.metadata.annotations["io.seedmatic.rke2lab/rke2-config"] // "false"' \
                          "$manifest")"
                        [ "$marked" = "true" ] || continue
                        # Only THIS node's cluster — the fragment's namespace (rke2lab-<cluster>)
                        # encodes it; the mandatory filter that keeps co-located clusters apart.
                        ns="$(yq eval -r '.metadata.namespace // ""' "$manifest")"
                        [ "$ns" = "rke2lab-$cluster" ] || continue
                        name="$(yq eval -r '.metadata.name' "$manifest")"
                        # Extract the config payload (ConfigMap .data), parsing each value from its
                        # embedded YAML. The fragments are non-secret ConfigMaps committed plaintext
                        # (the sops gitattributes binds only *-secret-* files), so no decryption.
                        yq eval -o=yaml \
                          '(.data // {}) | with_entries(.value |= from_yaml)' \
                          "$manifest" > "$dest/$name"
                        count=$((count + 1))
                      done < <(find "${self}" -type f \\( -name '*.yaml' -o -name '*.yml' \\) -print0)
                      echo "[install-rke2-config] installed $count fragment(s) for cluster $cluster into $dest"
                    '';
                  };
                  app = { type = "app"; program = "${installer}/bin/install-rke2-config"; };
                in {
                  install-rke2-config = app;
                  default = app;
                });
            };
        }
        """;
  }

  /**
   * Then: the two ends landed. Every domain the role enables produced units (the synth-time
   * filter); the materialised tree is complete (manifest file exists, hit count &gt; 0).
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState ManifestDomainPolicy domainPolicy;
    @ExpectedScenarioState ManifestSynthesisResult result;

    public Then every_enabled_domain_produced_its_units() {
      final int enabled = domainPolicy.enabledDomainIds().size();
      if (result.domainCount() < enabled) {
        throw new ManifestSynthesisError(
            "expected at least " + enabled + " synthesised domains, got " + result.domainCount(),
            ManifestSynthesisError.Gap.DOMAIN_COUNT_SHORT,
            result);
      }
      return self();
    }

    public Then the_manifests_file_is_written() {
      if (!Files.exists(result.manifestFile())) {
        throw new ManifestSynthesisError(
            "manifest file was not written: " + result.manifestFile(),
            ManifestSynthesisError.Gap.MANIFEST_FILE_MISSING,
            result);
      }
      if (result.manifestUnitHitCount() <= 0) {
        throw new ManifestSynthesisError(
            "no manifest units were processed",
            ManifestSynthesisError.Gap.NO_UNITS_PROCESSED,
            result);
      }
      return self();
    }

    /**
     * Seal + deliver the rendered branch: stage the whole rendered tree, commit it SIGNED as the
     * rke2lab bot, and — only when the operator armed {@code rke2lab:manifests:push} and the sealed
     * token was revealed — force-push {@code manifests/<cluster>} to origin. The worktree is NOT
     * closed: it persists at the SOIL path for the GROW to mount. A no-op for a survey / CLI render
     * (no worktree prepared) — the tree was materialised, nothing is delivered.
     */
    public Then the_rendered_branch_is_delivered(
        @Hidden Optional<LinkedWorktree> rendered, @Hidden Optional<Delivery> delivery) {
      if (rendered.isEmpty() || delivery.isEmpty()) {
        return self();
      }
      delivery.orElseThrow().seal(rendered.orElseThrow());
      return self();
    }
  }
}
