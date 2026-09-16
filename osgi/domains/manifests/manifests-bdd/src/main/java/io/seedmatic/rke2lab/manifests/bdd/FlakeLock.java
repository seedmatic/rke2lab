package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Reads a single locked value out of a flake.lock by STREAMING to it — a delegate the synthesis
 * scenario holds rather than a static helper (it owns its {@link JsonFactory} + the walk, so the
 * stage just asks it for the pin).
 *
 * <p>A flake.lock's transitive-input closure can be many MB, so a full JSON tree parse would
 * materialise the whole thing for one scalar and a {@code YAMLMapper} (SnakeYAML) would trip its 3
 * MB code-point limit. The walk instead descends the fixed field path and {@code skipChildren}s
 * every other subtree — bounded by the path's depth, not the file size.
 */
final class FlakeLock {

  private final JsonFactory json = new JsonFactory();

  /**
   * The locked nixpkgs commit — {@code nodes.nixpkgs.locked.rev}. {@link Optional#empty()} if the
   * path is absent or its value is not a string (the caller turns that into the render's
   * fail-loud).
   */
  Optional<String> nixpkgsRev(String flakeLock) {
    return locked(flakeLock, "nodes", "nixpkgs", "locked", "rev");
  }

  private Optional<String> locked(String flakeLock, String... path) {
    try (JsonParser p = json.createParser(flakeLock)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return Optional.empty();
      }
      for (int depth = 0; depth < path.length; depth++) {
        // Seek path[depth] in the current object, skipping the subtrees of the fields before it.
        boolean found = false;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
          if (path[depth].equals(p.currentName())) {
            found = true;
            break;
          }
          final JsonToken skipped = p.nextToken();
          if (skipped == JsonToken.START_OBJECT || skipped == JsonToken.START_ARRAY) {
            p.skipChildren();
          }
        }
        if (!found) {
          return Optional.empty();
        }
        final JsonToken value = p.nextToken();
        if (depth == path.length - 1) {
          return value == JsonToken.VALUE_STRING
              ? Optional.ofNullable(p.getText())
              : Optional.empty();
        }
        if (value != JsonToken.START_OBJECT) {
          return Optional.empty();
        }
      }
      return Optional.empty();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot parse the source flake.lock", ex);
    }
  }
}
