package io.seedmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodecProfilesTest {

  private final SeedCodec codec = new SeedCodec();

  // Components deliberately NOT in alphabetical order: the record's own order must survive.
  record Written(
      String zeta,
      Map<String, Object> labels,
      List<Integer> ports,
      Optional<String> absent,
      List<String> none,
      Map<String, String> empty) {}

  private static Written written() {
    final Map<String, Object> labels = new LinkedHashMap<>();
    labels.put("z", 2);
    labels.put("a", 1);
    return new Written("x", labels, List.of(1, 2), Optional.empty(), List.of(), Map.of());
  }

  record Scalars(String version, String flag, String text) {}

  record Known(String known) {}

  record Peer(String known, Optional<String> absent) {}

  @Test
  void canonical_json_keeps_record_order_sorts_maps_and_ends_with_a_newline() {
    assertEquals(
        """
        {
          "zeta": "x",
          "labels": {
            "a": 1,
            "z": 2
          },
          "ports": [
            1,
            2
          ],
          "none": [],
          "empty": {}
        }
        """,
        codec.canonical().writeJson(written()));
  }

  @Test
  void canonical_json_sorts_a_tree_which_has_no_declared_order() {
    final CanonicalCodec canonical = codec.canonical();
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
        canonical.writeJson(canonical.readJson("{\"b\":1,\"a\":{\"d\":4,\"c\":3}}")));
  }

  @Test
  void canonical_yaml_keeps_record_order_sorts_maps_and_ends_with_a_newline() {
    assertEquals(
        """
        zeta: x
        labels:
          a: 1
          z: 2
        ports:
          - 1
          - 2
        none: []
        empty: {}
        """,
        codec.canonical().writeYaml(written()));
  }

  @Test
  void canonical_yaml_quotes_only_what_would_not_read_back_as_the_same_string() {
    final CanonicalCodec canonical = codec.canonical();
    final Scalars scalars = new Scalars("1.0", "true", "line one\nline two\n");
    final String yaml = canonical.writeYaml(scalars);

    assertEquals(
        """
        version: "1.0"
        flag: "true"
        text: |
          line one
          line two
        """,
        yaml);
    assertEquals(scalars, canonical.readYaml(yaml, Scalars.class));
  }

  @Test
  void the_same_value_always_yields_the_same_bytes() {
    final Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("a", 1);
    reversed.put("z", 2);
    final Written other =
        new Written("x", reversed, List.of(1, 2), Optional.empty(), List.of(), Map.of());

    assertEquals(codec.canonical().writeJson(written()), codec.canonical().writeJson(other));
    assertEquals(codec.canonical().writeYaml(written()), codec.canonical().writeYaml(other));
  }

  @Test
  void reading_accepts_an_unknown_field() {
    final ReadingCodec reading = codec.reading();
    assertEquals(new Known("k"), reading.readJson("{\"known\":\"k\",\"added\":1}", Known.class));
    assertEquals(new Known("k"), reading.readYaml("known: k\nadded: 1\n", Known.class));
  }

  @Test
  void reading_parses_a_yaml_document_above_snakeyaml_s_default_limit() {
    final String big = "x".repeat(4 * 1024 * 1024);
    assertEquals(big, codec.reading().readYaml("known: " + big + "\n", Known.class).known());
  }

  @Test
  void wire_accepts_an_unknown_field() {
    assertEquals(
        new Known("k"),
        codec.wire().readJson("{\"known\":\"k\",\"added\":{\"x\":[1]}}", Known.class));
  }

  @Test
  void wire_writes_compact_json_and_keeps_the_keys_the_peer_may_expect() {
    assertEquals(
        "{\"known\":\"k\",\"absent\":null}",
        codec.wire().writeJson(new Peer("k", Optional.empty())));
  }
}
