package io.seedmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulumi.automation.StackDeployment;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reads a Pulumi file-backend checkpoint file and synthesizes a {@link StackSnapshot} from it.
 *
 * <p>A checkpoint file has the shape {@code {version: <int>, checkpoint: {latest: <deployment>}}}.
 * This class extracts the version and latest deployment, reshapes them into the envelope that
 * {@link StackDeployment#fromJson} expects ({@code {version, deployment}}), and wraps the result in
 * a {@link StackSnapshot}.
 *
 * <p>File I/O, JSON parsing, and StackDeployment construction are all deferred until {@link
 * #snapshot()} is called. Any failure throws either {@link StackAccessException} (I/O failure,
 * retryable) or {@link StackContentException} (malformed content, never retryable).
 */
public record StackCheckpoint(Path file) {

  private static final SeedCodec CODEC = new SeedCodec();

  /**
   * Factory method — wraps the given checkpoint file path.
   *
   * @param file path to a Pulumi checkpoint file (e.g., {@code .pulumi/stacks/<stack>.json})
   * @return a StackCheckpoint instance holding the file path
   */
  public static StackCheckpoint of(Path file) {
    return new StackCheckpoint(file);
  }

  /**
   * Reads the checkpoint file, reshapes it into the StackDeployment envelope format, and returns a
   * {@link StackSnapshot}.
   *
   * <p>Flow:
   *
   * <ol>
   *   <li>Read the file as JSON (expecting {@code {version, checkpoint: {latest}}})
   *   <li>Extract {@code version} (int) and {@code checkpoint.latest} (object)
   *   <li>Build the envelope {@code {version: <v>, deployment: <latest>}}
   *   <li>Pass the envelope JSON string to {@link StackDeployment#fromJson}
   *   <li>Wrap the resulting StackDeployment in {@link StackSnapshot#of}
   * </ol>
   *
   * @return snapshot of the checkpoint's deployment graph
   * @throws StackAccessException on I/O failure (file missing/unreadable) — retryable
   * @throws StackContentException on malformed content (bad JSON, missing fields, rejected by
   *     StackDeployment) — never retryable
   */
  public StackSnapshot snapshot() throws StackAccessException, StackContentException {
    // Throw on missing file — this class only handles files we expect to exist;
    // the aggregator decides the policy (fail vs skip vs log).
    if (Files.notExists(file)) {
      throw new StackAccessException(file, new NoSuchFileException(file.toString()));
    }

    try {
      JsonNode root = CODEC.reading().readJson(Files.readString(file));

      JsonNode versionNode = root.get("version");
      if (versionNode == null || !versionNode.isInt()) {
        throw new StackContentException(
            file, new IllegalStateException("missing or invalid version field"));
      }

      JsonNode checkpointNode = root.path("checkpoint");
      JsonNode latestNode = checkpointNode.path("latest");
      if (latestNode.isMissingNode() || latestNode.isNull()) {
        throw new StackContentException(
            file, new IllegalStateException("missing checkpoint.latest field"));
      }

      ObjectNode envelope = JsonNodeFactory.instance.objectNode();
      envelope.set("version", versionNode);
      envelope.set("deployment", latestNode);

      String envelopeJson = CODEC.wire().writeJson(envelope);

      StackDeployment deployment = StackDeployment.fromJson(envelopeJson);

      return StackSnapshot.of(deployment);

    } catch (UncheckedIOException e) {
      // Malformed JSON — content is broken, never retry
      throw new StackContentException(file, e);
    } catch (IOException e) {
      // I/O error reading the file — may succeed on retry
      throw new StackAccessException(file, e);
    } catch (Exception e) {
      // StackDeployment.fromJson or other failures — treat as content issue
      throw new StackContentException(file, e);
    }
  }
}
