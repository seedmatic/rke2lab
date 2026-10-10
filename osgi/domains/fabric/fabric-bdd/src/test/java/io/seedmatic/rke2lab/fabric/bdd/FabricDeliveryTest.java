package io.seedmatic.rke2lab.fabric.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.fabric.contract.FabricDelivery;
import io.seedmatic.rke2lab.fabric.contract.FabricRunbookInput;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fabric delivery: it reads the two harvests the run filed and writes them, canonically, into
 * the linked worktree of its branch. The git MECHANISM is the worktree domain's and is proven by
 * {@code JgitLinkedWorktreesTest} — here it is a recording double, so what is under test is
 * fabric's own behaviour: which coordinates it reads, which files it writes, in what form, and what
 * it asks of the worktree.
 */
class FabricDeliveryTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");

  @TempDir Path tmp;

  /** Records what the delivery asked of the worktree, and keeps the files so they can be read. */
  private static final class RecordingWorktree implements LinkedWorktree {
    private final Path path;
    private final String branch;
    private final List<String> calls = new ArrayList<>();
    private Optional<GitIdentity> committedAs = Optional.empty();
    private Optional<String> signedWith = Optional.empty();

    RecordingWorktree(Path path, String branch) {
      this.path = path;
      this.branch = branch;
    }

    @Override
    public Path path() {
      return path;
    }

    @Override
    public String branch() {
      return branch;
    }

    @Override
    public Optional<String> readAtHead(String file) {
      // The double commits nothing, so "at HEAD" is what the delivery staged — enough to prove the
      // committed tree carries both files; that a commit records its tree is jgit's own contract.
      final Path staged = path.resolve(file);
      try {
        return Files.exists(staged) ? Optional.of(Files.readString(staged)) : Optional.empty();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }

    @Override
    public Optional<String> smudgeFromHead(String file) {
      return readAtHead(file);
    }

    @Override
    public void restoreFromHead(String pathspec) {
      calls.add("restoreFromHead");
    }

    @Override
    public void stage(List<Path> paths) {
      calls.add("stage");
    }

    @Override
    public void stageAll() {
      calls.add("stageAll");
    }

    @Override
    public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
      calls.add("commit:" + message);
      this.committedAs = Optional.of(identity);
      this.signedWith = sshSigningKey;
      return "0000000000000000000000000000000000000000";
    }

    @Override
    public void push(String token, Duration timeout) {
      calls.add("push");
    }

    @Override
    public void close() {
      calls.add("close");
    }
  }

  private static final class RecordingWorktrees implements LinkedWorktrees {
    private RecordingWorktree made;

    @Override
    public LinkedWorktree prepare(Path worktreePath, String branch) {
      try {
        Files.createDirectories(worktreePath);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
      this.made = new RecordingWorktree(worktreePath, branch);
      return made;
    }
  }

  /** A key-store that answers only what the delivery asks of it. */
  private static final class FakeKeystore implements NdhKeystoreReader {
    @Override
    public boolean present() {
      return true;
    }

    @Override
    public String authorityCert(String authority) {
      throw new UnsupportedOperationException(authority);
    }

    @Override
    public String authorityDomain(String authority) {
      return "mammoth-skate.example.invalid";
    }

    @Override
    public String authorityPrivate(String authority) {
      throw new UnsupportedOperationException(authority);
    }

    @Override
    public String sshPrivate(String keyName) {
      return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + keyName + "\n";
    }

    @Override
    public String sshPublic(String keyName) {
      throw new UnsupportedOperationException(keyName);
    }
  }

  private static JsonNode tree(String json) {
    return new SeedCodec().reading().readJson(json);
  }

  private InMemoryCellar harvested(String netplan, String dataplan) {
    final InMemoryCellar cellar = new InMemoryCellar();
    if (netplan != null) {
      cellar.store(PARCEL, NetplanIngressCoordinate.PROJECTION, tree(netplan));
    }
    if (dataplan != null) {
      cellar.store(PARCEL, DataplanCoordinate.LAYOUT, tree(dataplan));
    }
    return cellar;
  }

  private FabricDeliveryScenario.When when() {
    final FabricDeliveryScenario.When when = new FabricDeliveryScenario.When();
    when.facet = new FabricRunbookInput(Optional.of(tmp.toString()));
    return when;
  }

  @Test
  void the_delivery_writes_both_harvests_in_the_canonical_form() {
    final FabricDeliveryScenario.When when = when();
    final RecordingWorktrees worktrees = new RecordingWorktrees();

    when.the_harvested_plan_is_read(
            harvested("{\"b\":1,\"a\":{\"d\":4,\"c\":3}}", "{\"datasets\":[\"tank/rke2lab\"]}"),
            Optional.of(PARCEL))
        .the_delivery_is_written(Optional.of(worktrees), Optional.empty());

    final FabricDelivery delivery = FabricDelivery.canonical();
    assertEquals(
        """
        {
          "a": {
            "c": 3,
            "d": 4
          },
          "b": 1
        }
        """,
        worktrees.made.readAtHead(delivery.netplanFile()).orElseThrow());
    assertEquals(
        """
        {
          "datasets": [
            "tank/rke2lab"
          ]
        }
        """,
        worktrees.made.readAtHead(delivery.dataplanFile()).orElseThrow());
  }

  @Test
  void the_delivery_lands_on_its_own_branch_under_the_amended_plot() {
    final RecordingWorktrees worktrees = new RecordingWorktrees();

    when()
        .the_harvested_plan_is_read(harvested("{}", "{}"), Optional.of(PARCEL))
        .the_delivery_is_written(Optional.of(worktrees), Optional.empty());

    assertEquals("fabric/plan", worktrees.made.branch());
    assertEquals(tmp.resolve("fabric/plan"), worktrees.made.path());
  }

  @Test
  void the_commit_is_signed_and_attributed_when_the_keystore_is_reachable() {
    final RecordingWorktrees worktrees = new RecordingWorktrees();

    when()
        .the_harvested_plan_is_read(harvested("{}", "{}"), Optional.of(PARCEL))
        .the_delivery_is_written(Optional.of(worktrees), Optional.of(new FakeKeystore()));

    assertEquals(
        new GitIdentity(
            "rke2lab:fabric-delivery", "rke2lab+fabric-delivery@mammoth-skate.example.invalid"),
        worktrees.made.committedAs.orElseThrow());
    assertTrue(
        worktrees.made.signedWith.orElseThrow().contains("github-signing"),
        "the signing key is named through NdhKeystoreCatalog, not re-typed");
  }

  /**
   * The LIMIT of this slice, pinned so the slice that lifts it has to change this test rather than
   * remember to. The delivery commits locally and does NOT push: a push needs a freshly minted
   * token, which only the publishing CLI can reveal. An unsigned, unpushed commit is also why the
   * missing key-store is tolerated here — a signature protects what is published.
   */
  @Test
  void the_delivery_is_not_pushed_until_a_publishing_cli_can_reveal_a_token() {
    final RecordingWorktrees worktrees = new RecordingWorktrees();

    when()
        .the_harvested_plan_is_read(harvested("{}", "{}"), Optional.of(PARCEL))
        .the_delivery_is_written(Optional.of(worktrees), Optional.empty());

    assertTrue(worktrees.made.calls.contains("stageAll"));
    assertTrue(worktrees.made.calls.stream().anyMatch(call -> call.startsWith("commit:")));
    assertFalse(worktrees.made.calls.contains("push"), "this slice delivers no push");
    assertEquals(
        Optional.empty(),
        worktrees.made.signedWith,
        "without a key-store the local commit is unsigned, never silently self-signed");
  }

  @Test
  void a_run_that_harvested_nothing_is_a_broken_run_not_an_empty_delivery() {
    assertEquals(
        FabricDeliveryError.Reason.NO_PARCEL,
        assertThrows(
                FabricDeliveryError.class,
                () -> when().the_harvested_plan_is_read(harvested("{}", "{}"), Optional.empty()))
            .reason());
    assertEquals(
        FabricDeliveryError.Reason.NO_NETPLAN,
        assertThrows(
                FabricDeliveryError.class,
                () -> when().the_harvested_plan_is_read(harvested(null, "{}"), Optional.of(PARCEL)))
            .reason());
    assertEquals(
        FabricDeliveryError.Reason.NO_DATAPLAN,
        assertThrows(
                FabricDeliveryError.class,
                () -> when().the_harvested_plan_is_read(harvested("{}", null), Optional.of(PARCEL)))
            .reason());
  }

  @Test
  void a_missing_worktree_mechanism_is_a_wiring_defect() {
    final FabricDeliveryScenario.When when = when();
    when.the_harvested_plan_is_read(harvested("{}", "{}"), Optional.of(PARCEL));

    assertEquals(
        FabricDeliveryError.Reason.NO_WORKTREES,
        assertThrows(
                FabricDeliveryError.class,
                () -> when.the_delivery_is_written(Optional.empty(), Optional.empty()))
            .reason());
  }
}
