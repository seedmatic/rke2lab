package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioCaseModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import com.tngtech.jgiven.report.model.Tag;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The decisive isolation: does a scenario runbook survive the host-crossing round-trip ({@code
 * ScenarioJsonWriter} → {@code ScenarioJsonReader}, what {@code ScenarioGraft.rebuild} does) when
 * it carries a CELLAR-ENTRY TAG whose value is deeply-nested escaped JSON — the one factor present
 * in a live model (a real {@code ScenarioCellar.store} posts it) yet absent from every green test
 * (they use a RecordingCellar / no store). If the rebuilt model loses its scenario, this is the
 * live "no scenario to graft".
 */
class RunbookTagRoundTripTest {

  @Test
  void a_runbook_with_a_nested_json_cellar_tag_survives_the_graft_round_trip() throws Exception {
    final ReportModel model = new ReportModel();
    model.setClassName("io.seedmatic.rke2lab.incus.bdd.IncusProvisionScenario");
    final ScenarioModel scenario = new ScenarioModel();
    scenario.setClassName(model.getClassName());
    scenario.setTestMethodName("the_instance_is_provisioned");
    scenario.addCase(new ScenarioCaseModel());
    model.addScenarioModel(scenario);

    // The cellar-entry tag exactly as ScenarioCellar.store posts it: value is the codec-encoded
    // entry — a JSON document with an envelope whose payload is ITSELF escaped JSON (nested
    // quotes).
    final String nestedJsonValue =
        "{\"parcel\":{\"project\":\"rke2lab\",\"stack\":\"dev\"},"
            + "\"envelope\":{\"domain\":\"incus\",\"coordinate\":\"incus-provision\","
            + "\"payload\":\"{\\\"dryRun\\\":true,\\\"desiredCount\\\":12}\"}}";
    final Tag tag = new Tag("cellar-entry", nestedJsonValue);
    tag.setType("cellar-entry");
    model.addTag(tag);

    assertEquals(1, model.getScenarios().size(), "the model starts with its scenario");

    final String json = new ScenarioJsonWriter(model).toString();
    final File tmp = File.createTempFile("tag-round-trip", ".json");
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), json);
    final ReportModel rebuilt = new ScenarioJsonReader().apply(tmp);

    assertEquals(
        1,
        rebuilt.getScenarios().size(),
        "the runbook still carries its scenario after the round-trip WITH the nested-json cellar"
            + " tag — if this is 0, the tag corrupts the graft (the live 'no scenario to graft')");
  }
}
