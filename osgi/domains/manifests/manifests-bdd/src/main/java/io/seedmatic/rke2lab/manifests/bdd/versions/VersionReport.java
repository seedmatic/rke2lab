package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.ingress.ComponentSource;
import java.util.Optional;

/**
 * One row of the {@code versions} bumper report — the DISCOVERED state of a single {@link
 * Component}: what it is pinned to, what its upstream latest release is, and — under the authorised
 * {@link io.seedmatic.rke2lab.manifests.ingress.BumpLevel} gate — the highest release it may bump
 * to right now. Keyed by the typed {@link Component} (not a loose id); discovery is computed
 * OSGi-side by the bumper, never by the passive enum.
 *
 * @param component the component this row reports on
 * @param currentPin the component's baseline pin ({@link Component#defaultVersion()}), verbatim
 * @param upstreamLatest the highest semver release found upstream, empty if unreachable / no source
 * @param allowedTarget the highest release reachable within the level gate and strictly above the
 *     current pin, empty when already current or when nothing within the gate is newer
 * @param note a human note when there is no clean numeric answer (no source, unreachable,
 *     unparseable pin); empty otherwise
 * @param latestRaw {@code upstreamLatest} as upstream spells it (its leading {@code v} or not)
 */
public record VersionReport(
    Component component,
    String currentPin,
    Optional<SemanticVersion> upstreamLatest,
    Optional<SemanticVersion> allowedTarget,
    String note,
    Optional<String> latestRaw) {

  /** Where every pin lives: the file a bump edits, as the version contract's {@code source}. */
  static final String PIN_SOURCE =
      "rke2lab:osgi/domains/manifests/manifests-ingress-contract/src/main/java/io/seedmatic/"
          + "rke2lab/manifests/ingress/Component.java";

  public VersionReport {
    note = note == null ? "" : note;
  }

  static VersionReport manual(
      final Component component, final String currentPin, final String why) {
    return new VersionReport(
        component, currentPin, Optional.empty(), Optional.empty(), why, Optional.empty());
  }

  /** True when a bump within the authorised gate is available. */
  public boolean bumpAvailable() {
    return allowedTarget.isPresent();
  }

  /** True when a newer release exists upstream but is held back by the level gate. */
  public boolean heldByGate() {
    return upstreamLatest.isPresent()
        && allowedTarget.isEmpty()
        && SemanticVersion.parse(currentPin)
            .map(current -> upstreamLatest.get().compareTo(current) > 0)
            .orElse(false);
  }

  /** The version contract's {@code status}: current, behind, ahead or unknown. */
  String status() {
    final Optional<SemanticVersion> pinned = SemanticVersion.parse(currentPin);
    if (pinned.isEmpty() || upstreamLatest.isEmpty()) {
      return "unknown";
    }
    final int order = pinned.orElseThrow().compareTo(upstreamLatest.orElseThrow());
    return order == 0 ? "current" : order < 0 ? "behind" : "ahead";
  }

  /** The version contract's {@code level}: the largest part that differs, empty when current. */
  Optional<String> level() {
    final Optional<SemanticVersion> pinned = SemanticVersion.parse(currentPin);
    if (pinned.isEmpty() || upstreamLatest.isEmpty()) {
      return Optional.empty();
    }
    final SemanticVersion a = pinned.orElseThrow();
    final SemanticVersion b = upstreamLatest.orElseThrow();
    if (a.major() != b.major()) {
      return Optional.of("major");
    }
    if (a.minor() != b.minor()) {
      return Optional.of("minor");
    }
    return a.patch() != b.patch() ? Optional.of("micro") : Optional.empty();
  }

  /**
   * This row as one line of the common version contract (nix-flake-commons {@code
   * nix/versions/schema.json}, agreed with the nix world): its eight fields, in their order.
   */
  public String jsonLine() {
    final JsonNodeFactory f = JsonNodeFactory.instance;
    final ObjectNode line = f.objectNode();
    line.put("world", "clusters");
    line.put("name", component.slug());
    line.put("source", PIN_SOURCE);
    line.put("pinned", currentPin);
    line.set("latest", latestRaw.<JsonNode>map(f::textNode).orElse(f.nullNode()));
    line.set("level", level().<JsonNode>map(f::textNode).orElse(f.nullNode()));
    line.put("status", status());
    line.put(
        "upstream",
        component
            .source()
            .map(ComponentSource::upstream)
            .map(ComponentSource.Upstream::locator)
            .orElse("none"));
    return line.toString();
  }
}
