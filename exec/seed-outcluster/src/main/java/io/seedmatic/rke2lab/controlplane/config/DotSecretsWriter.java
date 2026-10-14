package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.host.runtime.SopsAtRest;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surgical block upsert for the sops-encrypted {@code .secrets} YAML — the host-side writer behind
 * the {@code ghapp seed} operator command ({@link GithubAppCli}). The file is opened in memory,
 * only the block at the given path is rewritten — every other block and its {@code #
 * sops:encrypted} comments verbatim (a whole-file YAML round-trip would strip those comments and
 * silently un-encrypt the other secrets) — and the result is sealed again before it is written.
 * Leaves named in {@code encryptedLeaves} get a {@code # sops:encrypted} comment, the marker the
 * creation rule encrypts on; a multi-line value (a PEM) is emitted as a block scalar.
 *
 * <p>Nothing is written unless every leaf that was encrypted on disk, and every newly marked leaf,
 * comes out of the seal as {@code ENC[...]}: a rule that silently matched nothing is how a valid
 * envelope once carried an empty payload. The file is replaced atomically, never truncated.
 *
 * <p>This is a WRITE tool the operator invokes by hand — distinct from the {@code SecretsGateway}
 * seam the in-container scion reads through, which stays read-only.
 */
public final class DotSecretsWriter {

  private static final SeedCodec CODEC = new SeedCodec();

  private final SopsAtRest sops;

  public DotSecretsWriter(SopsAtRest sops) {
    this.sops = sops;
  }

  /**
   * Upsert the block at {@code path} (e.g. {@code github}, {@code app}) from ordered {@code
   * leaves}, insertion order kept. Missing parents are created; siblings stay verbatim.
   */
  public void upsert(
      Path secretsFile,
      List<String> path,
      Map<String, String> leaves,
      Set<String> encryptedLeaves) {
    // An input that already looks like ciphertext would satisfy the check below by itself.
    leaves.forEach(
        (name, value) -> {
          if (value.startsWith("ENC[")) {
            throw new IllegalArgumentException(
                name + " already looks like sops ciphertext; pass the clear value");
          }
        });
    final boolean present = Files.isReadable(secretsFile);
    // Every leaf encrypted on disk outside the rewritten block must still be encrypted after the
    // seal: losing a sibling's marker in the round-trip would otherwise write it in clear.
    final List<List<String>> mustStayEncrypted = new ArrayList<>();
    if (present) {
      collectEncrypted(readRaw(secretsFile), new ArrayList<>(), mustStayEncrypted);
      // Inside the rewritten block, a leaf encrypted on disk that is supplied again unmarked would
      // come out of the seal in clear: refuse it rather than downgrade it.
      for (final List<String> leaf : mustStayEncrypted) {
        if (leaf.size() == path.size() + 1 && startsWith(leaf, path)) {
          final String name = leaf.get(path.size());
          if (leaves.containsKey(name) && !encryptedLeaves.contains(name)) {
            throw new IllegalArgumentException(
                String.join(".", leaf) + " is encrypted on disk; mark it to keep it encrypted");
          }
        }
      }
      mustStayEncrypted.removeIf(leaf -> startsWith(leaf, path));
    }
    for (final String leaf : encryptedLeaves) {
      final List<String> full = new ArrayList<>(path);
      full.add(leaf);
      mustStayEncrypted.add(full);
    }
    final String existing = present ? sops.open(secretsFile) : "";
    final String sealed = sops.seal(secretsFile, splice(existing, path, leaves, encryptedLeaves));
    requireEncrypted(sealed, mustStayEncrypted, secretsFile);
    writeAtomically(secretsFile, sealed);
  }

  private static JsonNode readRaw(Path secretsFile) {
    try {
      return CODEC.reading().readYaml(Files.readString(secretsFile, StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read the secrets file at " + secretsFile, ex);
    }
  }

  private static void collectEncrypted(
      JsonNode node, List<String> at, List<List<String>> encrypted) {
    if (node == null) {
      return;
    }
    if (node.isTextual() && node.asText().startsWith("ENC[")) {
      encrypted.add(List.copyOf(at));
    } else if (node.isObject()) {
      node.fieldNames()
          .forEachRemaining(
              name -> {
                if (!(at.isEmpty() && name.equals("sops"))) {
                  at.add(name);
                  collectEncrypted(node.get(name), at, encrypted);
                  at.remove(at.size() - 1);
                }
              });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        at.add(Integer.toString(i));
        collectEncrypted(node.get(i), at, encrypted);
        at.remove(at.size() - 1);
      }
    }
  }

  private static boolean startsWith(List<String> leaf, List<String> prefix) {
    return leaf.size() >= prefix.size() && leaf.subList(0, prefix.size()).equals(prefix);
  }

  private static void requireEncrypted(
      String sealed, List<List<String>> mustBeEncrypted, Path secretsFile) {
    final JsonNode root = CODEC.reading().readYaml(sealed);
    if (root == null || !root.has("sops")) {
      throw new IllegalStateException(
          "refusing to write " + secretsFile + ": sops returned no envelope");
    }
    for (final List<String> leaf : mustBeEncrypted) {
      JsonNode node = root;
      for (final String part : leaf) {
        node = node.isArray() ? node.path(Integer.parseInt(part)) : node.path(part);
      }
      if (!node.asText("").startsWith("ENC[")) {
        throw new IllegalStateException(
            "refusing to write "
                + secretsFile
                + ": "
                + String.join(".", leaf)
                + " would be written in clear — its sops:encrypted marker was lost, or the"
                + " creation rule in .sops.yaml does not encrypt on it");
      }
    }
  }

  private static void writeAtomically(Path secretsFile, String sealed) {
    final Path target = secretsFile.toAbsolutePath();
    Path temp = null;
    try {
      temp = Files.createTempFile(target.getParent(), ".secrets.", ".tmp");
      Files.writeString(temp, sealed, StandardCharsets.UTF_8);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temp = null;
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write the secrets file at " + secretsFile, ex);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // best effort: the temp file holds ciphertext only
        }
      }
    }
  }

  private static List<String> renderBlock(
      String key, int depth, Map<String, String> leaves, Set<String> encryptedLeaves) {
    final String indent = "  ".repeat(depth);
    final List<String> lines = new ArrayList<>();
    lines.add(indent + key + ":");
    leaves.forEach(
        (name, value) -> {
          if (encryptedLeaves.contains(name)) {
            lines.add(indent + "  # sops:encrypted");
          }
          if (value.contains("\n")) {
            lines.add(indent + "  " + name + ": |");
            for (final String line : value.stripTrailing().split("\n", -1)) {
              lines.add(indent + "    " + line);
            }
          } else {
            lines.add(indent + "  " + name + ": " + quote(value));
          }
        });
    return lines;
  }

  /**
   * Replace the block at {@code path} (or create it, with its missing parents, at the end of its
   * parent) — every other line verbatim. A block runs from its {@code key:} line to the next line
   * indented at most as deep, blank lines included.
   */
  static String splice(
      String existing, List<String> path, Map<String, String> leaves, Set<String> encryptedLeaves) {
    final List<String> lines =
        existing.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(existing.split("\n", -1)));
    int from = 0;
    int to = lines.size();
    for (int depth = 0; depth < path.size(); depth++) {
      final String head = "  ".repeat(depth) + path.get(depth) + ":";
      int at = -1;
      for (int i = from; i < to; i++) {
        if (lines.get(i).equals(head) || lines.get(i).startsWith(head + " ")) {
          at = i;
          break;
        }
      }
      if (at < 0) {
        int insert = to;
        while (insert > from && lines.get(insert - 1).isBlank()) {
          insert--;
        }
        final List<String> created = new ArrayList<>();
        for (int d = depth; d < path.size() - 1; d++) {
          created.add("  ".repeat(d) + path.get(d) + ":");
        }
        created.addAll(
            renderBlock(path.get(path.size() - 1), path.size() - 1, leaves, encryptedLeaves));
        lines.addAll(insert, created);
        return String.join("\n", lines);
      }
      int end = at + 1;
      while (end < to && (lines.get(end).isBlank() || indentOf(lines.get(end)) > 2 * depth)) {
        end++;
      }
      from = at;
      to = end;
    }
    final List<String> block =
        renderBlock(path.get(path.size() - 1), path.size() - 1, leaves, encryptedLeaves);
    int end = to;
    while (end > from + 1 && lines.get(end - 1).isBlank()) {
      end--;
    }
    lines.subList(from, end).clear();
    lines.addAll(from, block);
    return String.join("\n", lines);
  }

  private static int indentOf(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == ' ') {
      n++;
    }
    return n;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
