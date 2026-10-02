package io.seedmatic.rke2lab.controlplane.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared test DSL for operator configuration: the single builder of config inputs and their
 * derivations, replacing the per-test {@code Map.of(...)} fixtures that were duplicated across the
 * suite. Plain-JUnit mechanics tests and JGiven {@code Given} stages both consume it.
 *
 * <p>Use this when a test merely <em>needs</em> configuration as a fixture dependency (e.g. a
 * specialist that takes a {@link BootstrapConfig}). When a scenario's <em>subject</em> is config
 * behaviour — ready vs which mandatory inputs are missing — delegate to the config BDD gate {@code
 * ConfigEntryGate} instead of asserting here, so config behaviour lives in exactly one place.
 */
public final class OperatorConfiguration {

  private final Map<String, Map<String, Object>> sections;

  private OperatorConfiguration(Map<String, Map<String, Object>> sections) {
    this.sections = sections;
  }

  /** The loader adapter shared by every config test: a section lookup over a plain section map. */
  public static ConfigLoader loaderOf(Map<String, Map<String, Object>> sections) {
    final Map<String, Object> root = new LinkedHashMap<>(sections);
    return ConfigLoader.ofNestedRoot(root);
  }

  /** No sections at all — every mandatory input absent (the missing-inputs starting point). */
  public static OperatorConfiguration empty() {
    return new OperatorConfiguration(new LinkedHashMap<>());
  }

  /** The single mandatory input with the canonical path the resolution tests assert. */
  public static OperatorConfiguration mandatory() {
    return empty().with("incus", "configDir", "/Users/nxmatic/.config/incus");
  }

  /**
   * Mandatory plus the common cross-cutting inputs (cluster name, incus project, dbus endpoint).
   */
  public static OperatorConfiguration full() {
    return mandatory()
        .with("cluster", "host", "bioskop")
        .with("cluster", "role", "mgmt")
        .with("incus", "project", "rke2lab")
        .with("systemd", "dbusHost", "bioskop-master")
        .with("systemd", "dbusPort", "12434");
  }

  /** A copy with {@code section.key = value} added or overwritten. */
  public OperatorConfiguration with(String section, String key, Object value) {
    final Map<String, Map<String, Object>> copy = deepCopy();
    copy.computeIfAbsent(section, ignored -> new LinkedHashMap<>()).put(key, value);
    return new OperatorConfiguration(copy);
  }

  /** A copy with the dotted {@code section.key} removed (and the section dropped if it empties). */
  public OperatorConfiguration without(String dottedKey) {
    final String[] parts = dottedKey.split("\\.", 2);
    final Map<String, Map<String, Object>> copy = deepCopy();
    final Map<String, Object> section = copy.get(parts[0]);
    if (section != null && parts.length == 2) {
      section.remove(parts[1]);
      if (section.isEmpty()) {
        copy.remove(parts[0]);
      }
    }
    return new OperatorConfiguration(copy);
  }

  public Map<String, Map<String, Object>> sections() {
    return deepCopy();
  }

  public ConfigLoader asLoader() {
    return loaderOf(sections);
  }

  public Rke2labConfig asDto() {
    return Rke2labConfig.from(asLoader());
  }

  public BootstrapConfig asBootstrapConfig() {
    return BootstrapConfig.from(asDto());
  }

  private Map<String, Map<String, Object>> deepCopy() {
    final Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
    sections.forEach((section, entries) -> copy.put(section, new LinkedHashMap<>(entries)));
    return copy;
  }
}
