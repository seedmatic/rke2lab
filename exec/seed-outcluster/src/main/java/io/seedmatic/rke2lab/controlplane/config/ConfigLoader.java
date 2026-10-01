package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.pulumi.Config;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent, section-aware reader over operator input. It delegates the WHERE-from to a {@link
 * SectionReader} and only turns a section's node into typed values: {@code optional*} returns
 * {@link Optional}; {@code require*} records a domain-tagged symptom on absence and returns a
 * placeholder, so the caller throws once via {@link #diagnoseIfIncomplete()}. Dotted section names
 * ({@code "manifests.publish"}) walk into sub-objects.
 *
 * <p>Each source is a small single-role {@link SectionReader}, composed by the {@code of(…)}
 * factories: {@link PulumiSections} (the Pulumi stack config) and {@link NestedDocument} (an
 * in-memory or parsed-YAML document, also the {@code .secrets} side). Every source speaks {@link
 * JsonNode}, so navigation is type-safe — no raw-map casts, no {@code @SuppressWarnings}.
 *
 * <p>The typed {@code optional*}/{@code require*} accessors serve records still read
 * field-by-field; {@link #bind(Class, String)} is the deterministic {@code json → record} path
 * (Jackson) for the records that have migrated to schema-as-record, {@code .secrets} joined per
 * {@link SecretJoin}.
 */
public final class ConfigLoader {

  @FunctionalInterface
  public interface SectionReader {
    Optional<JsonNode> read(String section);
  }

  /**
   * The record-mapping mapper — the {@code json → record} half of the deterministic bind (its
   * inverse is {@link Facet#facetJson()}). {@link Jdk8Module} decodes {@code Optional}; unknown
   * keys are tolerated (they land in a record's {@code @JsonAnySetter} remainder, the blind part of
   * a facet). Empty-{@code Optional} omission on the way back out is a per-record
   * {@code @JsonInclude}.
   */
  static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new Jdk8Module())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final SectionReader sectionReader;
  private final NestedDocument secrets;
  private final List<String> missingKeys = new ArrayList<>();

  private ConfigLoader(SectionReader sectionReader, NestedDocument secrets) {
    this.sectionReader = sectionReader;
    this.secrets = secrets;
  }

  public static ConfigLoader of(SectionReader sectionReader) {
    return new ConfigLoader(sectionReader, NestedDocument.empty());
  }

  /**
   * Live over TWO documents: the Pulumi stack config AND the smudged {@code .secrets} file. The
   * config is read plainly; the {@code .secrets} document is held for {@link #bind(Class, String)}
   * to deep-merge on demand, directed by a bound record's {@link SecretJoin}. A missing/unreadable
   * {@code .secrets} yields an empty secret side (a survey without secrets falls to defaults).
   */
  public static ConfigLoader of(Config config, Path secretsFile) {
    return new ConfigLoader(new PulumiSections(config), NestedDocument.ofYaml(secretsFile));
  }

  /** Offline/test: a root map keyed by top-level section; dotted names walk into sub-maps. */
  public static ConfigLoader ofNestedRoot(Map<String, Object> root) {
    return new ConfigLoader(NestedDocument.ofRoot(root), NestedDocument.empty());
  }

  /**
   * Offline/test twin of {@link #of(Config, Path)}: TWO nested roots — config keyed by coordinate +
   * secrets keyed by provider. The secret side is joined per {@link SecretJoin} at {@link
   * #bind(Class, String)}, exactly as the live reader joins the Pulumi config with {@code
   * .secrets}.
   */
  public static ConfigLoader ofNestedRoots(
      Map<String, Object> configRoot, Map<String, Object> secretsRoot) {
    return new ConfigLoader(NestedDocument.ofRoot(configRoot), NestedDocument.ofRoot(secretsRoot));
  }

  /**
   * Deterministic bind — the role split {@code config loader → json → mapper → record}. Read {@code
   * section}'s config subtree, deep-merge the {@code .secrets} subtree the bound type's {@link
   * SecretJoin} names (if any), then Jackson-map the result onto {@code type}. The record IS the
   * schema: its typed components are the host's inputs, and a {@code @JsonAnySetter} remainder map
   * carries the blind part of a facet (the host names no domain vocabulary). Its exact inverse is
   * {@link Facet#facetJson()} ({@code record → json}).
   */
  public <T> T bind(Class<T> type, String section) {
    final ObjectNode merged =
        sectionReader
            .read(section)
            .map(node -> (ObjectNode) node.deepCopy())
            .orElseGet(JSON::createObjectNode);
    final SecretJoin join = type.getAnnotation(SecretJoin.class);
    if (join != null) {
      secrets.read(join.from()).ifPresent(secret -> deepMerge(merged, secret));
    }
    return JSON.convertValue(merged, type);
  }

  /**
   * The {@code record → json} half of the bind — the inverse of {@link #bind(Class, String)}, used
   * by a {@link Facet} to re-serialise itself into the payload it contributes. Shares the one
   * mapper so the round-trip is symmetric.
   */
  static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("failed to serialise a config facet: " + value, ex);
    }
  }

  /** Deep-merge {@code overlay} into {@code base}; overlay leaves win (sops is the authority). */
  private static void deepMerge(ObjectNode base, JsonNode overlay) {
    overlay
        .properties()
        .forEach(
            entry -> {
              final JsonNode existing = base.get(entry.getKey());
              if (existing instanceof ObjectNode existingObject
                  && entry.getValue() instanceof ObjectNode) {
                deepMerge(existingObject, entry.getValue());
              } else {
                base.set(entry.getKey(), entry.getValue());
              }
            });
  }

  // --- optional* : empty when absent/blank, no defaults here ---

  public Optional<String> optional(String section, String key) {
    return rawValue(section, key).map(String::trim).filter(value -> !value.isBlank());
  }

  public Optional<Path> optionalPath(String section, String key) {
    return optional(section, key)
        .map(ConfigLoader::expandHome)
        .map(value -> Path.of(value).toAbsolutePath().normalize());
  }

  // Expand a leading ~ or $HOME/${HOME} to the operator's home so path config
  // (e.g. incus.configDir) stays portable across hosts whose home lives at a
  // different absolute root (/Users/<user> on one Mac, /Volumes/user-home on
  // another). Absolute and relative values pass through untouched.
  private static String expandHome(String value) {
    final String home = System.getProperty("user.home");
    if (home == null || home.isBlank()) {
      return value;
    }
    if (value.equals("~") || value.startsWith("~/")) {
      return home + value.substring(1);
    }
    for (final String prefix : new String[] {"${HOME}", "$HOME"}) {
      if (value.startsWith(prefix)) {
        return home + value.substring(prefix.length());
      }
    }
    return value;
  }

  public Optional<URI> optionalUri(String section, String key) {
    return optional(section, key).map(URI::create);
  }

  public Optional<Boolean> optionalBoolean(String section, String key) {
    return optional(section, key).map(this::parseBoolean);
  }

  public Optional<Integer> optionalInt(String section, String key) {
    return optional(section, key).map(value -> Integer.parseInt(value.trim()));
  }

  public Optional<Duration> optionalDuration(String section, String key) {
    return optional(section, key).map(value -> Duration.parse(value.trim()));
  }

  /**
   * Reads a nested string→string sub-map (e.g. {@code policy.readiness.override} = {@code
   * {systemd-adapter: warning}}). Empty map when the key is absent or not an object.
   */
  public Map<String, String> stringMap(String section, String key) {
    final Optional<JsonNode> node =
        sectionReader.read(section).map(subtree -> subtree.path(key)).filter(JsonNode::isObject);
    if (node.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, String> result = new LinkedHashMap<>();
    node.get().properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    return result;
  }

  /**
   * The child keys of {@code section} whose value is itself an object — e.g. the per-checkpoint
   * blocks under {@code readiness} ({@code systemd-adapter}, {@code cluster-readiness}), as opposed
   * to the section's scalar knobs ({@code connectTimeout}, {@code timeout}). Empty when the section
   * is absent. Lets a caller discover a dynamic set of sub-sections without naming each.
   */
  public List<String> objectKeys(String section) {
    final Optional<JsonNode> node = sectionReader.read(section);
    if (node.isEmpty()) {
      return List.of();
    }
    final List<String> keys = new ArrayList<>();
    node.get()
        .properties()
        .forEach(
            entry -> {
              if (entry.getValue().isObject()) {
                keys.add(entry.getKey());
              }
            });
    return keys;
  }

  /**
   * Reads a nested string list (e.g. {@code entryGate.cleanWorktree.tolerated} = {@code
   * [".secrets", "Pulumi.dev.yaml"]}). Empty list when the key is absent or not an array.
   */
  public List<String> stringList(String section, String key) {
    final Optional<JsonNode> node =
        sectionReader.read(section).map(subtree -> subtree.path(key)).filter(JsonNode::isArray);
    if (node.isEmpty()) {
      return List.of();
    }
    final List<String> result = new ArrayList<>();
    node.get().forEach(element -> result.add(element.asText()));
    return result;
  }

  // --- require* : accumulate on absence, return placeholder ---

  public Path requirePath(String section, String key) {
    final Optional<Path> value = optionalPath(section, key);
    if (value.isEmpty()) {
      missingKeys.add(section + "." + key);
      return Path.of("/__missing__/" + section + "/" + key);
    }
    return value.get();
  }

  public String require(String section, String key) {
    final Optional<String> value = optional(section, key);
    if (value.isEmpty()) {
      missingKeys.add(section + "." + key);
      return "";
    }
    return value.get();
  }

  public List<String> missingKeys() {
    return List.copyOf(missingKeys);
  }

  public void diagnoseIfIncomplete() {
    if (!missingKeys.isEmpty()) {
      throw new MissingRequiredConfiguration(missingKeys);
    }
  }

  private Optional<String> rawValue(String section, String key) {
    return sectionReader
        .read(section)
        .map(node -> node.path(key))
        .filter(value -> !value.isMissingNode() && !value.isNull())
        .map(JsonNode::asText);
  }

  private boolean parseBoolean(String raw) {
    return switch (raw.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("Invalid boolean: " + raw);
    };
  }

  /**
   * A nested {@link JsonNode} document (parsed YAML or an in-memory config root), read by dotted
   * section path — the walk that resolves {@code "manifests.publish"} to its sub-object, shared by
   * every source. Owns the one mapper that turns a {@code .secrets} file or a raw config value into
   * a tree; every other source delegates its navigation here.
   */
  private record NestedDocument(JsonNode root) implements SectionReader {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    /** Parse a smudged {@code .secrets}-style YAML file, or an empty document when unreadable. */
    static NestedDocument ofYaml(Path file) {
      if (!Files.isReadable(file)) {
        return new NestedDocument(MAPPER.createObjectNode());
      }
      try {
        final JsonNode root = MAPPER.readTree(file.toFile());
        return new NestedDocument(root == null ? MAPPER.createObjectNode() : root);
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to read the secrets file at " + file, ex);
      }
    }

    /** Wrap an already-parsed section→submap root (the offline fixtures). */
    static NestedDocument ofRoot(Map<String, Object> root) {
      return new NestedDocument(MAPPER.valueToTree(root));
    }

    /** An empty document — the secret side of a loader built with no {@code .secrets}. */
    static NestedDocument empty() {
      return new NestedDocument(MAPPER.createObjectNode());
    }

    /** Wrap one named section's raw content (a Pulumi {@code getObject} result) as a document. */
    static NestedDocument ofSection(String name, Object content) {
      final ObjectNode root = MAPPER.createObjectNode();
      root.set(name, MAPPER.valueToTree(content));
      return new NestedDocument(root);
    }

    @Override
    public Optional<JsonNode> read(String section) {
      JsonNode current = root;
      for (String part : section.split("\\.")) {
        current = current.path(part);
      }
      return current.isObject() ? Optional.of(current) : Optional.empty();
    }
  }

  /**
   * A {@link SectionReader} over {@link com.pulumi.Config}: fetch the top-level section via {@code
   * getObject}, then let a {@link NestedDocument} do the dotted descent — no walk logic of its own.
   */
  private record PulumiSections(Config config) implements SectionReader {

    @Override
    public Optional<JsonNode> read(String section) {
      final String top = section.split("\\.")[0];
      return config
          .getObject(top, Object.class)
          .flatMap(content -> NestedDocument.ofSection(top, content).read(section));
    }
  }
}
