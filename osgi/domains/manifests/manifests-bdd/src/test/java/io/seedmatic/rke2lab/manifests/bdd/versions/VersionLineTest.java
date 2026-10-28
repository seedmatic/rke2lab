package io.seedmatic.rke2lab.manifests.bdd.versions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * One report row as the common version contract's JSON line (nix-flake-commons
 * nix/versions/schema.json, copied verbatim from 6ae7788 into the test resources until the input is
 * relocked): the eight fields, the status, the level.
 */
class VersionLineTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Schema schema() throws Exception {
    try (InputStream in = VersionLineTest.class.getResourceAsStream("/versions/schema.json")) {
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
    }
  }

  private static JsonNode line(final VersionReport row) throws Exception {
    return JSON.readTree(row.jsonLine());
  }

  private static VersionReport row(
      final Component component, final String pinned, final Optional<String> latest) {
    return new VersionReport(
        component,
        pinned,
        latest.flatMap(SemanticVersion::parse),
        Optional.empty(),
        latest.isEmpty() ? "unreachable" : "",
        latest);
  }

  @Test
  void everyLineSatisfiesTheCommonSchema() throws Exception {
    final Schema schema = schema();
    for (final VersionReport r :
        List.of(
            row(Component.KUBE_VIP, "v0.9.2", Optional.of("v1.3.0")),
            row(Component.VCLUSTER_CHART, "0.37.4", Optional.of("0.37.4")),
            row(Component.OPENEBS_ZFS_CHART, "2.8.0", Optional.of("2.11.2")),
            row(Component.GITHUB_TOKEN_MANAGER, "1.6.1", Optional.empty()))) {
      assertEquals(List.of(), schema.validate(line(r)), r.component().slug());
    }
  }

  @Test
  void aMajorBehindIsLevelMajorAndStatusBehind() throws Exception {
    final JsonNode line = line(row(Component.KUBE_VIP, "v0.9.2", Optional.of("v1.3.0")));
    assertEquals("clusters", line.get("world").asText());
    assertEquals("kubeVip", line.get("name").asText());
    assertEquals("v0.9.2", line.get("pinned").asText());
    assertEquals("v1.3.0", line.get("latest").asText());
    assertEquals("major", line.get("level").asText());
    assertEquals("behind", line.get("status").asText());
    assertEquals("github:kube-vip/kube-vip", line.get("upstream").asText());
    assertTrue(line.get("source").asText().startsWith("rke2lab:"), line.get("source").asText());
  }

  @Test
  void theLevelIsTheLargestPartThatDiffers() throws Exception {
    assertEquals(
        "minor",
        line(row(Component.OPENEBS_ZFS_CHART, "2.8.0", Optional.of("2.11.2")))
            .get("level")
            .asText());
    assertEquals(
        "micro",
        line(row(Component.VCLUSTER_CHART, "0.37.2", Optional.of("0.37.4"))).get("level").asText());
  }

  @Test
  void aCurrentPinHasNoLevel() throws Exception {
    final JsonNode line = line(row(Component.VCLUSTER_CHART, "0.37.4", Optional.of("0.37.4")));
    assertEquals("current", line.get("status").asText());
    assertTrue(line.get("level").isNull());
    assertEquals("helm:https://charts.loft.sh#vcluster", line.get("upstream").asText());
  }

  @Test
  void anUnreachableUpstreamIsUnknownWithNullLatest() throws Exception {
    final JsonNode line = line(row(Component.GITHUB_TOKEN_MANAGER, "1.6.1", Optional.empty()));
    assertEquals("unknown", line.get("status").asText());
    assertTrue(line.get("latest").isNull());
    assertTrue(line.get("level").isNull());
    assertEquals("oci:ghcr.io/isometry/charts/github-token-manager", line.get("upstream").asText());
  }

  @Test
  void aPinNewerThanAnyStableIsAhead() throws Exception {
    final JsonNode line = line(row(Component.KUBE_VIP, "v1.4.0", Optional.of("v1.3.0")));
    assertEquals("ahead", line.get("status").asText());
  }
}
