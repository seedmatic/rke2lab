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
 * needed. Its INDENTS are not fixed: loft's and openebs' put each chart key at two spaces and an
 * entry's keys at four, tailscale's at four and ten (measured 2026-10-28). So they are read off the
 * index: a chart key sits at the first indent under {@code entries:}, an entry's keys two past its
 * {@code - }, and only a {@code version:} at exactly that indent is the chart's; nested blocks
 * (dependencies, maintainers) are deeper, with {@code version:} keys of their own.
 */
final class UpstreamVersions {

  // A version with any suffix (-rc.1, -alpha.7, -develop, -prerelease, +build) is not stable.
  private static final Pattern STABLE = Pattern.compile("^v?\\d+\\.\\d+(\\.\\d+)?$");

  private static final Pattern CHART_KEY = Pattern.compile("([^\\s:#-][^:]*):\\s*");

  private static final Pattern VERSION = Pattern.compile("version:\\s*\"?([^\"\\s]+)\"?\\s*");

  private final SeedCodec codec = new SeedCodec();

  boolean isStable(final String version) {
    return STABLE.matcher(version).matches();
  }

  /** The stable versions of {@code chart} in a Helm {@code index.yaml}, in index order. */
  List<String> helmIndex(final String indexYaml, final String chart) {
    final List<String> versions = new ArrayList<>();
    boolean inEntries = false;
    boolean inChart = false;
    int chartIndent = -1;
    int entryKeyIndent = -1;
    for (final String line : indexYaml.split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      final int indent = line.length() - line.stripLeading().length();
      final String content = line.substring(indent);
      if (indent == 0) {
        inEntries = content.startsWith("entries:");
        inChart = false;
        continue;
      }
      if (!inEntries) {
        continue;
      }
      if (chartIndent < 0) {
        chartIndent = indent;
      }
      final Matcher key = CHART_KEY.matcher(content);
      if (indent == chartIndent && key.matches()) {
        inChart = key.group(1).equals(chart);
        entryKeyIndent = -1;
        continue;
      }
      if (!inChart) {
        continue;
      }
      final boolean listed = content.startsWith("- ");
      final int keyIndent = listed ? indent + 2 : indent;
      if (entryKeyIndent < 0 && listed) {
        entryKeyIndent = keyIndent;
      }
      final Matcher version = VERSION.matcher(listed ? content.substring(2) : content);
      if (keyIndent == entryKeyIndent && version.matches() && isStable(version.group(1))) {
        versions.add(version.group(1));
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
