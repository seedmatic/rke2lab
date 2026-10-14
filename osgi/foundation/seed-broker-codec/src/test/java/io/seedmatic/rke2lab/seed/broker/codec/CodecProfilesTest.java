package io.seedmatic.rke2lab.seed.broker.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

  @Test
  void canonical_yaml_sorts_a_tree_which_has_no_declared_order() {
    final CanonicalCodec canonical = codec.canonical();
    assertEquals(
        """
        a:
          c: 3
          d: 4
        b: 1
        """,
        canonical.writeYaml(canonical.readJson("{\"b\":1,\"a\":{\"d\":4,\"c\":3}}")));
  }

  /**
   * The manifests are read by Go (sigs.k8s.io/yaml), a YAML 1.1 reader: unquoted, these strings
   * would come back as a boolean, a null, an octal number or nothing at all.
   */
  @Test
  void canonical_yaml_quotes_what_a_yaml_1_1_reader_would_not_read_as_the_same_string() {
    final CanonicalCodec canonical = codec.canonical();
    final Map<String, String> tricky = new LinkedHashMap<>();
    for (final String value : List.of("yes", "no", "on", "off", "null", "~", "", "0755")) {
      tricky.put("k" + tricky.size(), value);
    }
    final String yaml = canonical.writeYaml(tricky);

    assertEquals(
        """
        k0: "yes"
        k1: "no"
        k2: "on"
        k3: "off"
        k4: "null"
        k5: "~"
        k6: ""
        k7: "0755"
        """,
        yaml);
    assertEquals(tricky, canonical.readYaml(yaml, new TypeReference<Map<String, String>>() {}));
  }

  record Nullable(String kept, String dropped) {}

  @Test
  void canonical_drops_a_null_component_as_it_drops_an_empty_optional() {
    final CanonicalCodec canonical = codec.canonical();
    assertEquals("{\n  \"kept\": \"k\"\n}\n", canonical.writeJson(new Nullable("k", null)));
    assertEquals("kept: k\n", canonical.writeYaml(new Nullable("k", null)));
  }

  @Test
  void a_tree_follows_its_profile_and_binds_back_like_its_text() {
    final Peer peer = new Peer("k", Optional.empty());

    assertEquals("{\"known\":\"k\",\"absent\":null}", codec.reading().toTree(peer).toString());
    assertEquals("{\"known\":\"k\"}", codec.canonical().toTree(peer).toString());
    assertEquals(peer, codec.reading().convert(codec.canonical().toTree(peer), Peer.class));
  }

  @Test
  void yaml_documents_are_separated_by_a_line_and_read_back_in_order() {
    final CanonicalCodec canonical = codec.canonical();
    final String stream = canonical.writeYamlDocuments(List.of(new Known("a"), new Known("b")));

    assertEquals("known: a\n---\nknown: b\n", stream);
    assertEquals(
        List.of(new Known("a"), new Known("b")),
        canonical.readYamlDocuments(utf8(stream), Known.class).toList());
  }

  record Holder(Map<String, String> labels) {}

  /**
   * A stray separator yields an empty document; inside a stream it binds as null and is skipped.
   * The coercion is the stream's alone: read as one document, "" is still no map.
   */
  @Test
  void a_stray_separator_is_skipped_in_a_stream_and_only_there() {
    final ReadingCodec reading = codec.reading();

    assertEquals(
        List.of(Map.of("a", "1"), Map.of("b", "2")),
        reading
            .readYamlDocuments(
                utf8("a: \"1\"\n---\n---\nb: \"2\"\n"), new TypeReference<Map<String, String>>() {})
            .toList());
    assertEquals(
        List.of(new Holder(null)),
        reading.readYamlDocuments(utf8("labels: \"\"\n"), Holder.class).toList());
    assertThrows(
        UncheckedIOException.class, () -> reading.readYaml("labels: \"\"\n", Holder.class));
  }

  private static InputStream utf8(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
