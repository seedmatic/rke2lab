package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the stable versions out of each kind of upstream answer: a Helm repository's {@code
 * index.yaml}, an OCI registry's tags list. A delegate the bumper holds, the GitHub releases being
 * read by the bumper itself.
 *
 * <p>A Helm index is read as TEXT, line by line, not parsed: loft's is 2.99 MB (measured
 * 2026-10-28), at SnakeYAML's 3 MB code-point limit, and only one chart's {@code version} keys are
 * needed. Its shape is fixed (measured on loft's and openebs' indexes): {@code entries:} at column
 * 0, each chart key at two spaces, each entry's {@code - } at two and its keys at four, nested
 * blocks (dependencies, maintainers) deeper, with {@code version:} keys of their own.
 */
final class UpstreamVersions {

  // A version with any suffix (-rc.1, -alpha.7, -develop, -prerelease, +build) is not stable.
  private static final Pattern STABLE = Pattern.compile("^v?\\d+\\.\\d+(\\.\\d+)?$");

  private static final Pattern ENTRY_KEY = Pattern.compile("^  ([^\\s:#][^:]*):\\s*$");

  // An entry's own `version:` key: exactly four spaces deep, the list marker at two. Deeper ones
  // belong to an entry's dependencies (a dependency's version), not the chart's.
  private static final Pattern VERSION = Pattern.compile("^ {4}version:\\s*\"?([^\"\\s]+)\"?\\s*$");

  private final SeedCodec codec = new SeedCodec();

  boolean isStable(final String version) {
    return STABLE.matcher(version).matches();
  }

  /** The stable versions of {@code chart} in a Helm {@code index.yaml}, in index order. */
  List<String> helmIndex(final String indexYaml, final String chart) {
    final List<String> versions = new ArrayList<>();
    boolean inEntries = false;
    boolean inChart = false;
    for (final String line : indexYaml.split("\n", -1)) {
      if (!line.startsWith(" ") && !line.isBlank()) {
        inEntries = line.startsWith("entries:");
        inChart = false;
        continue;
      }
      if (!inEntries) {
        continue;
      }
      final Matcher key = ENTRY_KEY.matcher(line);
      if (key.matches()) {
        inChart = key.group(1).equals(chart);
        continue;
      }
      if (inChart) {
        final Matcher version = VERSION.matcher(line);
        if (version.matches() && isStable(version.group(1))) {
          versions.add(version.group(1));
        }
      }
    }
    return List.copyOf(versions);
  }

  /** The stable tags of an OCI registry's {@code /v2/<repository>/tags/list} answer. */
  List<String> ociTags(final String tagsListJson) {
    final List<String> versions = new ArrayList<>();
    final JsonNode tags = codec.reading().readJson(tagsListJson).path("tags");
    for (final JsonNode tag : tags) {
      if (isStable(tag.asText())) {
        versions.add(tag.asText());
      }
    }
    return List.copyOf(versions);
  }
}
