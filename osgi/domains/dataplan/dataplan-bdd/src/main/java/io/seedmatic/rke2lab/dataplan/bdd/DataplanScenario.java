package io.seedmatic.rke2lab.dataplan.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.dataplan.contract.DataplanRunbookInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
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
 * The dataplan layout-export scenario — a production jGiven scenario told in the DATAPLAN DOMAIN's
 * own vocabulary and played IN-CONTAINER by the engine. It builds the canonical {@link
 * DataplanLayout} (the {@code tank/rke2lab/*} dataset tree) and MATERIALISES it as {@code
 * dataplan.json} into the SOIL.
 *
 * <p>Why in-container and not a flat CLI dump: {@link DataplanLayout} is a {@code type=contract}
 * bundle record — it lives in the bundle realm and cannot cross the host frontier as a TYPE (a flat
 * reference {@code NoClassDefFoundError}s, the realm-boundary law is right). So the layout is built
 * HERE, where the type is reachable, and the result crosses to the host as pure JSON: the scion
 * serialises the layout to {@code dataplan.json}, the plan CLI reads that generic JSON (never the
 * contract type). SAFE for the flake bridge — ndh re-parses via {@code fromJSON}, so only the DATA
 * matters.
 *
 * <p>MODE-BLIND like the netplan scion: a pure FS materialiser with no live touch, so it runs
 * identically in both modes; the materialisation target is carried by the SOIL amendment alone (the
 * host's export dir when amended, a temp dir for a bare survey). The input is seeded by the
 * front-door via the inbound {@link #INPUT} channel and received here ({@link InputReceiver})
 * before the play.
 */
@SeedScenario
public class DataplanScenario
    extends ScenarioTestBase<DataplanScenario.Given, DataplanScenario.When, DataplanScenario.Then>
    implements CellarReceiver<Cellar>,
        InputReceiver<DataplanRunbookInput>,
        ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code DataplanRunbookHandler.seedFrom}) seeds the
   * {@link DataplanRunbookInput} through and this scenario receives it from. Single-sourced here.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<DataplanRunbookInput> INPUT =
      new ScenarioInputSeed<>(DataplanRunbookInput.class, "dataplan-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private DataplanRunbookInput input;

  // The transactional cellar the extension injects before the body; the layout is stored into it so
  // the fabric delivery reads the dataset tree from the run rather than from a file. Null until
  // receiveCellar sets it.
  @MonotonicNonNull private Cellar cellar;

  // The run's parcel, published by the seed host at its GIVEN (synchronously, before any sow), so a
  // snapshot sees it. The plan CLI publishes none: its run only exports to the soil, and nobody
  // fetches a layout there. Hence an optional collaborator, never awaited — the netplan treatment.
  @OsgiService(await = false)
  private Optional<Parcel> parcel = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(DataplanRunbookInput input) {
    this.input = input;
  }

  @Override
  public void receiveCellar(Cellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_layout_is_exported_to_the_soil() {
    final DataplanRunbookInput facet =
        Objects.requireNonNull(input, "the dataplan runbook input was not seeded before the body");
    given().the_runbook_input(facet);
    when().the_layout_is_derived().and().the_layout_is_written_as_json();
    final Cellar harvest =
        Objects.requireNonNull(cellar, "the cellar was not injected before the body");
    then().the_layout_file_is_written().and().the_layout_is_harvested(harvest, parcel);
  }

  /** Given: the runbook input carrying the SOIL to materialise into. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState DataplanRunbookInput facet;

    @Hidden
    public Given the_runbook_input(DataplanRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /** When: derive the canonical layout and write it as {@code dataplan.json} into the SOIL. */
  public static class When extends Stage<When> {

    @ExpectedScenarioState DataplanRunbookInput facet;

    @ProvidedScenarioState Path layoutFile;

    private final SeedCodec codec = new SeedCodec();

    // Derived by the first WHEN step, written as JSON by the second, and harvested by the THEN —
    // so it crosses stages and is scenario state, not a plain field.
    @ProvidedScenarioState @MonotonicNonNull DataplanLayout layout;

    public When the_layout_is_derived() {
      this.layout = DataplanLayout.canonical();
      return self();
    }

    public When the_layout_is_written_as_json() {
      final Path root = resolveSoil();
      final Path file = root.resolve("dataplan.json");
      try {
        Files.createDirectories(root);
        // SeedCodec renders the layout to JSON — the wire the host reaps. The scion never hands the
        // host a DataplanLayout type, only this serialized String.
        Files.writeString(
            file,
            codec.encode(
                Objects.requireNonNull(layout, "the layout step must run before the write step")));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot write the dataplan export " + file, ex);
      }
      this.layoutFile = file;
      return self();
    }

    private Path resolveSoil() {
      return facet
          .materializationRoot()
          .map(soil -> Path.of(soil).toAbsolutePath().normalize())
          .orElseGet(this::freshTempDir);
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-dataplan-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the dataplan export dir", ex);
      }
    }
  }

  /**
   * Then: the export landed — {@code dataplan.json} exists and is non-empty — and, in a seed run,
   * the same layout is filed at {@link DataplanCoordinate#LAYOUT} under the run's parcel, where the
   * fabric delivery reads the dataset tree instead of a file. The layout is what the dataplan CODE
   * derives, never a file it publishes (fabric/plan's {@code dataplan.json} is the delivery of this
   * same derivation, for nix).
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Path layoutFile;

    @ExpectedScenarioState DataplanLayout layout;

    public Then the_layout_file_is_written() {
      if (!Files.exists(layoutFile)) {
        throw new DataplanExportError(layoutFile, DataplanExportError.Reason.MISSING);
      }
      final long size;
      try {
        size = Files.size(layoutFile);
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot stat the dataplan export " + layoutFile, ex);
      }
      if (size <= 0) {
        throw new DataplanExportError(layoutFile, DataplanExportError.Reason.EMPTY);
      }
      return self();
    }

    public Then the_layout_is_harvested(@Hidden Cellar cellar, @Hidden Optional<Parcel> parcel) {
      parcel.ifPresent(run -> cellar.store(run, DataplanCoordinate.LAYOUT, layout));
      return self();
    }
  }
}
