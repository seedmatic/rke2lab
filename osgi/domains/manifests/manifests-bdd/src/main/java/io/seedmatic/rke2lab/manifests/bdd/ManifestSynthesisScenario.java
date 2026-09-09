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
import io.seedmatic.rke2lab.auth.contract.GithubWriterTokenMint;
import io.seedmatic.rke2lab.manifests.bdd.versions.GitBotIdentities;
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
import io.seedmatic.rke2lab.manifests.contract.profiles.ClusterIssuerCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.OperatorPkiMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsBundle;
import io.seedmatic.rke2lab.manifests.ingress.ServerManifestsCoordinate;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.RenderedBranch;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  // current plot — the seam through which the sealed admin-credentials case the seal scion filed is
  // revealed (decoded into OperatorPkiMaterial via a neutral wire coordinate), in-container, never
  // crossing the host membrane.
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
  private Optional<RenderedBranch> renderedBranch = Optional.empty();

  // The SOURCE worktree — the checkout the process runs in (the Tekton FETCH_HEAD clone in-cluster,
  // the grow's worktree otherwise). Its jgit provenance (HEAD sha + dirty) stamps the render's
  // commit subject and the recorded manifest, so a rendered branch is traceable to the exact
  // rke2lab
  // rev that synthesised it — no wrapper plumbing, jgit is already in the boat. Same await=false
  // shape as renderedBranch: present together (RenderedBranch is built @Reference Worktree), absent
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
  // here at the moment of the push, from the durable App credentials revealed at GhAppCase — the
  // ephemeral (~1 h) token is never sealed, so it can't go stale between a mint and a much later
  // reveal. Absent under a survey/preview frontier → the push is skipped.
  @OsgiService(await = false)
  private Optional<GithubWriterTokenMint> writerTokenMint = Optional.empty();

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

  // Reveal the operator's admin PKI straight into the manifests-side OperatorPkiMaterial: its three
  // PEM fields mirror the cluster-pki AdminCredentials record exactly, so the codec's structural
  // decode reads the sealed case 1:1. Addressed by the NEUTRAL wire coordinate (see
  // ClusterPkiCase),
  // so no cluster-pki type is ever touched and manifests-bdd carries no cluster-pki-contract
  // dependency — the standalone manifests-cli assembly never drags that domain's dual-realm flat
  // copy. Empty when no cellar/plot (a bare survey) or the seal has not filed yet.
  private Optional<OperatorPkiMaterial> revealOperatorPki() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(
        parcel.orElseThrow(), ClusterPkiCase.ADMIN_CREDENTIALS, OperatorPkiMaterial.class);
  }

  // Reveal the cluster-issuer CA (mirror of cluster-pki ClusterIssuerCa) via the neutral wire
  // coordinate — same treatment as revealOperatorPki(). Empty on a bare survey / before the seal
  // filed / a secret-blind in-cluster render (EphemeralCellar) → the delivering unit renders no
  // Secret onto the branch (the material rides the durable NODE_BOOTSTRAP lane).
  private Optional<ClusterIssuerCaMaterial> revealClusterIssuerCa() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(
        parcel.orElseThrow(), ClusterPkiCase.CLUSTER_ISSUER_CA, ClusterIssuerCaMaterial.class);
  }

  // Reveal the workload clusters' BYO-CA sets (mirror of cluster-pki WorkloadClusterCas) via the
  // neutral wire coordinate — same treatment as revealClusterIssuerCa(). Empty on a bare survey /
  // before the seal filed / a secret-blind in-cluster render → ClusterApiWorkloadManifestsUnit
  // renders no <cluster>-{ca,cca,etcd,peer-etcd} Secret (they ride the durable NODE_BOOTSTRAP
  // lane).
  private Optional<WorkloadClusterCasMaterial> revealWorkloadCas() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(
        parcel.orElseThrow(),
        ClusterPkiCase.WORKLOAD_CLUSTER_CAS,
        WorkloadClusterCasMaterial.class);
  }

  // Reveal the CAPN provider incus identity (assembled + sealed by the incus-identity seal scion)
  // via the shared IncusIdentityCase coordinate. Empty on a bare survey / before the seal filed / a
  // secret-blind in-cluster render → ClusterApiWorkloadManifestsUnit renders no
  // <host>-incus-identity
  // Secret (it rides the durable NODE_BOOTSTRAP lane).
  private Optional<IncusIdentityMaterial> revealIncusIdentity() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(
        parcel.orElseThrow(), IncusIdentityCase.INCUS_IDENTITY, IncusIdentityMaterial.class);
  }

  /**
   * The one org-owned App's credentials the ghapp registration sealed, revealed from the cellar so
   * the {@code githubapp} Secret unit renders them for Flux's native App auth. Empty on a bare
   * survey / before the registration filed — the unit then renders nothing. Addressed by the
   * NEUTRAL {@code github-app} wire coordinate ({@link GhAppCase}) into the manifests-side {@link
   * GithubAppMaterial} mirror, so no {@code ghapp-contract} flat copy is dragged into the
   * standalone {@code manifests-cli} assembly — the exact treatment {@link #revealOperatorPki()}
   * gives cluster-pki.
   */
  private Optional<GithubAppMaterial> revealGithubApp() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(parcel.orElseThrow(), GhAppCase.GITHUB_APP, GithubAppMaterial.class);
  }

  /**
   * The mittwald-replicator SOURCE secrets the {@code replicator-secrets} seal rehydrated from
   * {@code .secrets} and filed SEALED, revealed from the cellar in-container so {@code
   * ReplicatorManifestsUnit} renders them onto the node-bootstrap lane. Empty on a bare survey /
   * before the seal filed (an empty material seals nothing) → the unit renders no source secrets.
   */
  private Optional<ReplicatorSourceSecretsMaterial> revealReplicatorSources() {
    if (cellar == null || parcel.isEmpty()) {
      return Optional.empty();
    }
    return cellar.fetch(
        parcel.orElseThrow(),
        ReplicatorSecretsCase.REPLICATOR_SECRETS,
        ReplicatorSourceSecretsMaterial.class);
  }

  private static final String TAILNET_AUTHORITY = "mammoth-skate";

  // The tailnet authority DOMAIN (the key-store's authorities.mammoth-skate.domain). IN_CLUSTER the
  // sops key-store is unreadable, so the bot identity takes this constant — the same deployment
  // coupling TAILNET_AUTHORITY already carries.
  private static final String TAILNET_DOMAIN = "mammoth-skate.ts.net";
  private static final String SIGNING_KEY = "github-signing";

  // The env a mounted Secret feeds the IN_CLUSTER commit-signing key through (the NODE_BOOTSTRAP →
  // replicator lane), the twin of the PaC-provided RKE2LAB_PUSH_TOKEN.
  private static final String SIGNING_KEY_ENV = "RKE2LAB_SIGNING_KEY";
  private static final String RENDER_TOOL = "manifests-render";
  private static final String BRANCH_PREFIX = "manifests/";

  /**
   * Prepare the rendered-branch worktree for THIS run's cluster — an orphan linked worktree at the
   * SOIL path on branch {@code manifests/<cluster>}, into which the synthesis materialises the
   * rendered tree (the GROW then mounts this worktree). Present only when the delivery seam is
   * reachable (a provisioning run) AND the host amended a SOIL plot AND the cluster identity is
   * known; a bare survey / the standalone CLI leaves it empty and the synthesis falls back to a
   * temp dir, with no branch and no commit.
   */
  private Optional<LinkedWorktree> prepareRenderWorktree(ManifestsRunbookInput facet) {
    if (renderedBranch.isEmpty()
        || facet.materializationRoot().isEmpty()
        || facet.identity().isEmpty()) {
      return Optional.empty();
    }
    final String cluster = facet.identity().orElseThrow().clusterId();
    final Path worktreePath =
        Path.of(facet.materializationRoot().orElseThrow()).toAbsolutePath().normalize();
    return Optional.of(renderedBranch.orElseThrow().prepare(worktreePath, BRANCH_PREFIX + cluster));
  }

  // Reads the branch HEAD's recorded facet — a plain YAMLMapper, native record binding (jackson
  // reads the record component names). Symmetric with the Then's write of the same {source, facet}.
  // Tolerant of unknown keys: a branch recorded before the publish facet was removed still carries
  // a `publish:` sub-map under `facet:`; the domain set is now role-derived, so that key is stale
  // and ignored rather than failing the replay decode.
  private static final YAMLMapper FACET_READER =
      (YAMLMapper)
          new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
    final Optional<ManifestsRunbookInput.Facets> head = headManifest.flatMap(this::recordedFacets);
    // The node-base ImageState the last grow recorded at HEAD: replayed into a steady-state UPDATE
    // /EDIT render because the incus scion (the live IMAGE_STATE amendment) runs ONLY at the grow —
    // without this the in-cluster render is ImageState-blind and empties the image-pinned CR set.
    final Optional<ImageState> recordedImage = headManifest.flatMap(this::recordedImage);
    switch (verb) {
      case INIT -> {
        if (head.isPresent()) {
          throw new IllegalStateException(
              "init: manifests/<cluster> already has a recorded facet — use update or edit");
        }
      }
      case UPDATE, EDIT -> {
        if (head.isEmpty()) {
          throw new IllegalStateException(
              verb.name().toLowerCase(java.util.Locale.ROOT)
                  + ": manifests/<cluster> has no recorded facet yet — use init");
        }
      }
      case GROW -> {
        // No guard: the grow's facet is the SSOT, applied whether the branch is new or not.
      }
    }
    return switch (verb) {
      case GROW, INIT -> seeded;
      case UPDATE -> withDebug(seeded, head.orElseThrow(), recordedImage);
      case EDIT -> withDebug(seeded, overlay(head.orElseThrow(), mode.overrides()), recordedImage);
    };
  }

  /**
   * A copy of {@code seeded} taking debug + workloadTargets from {@code facets} (HEAD), keeping
   * only the seeded {@code delivery}. Rationale: debug/workloadTargets are GROW-recorded
   * coordinates (the CLI's facet never sets workloadTargets — it comes from the grow's Pulumi
   * config), so HEAD wins; only {@code delivery} is verb-carried (the CLI's push intent). An
   * earlier version took workloadTargets from {@code seeded} and a steady-state render — whose
   * seeded facet has none — stripped them off the recorded manifest, emptying the workload CR set.
   * The domain set is no longer replayed here: it is a function of the cluster ROLE (see {@link
   * ClusterRole}), derived fresh from the identity on every render.
   */
  private ManifestsRunbookInput withDebug(
      ManifestsRunbookInput seeded,
      ManifestsRunbookInput.Facets facets,
      Optional<ImageState> recordedImage) {
    return new ManifestsRunbookInput(
        new ManifestsRunbookInput.Facets(
            facets.debug(), seeded.facets().delivery(), facets.workloadTargets()),
        seeded.materializationRoot(),
        seeded.identity(),
        seeded.renderMode(),
        // A live seeded image (a grow) wins; else replay the ImageState the grow recorded at HEAD,
        // so a steady-state render pins the same node-base image instead of emptying the CR set.
        seeded.image().or(() -> recordedImage));
  }

  /**
   * Overlay the sparse EDIT overrides onto a base facet: each entry is a dotted JSON path into the
   * facet ({@code publish.mesh}, {@code debug.networking.enabled}) → boolean. Applied at the JSON
   * level — the CLI owns the arg → path mapping, here it is generic — then decoded back to a facet.
   */
  private ManifestsRunbookInput.Facets overlay(
      ManifestsRunbookInput.Facets base, java.util.Map<String, Boolean> overrides) {
    final ObjectNode json = FACET_READER.valueToTree(base);
    overrides.forEach((path, value) -> setBooleanAtPath(json, path, value));
    try {
      return FACET_READER.treeToValue(json, ManifestsRunbookInput.Facets.class);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot overlay the edit facet: " + overrides, ex);
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
   * Decode the {@code facet} sub-tree of a recorded {@code manifest.yaml}; empty if
   * absent/unreadable.
   */
  private Optional<ManifestsRunbookInput.Facets> recordedFacets(String manifestYaml) {
    try {
      final JsonNode facet = FACET_READER.readTree(manifestYaml).path("facet");
      if (facet.isMissingNode() || facet.isNull()) {
        return Optional.empty();
      }
      return Optional.of(FACET_READER.treeToValue(facet, ManifestsRunbookInput.Facets.class));
    } catch (IOException ex) {
      return Optional.empty();
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
    try {
      final JsonNode image = FACET_READER.readTree(manifestYaml).path("image");
      if (image.isMissingNode() || image.isNull()) {
        return Optional.empty();
      }
      return Optional.of(FACET_READER.treeToValue(image, ImageState.class));
    } catch (IOException ex) {
      return Optional.empty();
    }
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
   * The GitHub token for the force-push, resolved by CONTAINER (the two lanes the {@code
   * host/host-runtime} {@code ExecutionEnclosure} FACT names):
   *
   * <ul>
   *   <li>OPERATOR — mint a FRESH {@code WRITER} installation token HERE, at the moment of the
   *       push, from the durable App credentials revealed at {@link GhAppCase} (the ghapp
   *       registration sealed them). The {@code auth} {@link GithubWriterTokenMint} edge holds the
   *       mint; the token is ephemeral (≈1 h) and never sealed, so it cannot go stale between a
   *       mint and a much later reveal (the trap a pre-provisioning seal fell into — minted before
   *       the cluster came up, dead by the time the push ran).
   *   <li>IN_CLUSTER — the renderer runs inside a Tekton PipelineRun; there is no cellar and the
   *       mint edge is absent. Pipelines-as-Code has already minted an App token and the {@code
   *       render-publish} step extracts it from the mounted {@code git_auth} secret into {@code
   *       RKE2LAB_PUSH_TOKEN}. Read in-container — the token never crosses the host↔OSGi membrane
   *       (no seam word), the twin locality of the mint.
   * </ul>
   *
   * <p>Empty when neither is present (a survey / preview, where the {@code cultivating}-gated mint
   * edge is filtered out; or a render that isn't a push) — the push is then simply skipped. The
   * OPERATOR mint wins when both are reachable (an operator run never sets the env).
   */
  private Optional<String> revealGithubToken() {
    final Optional<String> minted =
        writerTokenMint.flatMap(
            mint ->
                revealGithubApp()
                    .flatMap(
                        app -> mint.mint(app.appId(), app.installationId(), app.privateKeyPem())));
    return minted.or(
        () ->
            Optional.ofNullable(System.getenv("RKE2LAB_PUSH_TOKEN"))
                .map(String::trim)
                .filter(token -> !token.isEmpty()));
  }

  /** The rendered-branch delivery plan carried from the scenario into the THEN. */
  private record Delivery(
      String message,
      GitIdentity identity,
      String signingKey,
      boolean push,
      Optional<String> token) {}

  /**
   * The cluster-pki seal's {@code admin-credentials} cellar case, addressed by its NEUTRAL wire
   * coordinate so the manifests realm reveals it without a compile link to {@code
   * cluster-pki-contract}. Naming that domain's {@code ClusterPkiCoordinate} enum would drag its
   * {@code type=dual-realm} flat copy into the standalone {@code manifests-cli} assembly — a dead
   * flat copy the staging gate rightly flags. The membrane speaks slugs; the {@code slug}/{@code
   * domain} here MUST match {@code ClusterPkiCoordinate.ADMIN_CREDENTIALS}. This is the one place
   * the manifests realm knows that cross-realm wire name (the cellar matches a read case by slug).
   */
  private enum ClusterPkiCase implements SeedCoordinate {
    ADMIN_CREDENTIALS("admin-credentials"),
    CLUSTER_ISSUER_CA("cluster-issuer-ca"),
    WORKLOAD_CLUSTER_CAS("workload-cluster-cas");

    private final String slug;

    ClusterPkiCase(String slug) {
      this.slug = slug;
    }

    @Override
    public String slug() {
      return slug;
    }

    @Override
    public String domain() {
      return "cluster-pki";
    }
  }

  @Test
  void the_manifests_are_synthesized_from_the_activation_facet() {
    final ManifestsRunbookInput facet =
        Objects.requireNonNull(input, "the activation facet was not seeded before the body");
    // Prepare the rendered-branch worktree (a provisioning run) — the synthesis materialises INTO
    // it, and the THEN seals + delivers it. Empty for a bare survey / the standalone CLI: the
    // synthesis then falls back to a temp dir with no branch, and the delivery THEN is a no-op.
    final Optional<LinkedWorktree> rendered = prepareRenderWorktree(facet);
    // Resolve the effective facet per the sower's RenderMode: GROW/INIT keep the seeded facet
    // (the grow's Pulumi SSOT / the CLI args), UPDATE follows the branch HEAD, EDIT overlays the
    // sparse operator overrides on HEAD — so a steady-state render never silently resets the branch
    // to the operator default (the mesh-drop footgun) yet the grow stays authoritative. INIT/UPDATE
    // /EDIT also guard branch existence. Absent a worktree (a survey) the seeded facet stands.
    final ManifestsRunbookInput effective = resolveFacet(facet, rendered);
    given().the_activation_facet(effective);
    when()
        .the_policy_is_derived_from_the_facet()
        .and()
        .the_manifests_are_synthesized(
            revealOperatorPki(),
            revealGithubApp(),
            revealReplicatorSources(),
            revealClusterIssuerCa(),
            revealWorkloadCas(),
            revealIncusIdentity(),
            rendered);
    then()
        .every_enabled_domain_produced_its_units()
        .and()
        .the_manifests_file_is_written()
        .and()
        .the_rendered_branch_is_delivered(rendered, deliveryPlan(effective, rendered));
    fileNodeBootstrap(rendered);
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

    public When the_manifests_are_synthesized(
        @Hidden Optional<OperatorPkiMaterial> operatorPki,
        @Hidden Optional<GithubAppMaterial> githubApp,
        @Hidden Optional<ReplicatorSourceSecretsMaterial> replicatorSources,
        @Hidden Optional<ClusterIssuerCaMaterial> clusterIssuerCa,
        @Hidden Optional<WorkloadClusterCasMaterial> workloadCas,
        @Hidden Optional<IncusIdentityMaterial> incusIdentity,
        @Hidden Optional<LinkedWorktree> rendered) {
      final ManifestsRunbookInput.DebugFacet debug = facet.facets().debug();
      final FloxDebugPolicy floxDebug =
          new FloxDebugPolicy(
              debug.mesh().enabled(),
              debug.networking().enabled(),
              debug.nriPlugins().flox().enabled());
      // Materialise INTO the rendered-branch worktree when one was prepared (a provisioning run —
      // the GROW mounts it and the THEN seals + delivers it), else a temp dir (a survey / the
      // standalone CLI). Mode-blind: whether the run is a survey is the frontier's business.
      final Path root = rendered.map(LinkedWorktree::path).orElseGet(this::freshTempDir);
      // manifests.yaml is the INTERMEDIATE aggregate, not part of the mounted/checksummed tree — it
      // sits a level ABOVE the synthesis root (sibling of rke2-manifests.d), so the staging replica
      // the scion checksums holds only the manifest units, never the merged file. Falls back into
      // the root when the SOIL is a bare temp dir with no usable parent.
      final Path parent = root.getParent();
      final Path manifestFile = (parent == null ? root : parent).resolve("manifests.yaml");
      final ManifestSynthesisRequest.Builder builder =
          ManifestSynthesisRequest.builder(root, manifestFile)
              .manifestDomainPolicy(java.util.Optional.of(domainPolicy))
              .floxDebugPolicy(floxDebug);
      // The management render's workload targets (from the manifests facet): the DIFFERENT clusters
      // whose CAPI CR set this run emits onto manifests/<host>-mgmt (model B — the CRs live where
      // CAPI runs). Empty on a mgmt-only or survey run; the cluster-api units derive each target's
      // blueprint from its clusterName.
      builder.workloadTargets(facet.facets().workloadTargets());
      // The cross-frontier identity view: reaped ONCE at this scion via the WORKTREE amendment,
      // then
      // handed to synthesis on the request — the synthesis root threads one NodeEnvContext derived
      // from it to every unit. Absent (a bare survey / no worktree amended) → the request keeps its
      // unknown identity and the synthesis renders a clearly-blank cluster.
      facet
          .identity()
          .ifPresent(
              w ->
                  builder.bootstrapIdentity(
                      BootstrapIdentity.builder()
                          .clusterName(w.clusterName())
                          .nodeName(w.nodeName())
                          .build()));
      // The built node-base image's identity, forwarded by the incus scion as the IMAGE_STATE
      // amendment (empty on a survey / a render with no image built): the image-state ConfigMap and
      // the workload CR units pin the image fingerprint and the RKE2 version from it.
      builder.imageState(facet.image());
      // The operator PKI revealed from the cellar (empty on a bare survey / before the seal filed):
      // the kubeconfig unit renders the operator + CAPI kubeconfigs from it, or nothing.
      builder.operatorPki(operatorPki);
      // The one App credentials revealed from the cellar (empty on a bare survey / before the ghapp
      // registration filed): the githubapp Secret unit renders Flux's App-auth Secret from them, or
      // nothing.
      builder.githubApp(githubApp);
      // The replicator SOURCE secrets revealed from the cellar (empty on a bare survey / before the
      // seal filed): ReplicatorManifestsUnit renders them onto the node-bootstrap lane, or nothing.
      builder.replicatorSources(replicatorSources);
      // The cluster-issuer CA revealed from the cellar (empty on a bare survey / secret-blind
      // in-cluster render): ClusterIssuerManifestsUnit renders the ClusterIssuer + its key Secret
      // onto the node-bootstrap lane, or (no material) just leaves the branch ClusterIssuer.
      builder.clusterIssuerCa(clusterIssuerCa);
      // The workload clusters' BYO-CA sets revealed from the cellar (empty on a bare survey /
      // secret-blind in-cluster render): ClusterApiWorkloadManifestsUnit renders the four
      // <cluster>-{ca,cca,etcd,peer-etcd} Secrets onto the node-bootstrap lane, or nothing.
      builder.workloadCas(workloadCas);
      // The CAPN provider incus identity revealed from the cellar (empty on a bare survey /
      // secret-blind in-cluster render): ClusterApiWorkloadManifestsUnit renders the
      // <host>-incus-identity Secret onto the node-bootstrap lane, or nothing.
      builder.incusIdentity(incusIdentity);
      final ManifestSynthesisRequest request = builder.build();
      try {
        this.result = synthesis.orElseThrow().synthesize(request);
      } catch (IOException ex) {
        throw new UncheckedIOException("manifests synthesis failed", ex);
      }
      // Record the facet that produced this tree at the branch ROOT, so the branch is
      // self-describing and a later in-cluster render reads it back (the facet follows the grow —
      // see docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc § render-config). Only
      // for
      // a real delivery worktree (root = the branch root the THEN stages + pushes); a bare survey /
      // temp-dir render records nothing. This is written by the FLOW, not a ManifestsUnit: only
      // here
      // is the raw facet (incl. delivery) in hand, and the exploder has no root path.
      rendered.ifPresent(
          linkedWorktree ->
              recordRenderFacet(linkedWorktree.path(), facet.facets(), facet.image()));
      return self();
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
      final LinkedWorktree linkedWorktree = rendered.orElseThrow();
      final Delivery plan = delivery.orElseThrow();
      linkedWorktree.stageAll();
      linkedWorktree.commit(plan.message(), plan.identity(), Optional.of(plan.signingKey()));
      if (plan.push()) {
        plan.token().ifPresent(linkedWorktree::push);
      }
      return self();
    }
  }
}
