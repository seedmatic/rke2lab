package io.seedmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.ReadingCodec;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads the history directory of a Pulumi file-backend stack and provides access to historical
 * update entries.
 *
 * <p>Each update produces a pair of files:
 *
 * <ul>
 *   <li>{@code <stack>-<timestamp>.history.json} — the update summary (version, startTime, result)
 *   <li>{@code <stack>-<timestamp>.checkpoint.json} — the state snapshot after that update
 * </ul>
 *
 * <p>This class parses the history files, pairs each with its sibling checkpoint, and exposes them
 * as {@link Entry} instances sorted by deployment time — the nanosecond timestamp the backend
 * stamps into each file name, which is the strictly-monotonic deployment sequence (the JSON {@code
 * version} is left at 0 by the file backend, so it cannot order the timeline).
 *
 * <p>Error contract:
 *
 * <ul>
 *   <li>Absent history directory → {@link #entries()} returns an empty list (legitimate
 *       nothing-here).
 *   <li>Directory listing I/O failure → throws {@link StackAccessException} (retryable).
 *   <li>Malformed history file → throws {@link StackContentException} (never retryable).
 * </ul>
 */
public record StackHistory(Path historyDir) {

  private static final ReadingCodec READING = new SeedCodec().reading();

  /** Factory — resolves the history dir from the backend root via the canonical layout. */
  public static StackHistory of(Path backendDir, String project, String stack) {
    return new StackHistory(PulumiBackendLayout.historyDir(backendDir, project, stack));
  }

  /**
   * Reads all {@code *.history.json} files in the history directory, parses each, pairs it with its
   * sibling checkpoint file, and returns the entries sorted by deployment time (the file name's
   * nanosecond stamp).
   *
   * <p>If the history directory does not exist, returns an empty list. If the directory stream
   * fails (I/O), throws {@link StackAccessException}. If a history file is malformed (invalid JSON,
   * missing required fields, or a name without a {@code -<nanos>} suffix), throws {@link
   * StackContentException}.
   */
  public List<Entry> entries() throws StackAccessException, StackContentException {
    if (Files.notExists(historyDir)) {
      return List.of();
    }

    List<Entry> entries = new ArrayList<>();

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "*.history.json")) {
      for (Path historyFile : stream) {
        entries.add(parseEntry(historyFile));
      }
    } catch (IOException e) {
      // Directory listing I/O failure — may succeed on retry
      throw new StackAccessException(historyDir, e);
    }

    entries.sort(Comparator.comparing(Entry::when));
    return entries;
  }

  /** Returns the checkpoint for the given entry if the checkpoint file exists. */
  public Optional<StackCheckpoint> checkpointOf(Entry entry) {
    if (Files.exists(entry.file())) {
      return Optional.of(StackCheckpoint.of(entry.file()));
    }
    return Optional.empty();
  }

  private Entry parseEntry(Path historyFile) throws StackAccessException, StackContentException {
    // The deployment instant is the nanosecond the backend stamps into the file name
    // (<stack>-<nanos>.history.json) — the strictly-monotonic deployment sequence. The JSON
    // startTime is only second-resolution and the version is left at 0, so neither can order the
    // timeline; the file name is the authority.
    Instant when = deploymentInstant(historyFile);
    try {
      JsonNode root = READING.readJson(Files.readString(historyFile));

      JsonNode versionNode = root.get("version");
      if (versionNode == null || !versionNode.canConvertToInt()) {
        throw new StackContentException(
            historyFile, new IllegalStateException("missing or invalid version field"));
      }
      int version = versionNode.asInt();

      JsonNode resultNode = root.get("result");
      if (resultNode == null || !resultNode.isTextual()) {
        throw new StackContentException(
            historyFile, new IllegalStateException("missing or invalid result field"));
      }
      String result = resultNode.asText();

      Path checkpointFile =
          historyFile.resolveSibling(
              historyFile.getFileName().toString().replace(".history.json", ".checkpoint.json"));

      return new Entry(version, when, result, checkpointFile);

    } catch (UncheckedIOException e) {
      // Malformed JSON — content is broken, never retry
      throw new StackContentException(historyFile, e);
    } catch (IOException e) {
      // I/O error reading the file — may succeed on retry
      throw new StackAccessException(historyFile, e);
    }
  }

  /**
   * Parses the nanosecond deployment timestamp from a {@code <stack>-<nanos>.history.json} name.
   */
  private static Instant deploymentInstant(Path historyFile) throws StackContentException {
    String name = historyFile.getFileName().toString().replace(".history.json", "");
    int dash = name.lastIndexOf('-');
    if (dash < 0 || dash == name.length() - 1) {
      throw new StackContentException(
          historyFile, new IllegalStateException("history file name has no -<nanos> suffix"));
    }
    try {
      long nanos = Long.parseLong(name.substring(dash + 1));
      return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    } catch (NumberFormatException e) {
      throw new StackContentException(historyFile, e);
    }
  }

  /**
   * A single update entry from the stack's history.
   *
   * @param version the update sequence number (0 on the file backend — not an ordering key)
   * @param when the deployment instant, from the file name's nanosecond stamp; orders the timeline
   * @param result the result of the update (e.g., "succeeded", "failed")
   * @param file the path to the paired checkpoint file
   */
  public record Entry(int version, Instant when, String result, Path file) {

    public boolean succeeded() {
      return "succeeded".equalsIgnoreCase(result);
    }
  }
}
