package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.StepModel;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.manifests.contract.BranchSecretCarrier;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import io.seedmatic.rke2lab.worktree.BuildRevision;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.DurableWorktree;
import io.seedmatic.rke2lab.worktree.Provenance;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TransientWorktrees;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import io.seedmatic.rke2lab.worktree.WorkingState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the manifests scion, run WHERE the scenario lives (this passenger
 * shares the manifests-bdd host loader through the fragment). It resolves the REAL synthesis:
 * manifests-core's DS {@code ManifestSynthesisService} activates under SCR and the scenario drives
 * it (the real cdk8s synthesis runs inside it).
 *
 * <p>The synthesis binds ONE collaborator, {@link SshToAgeConverter} — a pure external-tool seam
 * (OpenSSH→age), mandatory so the {@code @Component} activates. The passenger registers a stub for
 * it in-container (the incus edge-mocking shape, on the shared fragment loader so no seam and no
 * system-export are needed): the scenario runs with no SSH key-store, so {@code
 * SopsAgeMaterialResolver} fail-softs and the converter is never actually reached — the stub throws
 * if it ever is, surfacing that as the defect it would be.
 *
 * <p>It proves BOTH renders, as every sibling scion does. A LIVE run (no gate — {@code
 * GardeningSelection} defaults to cultivating) plays GREEN: the operator's facet is translated and
 * synthesised into the manifest tree. A SURVEY run (a surveying {@link RunGate}) renders PENDING:
 * manifests is a MODE-BLIND materialiser with no live-mutating edge — no {@code Cultivating}/{@code
 * Surveying} pair, since its single synthesis is honest as a plan (it writes only to staging) — so
 * {@code SurveyRenderExtension} gives it a {@code PendingMarkingScenarioExecutor}: the bodies STILL
 * run, only the render is rewritten PENDING.
 *
 * <p>It plays in-container through {@link ScenarioPlayer} (the shared play recipe the production
 * {@code GenericRunbookHandler} also drives) — seeding the activation facet through the scenario's
 * own inbound {@link ManifestSynthesisScenario#INPUT} channel, exactly as the handler does — and
 * asserts on the harvested {@link ScenarioOutcome}. It reads the LIVE outcome (same in-container
 * worker), no JSON round-trip. Registrations are removed in a {@code finally} because the framework
 * is shared across the passenger's tests (an oldest-wins ranking tie would otherwise leak a mock).
 */
public class ManifestSynthesisScenarioInContainerTest {

  private static final String CLUSTER = "test-mgmt";
  private static final String CARRIER = BranchSecretCarrier.IN_CLUSTER_CELLAR.file();
  private static final String MARKER = "SECRET-MARKER-7f3a";
  private static final String CARRIER_WITHOUT_ENVELOPES =
      "apiVersion: v1\nkind: Secret\nmetadata:\n  name: in-cluster-cellar\nstringData:\n  other: "
          + MARKER
          + "\n";

  @Test
  void a_live_run_synthesizes_from_the_activation_facet() throws Exception {
    // The operator's usual posture (everything on except mesh, debug off) — a complete facet, the
    // same shape a sower plucks from Pulumi.dev.yaml. No gate: the run defaults to cultivating.
    final ScenarioOutcome outcome = playWith(null);
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "the facet was translated and synthesised into the manifest tree — plays green");
  }

  @Test
  void a_survey_run_synthesizes_but_renders_pending() throws Exception {
    // Same facet, but a surveying gate. manifests has no Cultivating/Surveying pair (nothing to
    // swap — its one contact is a read-only converter), so SurveyRenderExtension installs a
    // PendingMarkingScenarioExecutor: it STILL proceed()s every body (the WHEN synthesises to
    // staging, the THENs assert on the real result), only the render is rewritten NORMAL → PENDING.
    final ScenarioOutcome outcome = playWith(() -> false);
    final ReportModel runbook = outcome.runbook();

    // The bodies were genuinely played, not skipped: the WHEN synthesis step is in the tree...
    assertNotNull(
        stepNamed(runbook, "the manifests are synthesized"),
        "the surveyed run still played the synthesis step (a materialiser, not survey-inert)");
    // ...and the run reads PENDING, not FAILED — and that distinction IS the proof the bodies ran:
    // had synthesis produced nothing, the THEN assertion (the manifests file) would have THROWN
    // and the scenario would read FAILED. PENDING means it held, and
    // only the render was rewritten — a plan, not a result, exactly as every sibling scion proves.
    assertEquals(
        ExecutionStatus.SCENARIO_PENDING,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "a surveyed materialiser renders PENDING — the bodies ran and held, only the render flipped");
  }

  @Test
  void a_render_with_no_in_cluster_asset_at_head_commits_and_publishes() throws Exception {
    // The control: the same provisioning render, with no carrier at HEAD (a first grow), reaches
    // the delivery — so the refusal below is what stops the commit, not a harness that never could.
    final SpyHead head = new SpyHead(Map.of());
    final ScenarioOutcome outcome = playRenderOnto(head, openingDecryptor());

    assertEquals(
        ExecutionStatus.SUCCESS,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        outcome.runbook().getScenarios().get(0).getScenarioCases().get(0).getErrorMessage());
    assertEquals(1, head.commits, "the render committed onto its head");
    assertEquals(1, head.publishes, "and published it");
  }

  @Test
  void an_in_cluster_asset_without_envelopes_fails_the_render_before_any_commit() throws Exception {
    // A carrier present at HEAD whose opened YAML holds no stringData.envelopes: rehydrating
    // nothing would render the IN_CLUSTER Secrets empty and Flux would prune the populated ones.
    // The render must fail, with the branch head where it was and nothing pushed.
    final SpyHead head = new SpyHead(Map.of(CARRIER, CARRIER_WITHOUT_ENVELOPES));
    final String before = head.head();
    final ScenarioOutcome outcome = playRenderOnto(head, openingDecryptor());

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "a present but unreadable in-cluster asset refuses the render");
    final String reason =
        outcome.runbook().getScenarios().get(0).getScenarioCases().get(0).getErrorMessage();
    assertTrue(reason.contains(CARRIER), "the refusal names the carrier: " + reason);
    assertFalse(reason.contains(MARKER), "the refusal carries nothing of the carrier");
    assertEquals(0, head.commits, "nothing was committed onto the render branch");
    assertEquals(before, head.head(), "the render branch head is unchanged");
    assertEquals(0, head.publishes, "nothing was pushed");
  }

  @Test
  void an_in_cluster_asset_that_does_not_open_fails_the_render_without_quoting_sops()
      throws Exception {
    // The carrier T4 meets: sealed to recipients this enclosure no longer holds, so its decrypt
    // throws — with sops' stderr in the message. Same verdict, and none of that stderr in the
    // reason the runbook, the graft and the logs carry.
    final SpyHead head = new SpyHead(Map.of(CARRIER, "sops: sealed\n"));
    final String before = head.head();
    final ScenarioOutcome outcome = playRenderOnto(head, undecryptableCarrier());

    assertEquals(
        ExecutionStatus.FAILED,
        outcome.runbook().getScenarios().get(0).getExecutionStatus(),
        "an in-cluster asset that does not open refuses the render");
    final String reason =
        outcome.runbook().getScenarios().get(0).getScenarioCases().get(0).getErrorMessage();
    assertTrue(reason.contains(CARRIER), "the refusal names the carrier: " + reason);
    assertFalse(reason.contains(MARKER), "the refusal carries nothing of sops' stderr: " + reason);
    assertEquals(0, head.commits, "nothing was committed onto the render branch");
    assertEquals(before, head.head(), "the render branch head is unchanged");
    assertEquals(0, head.publishes, "nothing was pushed");
  }

  /**
   * Register the synthesis collaborator (the ssh-to-age edge stub) — and, when {@code gate} is
   * non-null, an ambient {@link RunGate} selecting the mode — into THIS bundle's registry, play the
   * scenario in-container through the shared {@link ScenarioPlayer}, and return its live {@link
   * ScenarioOutcome}. Registrations are removed in the {@code finally} so each test plays against
   * exactly its own services.
   */
  private static ScenarioOutcome playWith(RunGate gate) throws Exception {
    final BundleContext context =
        FrameworkUtil.getBundle(ManifestsBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    // The synthesis component now also binds NdhKeystoreReader (mandatory) — seed a stub reporting
    // NO key-store, so SopsAgeMaterialResolver fail-softs exactly as before (the test seeds none).
    registrations.add(
        context.registerService(NdhKeystoreReader.class, absentKeystore(), new Hashtable<>()));
    if (gate != null) {
      registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    }
    return play(context, registrations, ManifestsRunbookInput.defaults());
  }

  /**
   * Play a PROVISIONING render of {@code test-mgmt} onto {@code head}, opening its carrier with
   * {@code decryptor}: the delivery seam and the source seat it is rendered from, a parcel over an
   * empty durable cellar, the sops edges and a key-store that only names the signing identity — the
   * spy head never signs, so nothing real is reached.
   */
  private static ScenarioOutcome playRenderOnto(SpyHead head, SopsDecryptor decryptor)
      throws Exception {
    final BundleContext context =
        FrameworkUtil.getBundle(ManifestsBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(
        context.registerService(
            TransientWorktrees.class,
            (TransientWorktrees) (branch, fetchToken) -> head,
            new Hashtable<>()));
    registrations.add(
        context.registerService(DurableWorktree.class, sourceSeat(), new Hashtable<>()));
    registrations.add(
        context.registerService(Parcel.class, new Parcel("rke2lab", "test"), new Hashtable<>()));
    registrations.add(context.registerService(Cellar.class, emptyCellar(), new Hashtable<>()));
    registrations.add(context.registerService(SopsDecryptor.class, decryptor, new Hashtable<>()));
    registrations.add(
        context.registerService(SopsEncryptor.class, markingEncryptor(), new Hashtable<>()));
    registrations.add(
        context.registerService(NdhKeystoreReader.class, signingOnlyKeystore(), new Hashtable<>()));
    return play(
        context,
        registrations,
        ManifestsRunbookInput.builder()
            .materializationRoot(SpyHead.BRANCH)
            .identity(new ManifestsRunbookInput.Identity(CLUSTER, "master"))
            .build());
  }

  private static ScenarioOutcome play(
      BundleContext context,
      List<ServiceRegistration<?>> registrations,
      ManifestsRunbookInput facet)
      throws Exception {
    registrations.add(
        context.registerService(
            SshToAgeConverter.class, unreachableConverter(), new Hashtable<>()));
    try {
      return new ScenarioPlayer()
          .play(ManifestSynthesisScenario.class, ManifestSynthesisScenario.INPUT.into(facet));
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The named top-level step of the played scenario. */
  private static StepModel stepNamed(ReportModel runbook, String name) {
    return runbook.getScenarios().get(0).getScenarioCases().get(0).getSteps().stream()
        .filter(step -> name.equals(step.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step named '" + name + "' in the runbook"));
  }

  /**
   * A converter the test never expects to reach — the scenario runs with no SSH key-store, so
   * {@code SopsAgeMaterialResolver} fail-softs before any conversion. If synthesis ever DID call
   * it, that is a defect the test must surface, so the stub throws rather than fabricating an age
   * key.
   */
  private static SshToAgeConverter unreachableConverter() {
    return sshPrivateKey -> {
      throw new AssertionError(
          "the in-container synthesis reached the ssh-to-age edge, but the test seeds no key-store");
    };
  }

  /**
   * A key-store reader reporting ABSENCE — the test seeds no ndh inventory, so {@code
   * SopsAgeMaterialResolver} fail-softs on {@code present() == false} and never reaches an
   * accessor. The accessors throw for the same reason {@link #unreachableConverter()} does:
   * reaching one is a defect the test must surface, not fabricate around.
   */
  private static NdhKeystoreReader absentKeystore() {
    return new NdhKeystoreReader() {
      @Override
      public boolean present() {
        return false;
      }

      @Override
      public String authorityCert(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String authorityDomain(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String authorityPrivate(String authority) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String sshPrivate(String keyName) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }

      @Override
      public String sshPublic(String keyName) {
        throw new AssertionError(
            "the in-container synthesis read the ndh key-store, but none seeded");
      }
    };
  }

  /**
   * The render branch's head, in memory: it serves {@code atHead} as the committed files and counts
   * what the delivery does to it — a commit moves its sha, a publish pushes it (or, unarmed, moves
   * the local ref).
   */
  private static final class SpyHead implements TransientWorktree {
    static final String BRANCH = "manifests/" + CLUSTER;

    private final Map<String, String> atHead;
    private String sha = "0".repeat(40);
    int commits;
    int publishes;

    SpyHead(Map<String, String> atHead) {
      this.atHead = atHead;
    }

    @Override
    public String branch() {
      return BRANCH;
    }

    @Override
    public String head() {
      return sha;
    }

    @Override
    public Optional<String> readAtHead(String path) {
      return Optional.ofNullable(atHead.get(path));
    }

    @Override
    public String commit(TreeEdit edit, CommitSpec spec) {
      commits++;
      sha = String.format("%040x", commits);
      return sha;
    }

    @Override
    public void publish(Optional<String> pushToken, Duration timeout) {
      publishes++;
    }
  }

  /**
   * The seat the render is synthesised from: a clean provenance, a {@code flake.lock} pinning
   * nixpkgs for the branch's installer flake and the {@code .sops.yaml} its Secrets are sealed
   * under. Nothing else of it is reached.
   */
  private static DurableWorktree sourceSeat() {
    return new DurableWorktree() {
      @Override
      public Path root() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Path repository() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Provenance provenance() {
        return new Provenance("1".repeat(40), false);
      }

      @Override
      public WorkingState workingState() {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean flakeLockCoherent() {
        throw new UnsupportedOperationException();
      }

      @Override
      public BuildRevision buildRevision(List<String> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void releaseBuildRevision(BuildRevision revision) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String treeDigest(String revision, List<String> paths) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<String> readAtHead(String path) {
        return switch (path) {
          case "flake.lock" ->
              Optional.of(
                  "{\"nodes\":{\"nixpkgs\":{\"locked\":{\"rev\":\"" + "2".repeat(40) + "\"}}}}");
          case ".sops.yaml" -> Optional.of("creation_rules: []\n");
          default -> Optional.empty();
        };
      }

      @Override
      public String commit(TreeEdit edit, CommitSpec spec) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** A durable cellar holding nothing: no seal filed, every reveal resolves empty. */
  private static Cellar emptyCellar() {
    return new Cellar() {
      @Override
      public <T> void store(
          Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {}

      @Override
      public <T> List<T> fetch(Parcel parcel, Class<T> type) {
        return List.of();
      }

      @Override
      public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
        return Optional.empty();
      }

      @Override
      public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
        return Optional.empty();
      }

      @Override
      public List<Parcel> neighbours(Parcel parcel) {
        return List.of();
      }
    };
  }

  /** Opens a carrier by handing it back: the carrier the spy head serves is already the clear. */
  private static SopsDecryptor openingDecryptor() {
    return new SopsDecryptor() {
      @Override
      public String decryptYaml(String sopsYaml, String ageIdentity) {
        return sopsYaml;
      }

      @Override
      public String decryptYaml(String sopsYaml) {
        return sopsYaml;
      }
    };
  }

  /**
   * Fails the way the sops edge does for a carrier sealed to recipients this enclosure no longer
   * holds: its message quotes sops' stderr, here the marker.
   */
  private static SopsDecryptor undecryptableCarrier() {
    return new SopsDecryptor() {
      @Override
      public String decryptYaml(String sopsYaml, String ageIdentity) {
        return decryptYaml(sopsYaml);
      }

      @Override
      public String decryptYaml(String sopsYaml) {
        throw new IllegalStateException(
            "sops --decrypt exited with code 128: no key could decrypt the data key; " + MARKER);
      }
    };
  }

  /**
   * Seals each rendered Secret by replacing its {@code data}/{@code stringData} values with an
   * {@code ENC[…]} marker — the shape the seal checks, with no sops and no key behind it.
   */
  private static SopsEncryptor markingEncryptor() {
    final SeedCodec codec = new SeedCodec();
    return new SopsEncryptor() {
      @Override
      public String encryptYaml(String plaintextYaml, List<String> ageRecipients) {
        throw new AssertionError("the render sealed for recipients instead of under its policy");
      }

      @Override
      public String encryptYaml(String plaintextYaml, Path policy, String path) {
        final List<ObjectNode> documents =
            codec
                .reading()
                .readYamlDocuments(
                    new ByteArrayInputStream(plaintextYaml.getBytes(StandardCharsets.UTF_8)),
                    ObjectNode.class)
                .toList();
        for (ObjectNode document : documents) {
          for (String field : List.of("data", "stringData")) {
            if (document.get(field) instanceof ObjectNode values) {
              values.fieldNames().forEachRemaining(key -> values.put(key, "ENC[test]"));
            }
          }
        }
        return codec.canonical().writeYamlDocuments(documents);
      }
    };
  }

  /**
   * Absent to the synthesis (so it fail-softs as {@link #absentKeystore()}), yet naming the bot's
   * domain and a signing key for the delivery plan — which the spy head never uses to sign.
   */
  private static NdhKeystoreReader signingOnlyKeystore() {
    return new NdhKeystoreReader() {
      @Override
      public boolean present() {
        return false;
      }

      @Override
      public String authorityCert(String authority) {
        throw new AssertionError("the render read a certificate from the ndh key-store");
      }

      @Override
      public String authorityDomain(String authority) {
        return "example.invalid";
      }

      @Override
      public String authorityPrivate(String authority) {
        throw new AssertionError("the render read an authority key from the ndh key-store");
      }

      @Override
      public String sshPrivate(String keyName) {
        return "unused-by-the-spy-head";
      }

      @Override
      public String sshPublic(String keyName) {
        throw new AssertionError("the render read a public key from the ndh key-store");
      }
    };
  }
}
