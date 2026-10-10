package io.seedmatic.rke2lab.fabric.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.fabric.contract.FabricDelivery;
import io.seedmatic.rke2lab.fabric.contract.FabricRunbookInput;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.worktree.GitBotIdentities;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.LinkedWorktree;
import io.seedmatic.rke2lab.worktree.LinkedWorktrees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The fabric delivery scenario — what seedmatic READS of rke2lab, written where the other repos pin
 * it. It derives NOTHING: it reads the two harvests the netplan and dataplan scions filed in this
 * run's cellar and writes them, in the codec's canonical form, into a linked worktree of {@link
 * FabricDelivery#branch}.
 *
 * <p>Why a domain of its own rather than each domain delivering its own file: the delivery is one
 * commit carrying both files, so it has one owner. netplan and dataplan each derive and harvest;
 * neither knows the branch, the other's file, or that a git commit is involved. Fabric knows only
 * that it holds two opaque JSON trees — it reads them as {@code JsonNode}, never as a netplan or
 * dataplan type, so no foreign vocabulary enters and the realm boundary is not tested by this seam.
 *
 * <p>The commit is LOCAL. Signing happens when the ndh key-store is reachable (the OPERATOR
 * enclosure, the only one that publishes a plan), and the delivery is NOT pushed: the push needs a
 * freshly minted token, which only the publishing CLI can reveal. Until that CLI exists the bytes
 * are the deliverable, handed to the integration for republication — {@code
 * FabricDeliveryTest.the_delivery_is_not_pushed_until_a_publishing_cli_can_reveal_a_token} pins
 * that limit, so the slice that adds the push has to change it rather than remember it.
 */
@SeedScenario
public class FabricDeliveryScenario
    extends ScenarioTestBase<
        FabricDeliveryScenario.Given, FabricDeliveryScenario.When, FabricDeliveryScenario.Then>
    implements CellarReceiver<Cellar>, InputReceiver<FabricRunbookInput>, ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code FabricRunbookHandler.seedFrom}) seeds the
   * {@link FabricRunbookInput} through and this scenario receives it from. Single-sourced here.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<FabricRunbookInput> INPUT =
      new ScenarioInputSeed<>(FabricRunbookInput.class, "fabric-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private FabricRunbookInput input;

  // The transactional cellar the extension injects before the body — where the two harvests are.
  @MonotonicNonNull private Cellar cellar;

  // The run's parcel: the plot the harvests were filed under. A run that published none harvested
  // nothing, so the delivery has nothing to read — hence optional here and a hard failure inside.
  @OsgiService(await = false)
  private Optional<Parcel> parcel = Optional.empty();

  // The git mechanism, owned by the worktree domain. Optional because a bare shape probe resolves
  // no services; absent at delivery time is a wiring defect, raised as such.
  @OsgiService(await = false)
  private Optional<LinkedWorktrees> linkedWorktrees = Optional.empty();

  // The ndh key-store the commit's bot identity and signing key come from. Absent in a run whose
  // sops key-store is unreadable — the commit is then unsigned, which is honest for a commit that
  // is not pushed: a signature protects what is PUBLISHED, and publishing is the next slice.
  @OsgiService(await = false)
  private Optional<NdhKeystoreReader> keystore = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(FabricRunbookInput input) {
    this.input = input;
  }

  @Override
  public void receiveCellar(Cellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_plan_is_delivered_to_its_branch() {
    final FabricRunbookInput facet =
        Objects.requireNonNull(input, "the fabric runbook input was not seeded before the body");
    given().the_runbook_input(facet);
    final Cellar harvest =
        Objects.requireNonNull(cellar, "the cellar was not injected before the body");
    when()
        .the_harvested_plan_is_read(harvest, parcel)
        .and()
        .the_delivery_is_written(linkedWorktrees, keystore);
    then().the_delivery_carries_both_files();
  }

  /** Given: the runbook input carrying the plot the linked worktree is made under. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState FabricRunbookInput facet;

    @Hidden
    public Given the_runbook_input(FabricRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /** When: read both harvests, then write them into the delivery's linked worktree. */
  public static class When extends Stage<When> {

    @ExpectedScenarioState FabricRunbookInput facet;

    @ProvidedScenarioState FabricDelivery delivery;

    @ProvidedScenarioState @MonotonicNonNull LinkedWorktree worktree;

    @ProvidedScenarioState @MonotonicNonNull String deliveredSha;

    private final SeedCodec codec = new SeedCodec();

    // Read by the first WHEN step, written by the second — intra-stage, so plain fields.
    @MonotonicNonNull private JsonNode netplan;
    @MonotonicNonNull private JsonNode dataplan;

    public When the_harvested_plan_is_read(@Hidden Cellar cellar, @Hidden Optional<Parcel> parcel) {
      final Parcel run =
          parcel.orElseThrow(() -> new FabricDeliveryError(FabricDeliveryError.Reason.NO_PARCEL));
      this.netplan =
          cellar
              .fetch(run, NetplanIngressCoordinate.PROJECTION, JsonNode.class)
              .orElseThrow(() -> new FabricDeliveryError(FabricDeliveryError.Reason.NO_NETPLAN));
      this.dataplan =
          cellar
              .fetch(run, DataplanCoordinate.LAYOUT, JsonNode.class)
              .orElseThrow(() -> new FabricDeliveryError(FabricDeliveryError.Reason.NO_DATAPLAN));
      return self();
    }

    public When the_delivery_is_written(
        @Hidden Optional<LinkedWorktrees> worktrees, @Hidden Optional<NdhKeystoreReader> keystore) {
      this.delivery = FabricDelivery.canonical();
      final LinkedWorktrees mechanism =
          worktrees.orElseThrow(
              () -> new FabricDeliveryError(FabricDeliveryError.Reason.NO_WORKTREES));
      final LinkedWorktree linked =
          mechanism.prepare(soil().resolve(delivery.branch()), delivery.branch());
      this.worktree = linked;
      write(linked.path().resolve(delivery.netplanFile()), read(netplan, delivery.netplanFile()));
      write(
          linked.path().resolve(delivery.dataplanFile()), read(dataplan, delivery.dataplanFile()));
      linked.stageAll();
      this.deliveredSha = linked.commit(message(), identity(keystore), signingKey(keystore));
      return self();
    }

    /**
     * The delivery commit subject. It names WHAT is delivered rather than a revision: the two files
     * are a projection of the run, and the run's provenance is the commit's own parent chain on an
     * accreting branch.
     */
    private String message() {
      return "deliver " + delivery.netplanFile() + " + " + delivery.dataplanFile();
    }

    /**
     * The bot identity, minted by the worktree domain's {@link GitBotIdentities} from the
     * key-store's authority domain — the single source every automated commit of this project
     * reads, so the fabric delivery cannot drift into a convention of its own. Without a key-store
     * the identity falls back to the tool with no authority domain, which is what an unsigned local
     * commit deserves; the publishing slice makes the key-store mandatory because it pushes.
     */
    private GitIdentity identity(Optional<NdhKeystoreReader> keystore) {
      return keystore
          .map(
              ks ->
                  new GitBotIdentities(
                          ks.authorityDomain(NdhKeystoreCatalog.TAILNET_AUTHORITY.entryName()))
                      .forTool(DELIVERY_TOOL))
          .orElseGet(() -> new GitBotIdentities(UNATTRIBUTED_DOMAIN).forTool(DELIVERY_TOOL));
    }

    private Optional<String> signingKey(Optional<NdhKeystoreReader> keystore) {
      return keystore.map(ks -> ks.sshPrivate(NdhKeystoreCatalog.SIGNING_KEY.entryName()));
    }

    private Path soil() {
      return facet
          .worktreesRoot()
          .map(root -> Path.of(root).toAbsolutePath().normalize())
          .orElseGet(this::freshTempDir);
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-fabric-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the fabric delivery dir", ex);
      }
    }

    /** The harvest the read step holds, refused if the steps were played out of order. */
    private JsonNode read(@Nullable JsonNode harvested, String file) {
      return Objects.requireNonNull(
          harvested, "the read step must run before the delivery is written: " + file);
    }

    private void write(Path file, JsonNode tree) {
      try {
        Files.writeString(file, codec.canonical().writeJson(tree));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot write the fabric delivery " + file, ex);
      }
    }
  }

  /** The discriminator this domain's automated commits carry — provenance, never a new base. */
  static final String DELIVERY_TOOL = "fabric-delivery";

  /**
   * The authority domain an UNSIGNED local delivery is attributed under. {@code .invalid} is
   * reserved by RFC 2606 precisely so it can never resolve: an unsigned commit's email must not
   * look like a mailbox someone could answer.
   */
  static final String UNATTRIBUTED_DOMAIN = "unsigned.invalid";

  /**
   * Then: the delivery holds both files AS COMMITTED — read back from the commit's tree, not from
   * the working directory, because the commit is what the branch publishes and what the next slice
   * will push. The worktree is removed afterwards: it is a transient checkout, and the delivery
   * that outlives it is the commit.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState FabricDelivery delivery;

    @ExpectedScenarioState LinkedWorktree worktree;

    @ExpectedScenarioState String deliveredSha;

    public Then the_delivery_carries_both_files() {
      try {
        for (final String file : delivery.files()) {
          final String committed =
              worktree
                  .readAtHead(file)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "the fabric delivery " + deliveredSha + " carries no " + file));
          if (committed.isBlank()) {
            throw new IllegalStateException(
                "the fabric delivery " + deliveredSha + " carries an empty " + file);
          }
        }
      } finally {
        worktree.close();
      }
      return self();
    }
  }
}
