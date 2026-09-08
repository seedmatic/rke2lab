package io.seedmatic.rke2lab.manifests.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.ShapeCoordinate;
import io.seedmatic.rke2lab.seed.broker.testkit.RefusingCellar;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shape verb manifests contributes: given a seed naming the {@code runbook} coordinate, the
 * reflector projects the JSON Schema of {@link ManifestsRunbookInput} OSGi-side and hands it back
 * as an opaque String, so a sower learns the payload shape holding no manifests class. Pins the
 * behaviors the sower relies on: the reflector serves the manifests shape meta-coordinate; the
 * projected schema is a non-empty object naming the composite {@code facets} concern (with the yaml
 * concern keys {@code publish} / {@code debug} nested under it); an unknown coordinate is refused.
 * Plus the invariant the whole config path rests on — a yaml-shaped map (string "true", nested
 * {enabled} wrappers) decodes into the wire-record verbatim.
 */
class ManifestsShapeReflectorTest {

  private static final SeedCodec CODEC = new SeedCodec();
  private static final ManifestsShapeReflector REFLECTOR = new ManifestsShapeReflector();

  @Test
  void serves_the_manifests_shape_meta_coordinate() {
    assertEquals(new ShapeCoordinate("manifests"), REFLECTOR.serves());
  }

  @Test
  void projects_a_schema_naming_the_yaml_concern_keys() {
    // A sower asks the shape of the runbook coordinate — the seed carries that coordinate's slug.
    final SeedEnvelope ask = SeedEnvelope.of(new RunbookCoordinate("manifests"), "{}");

    final SeedEnvelope reaped = REFLECTOR.handle(RefusingCellar.INSTANCE, ask);

    assertEquals(ShapeCoordinate.SLUG, reaped.coordinate());
    final var schema = CODEC.decode(reaped.payload());
    assertEquals("object", schema.path("type").asText());
    // Option A: the FACET is ONE composite 'facets' component at top level (the whole
    // rke2lab:manifests: subtree the host contributes verbatim, bound to one field); the yaml
    // concern keys (debug, delivery, workloadTargets) live under it.
    assertTrue(
        schema.path("properties").has("facets"), "schema must name the composite 'facets' concern");
    assertTrue(reaped.payload().contains("debug"), "schema must name the 'debug' concern");
  }

  @Test
  void refuses_a_coordinate_it_describes_no_shape_for() {
    final SeedEnvelope ask = SeedEnvelope.of(new ShapeCoordinate("manifests"), "{}");
    assertThrows(
        IllegalArgumentException.class, () -> REFLECTOR.handle(RefusingCellar.INSTANCE, ask));
  }

  @Test
  void decodes_a_yaml_shaped_map_into_the_wire_record_verbatim() {
    // The exact subtree a sower plucks from Pulumi.dev.yaml: {enabled} nesting for debug. The codec
    // (jackson) coerces it into the typed wire-record — the verbatim-pluck invariant. The host
    // copies this subtree blindly; all coercion is OSGi-side.
    final Map<String, Object> debug =
        Map.of(
            "mesh", Map.of("enabled", "true"),
            "networking", Map.of("enabled", "false"),
            "nriPlugins", Map.of("flox", Map.of("enabled", "true")));

    final ManifestsRunbookInput input =
        CODEC.fromMap(Map.of("facets", Map.of("debug", debug)), ManifestsRunbookInput.class);

    assertTrue(input.facets().debug().mesh().enabled());
    assertFalse(input.facets().debug().networking().enabled());
    assertTrue(input.facets().debug().nriPlugins().flox().enabled());
  }
}
