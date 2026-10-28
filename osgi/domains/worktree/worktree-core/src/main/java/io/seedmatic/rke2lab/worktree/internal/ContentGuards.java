package io.seedmatic.rke2lab.worktree.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The repository's pre-commit content guards ({@code .githooks/pre-commit}: {@code
 * secret_material_guard}, {@code token_shape_guard}) ported to run on BLOB bytes, for a commit this
 * domain builds itself and no hook sees. The {@code *_ERE} constants are the hook's literals byte
 * for byte — a drift test reads the hook and compares — and the logic is the hook's: a private-key
 * header followed within {@link #KEY_BODY_WINDOW} lines by a long base64 line, an age secret
 * anywhere, and a bearer-token shape once every {@code ENC[…]} value is removed. The token guard
 * runs in its strict form, on every file, not only on sops-managed ones.
 *
 * <p>A finding names the path and the line, never the line itself: the value is the secret.
 */
final class ContentGuards {

  static final String PRIVATE_KEY_HEADER_ERE = "BEGIN [A-Z]*[ ]?PRIVATE[ ]KEY";
  static final String KEY_BODY_ERE = "^[A-Za-z0-9+\\/=]{40,}$";
  static final int KEY_BODY_WINDOW = 2;
  static final String AGE_SECRET_ERE = "AGE-SECRET[-]KEY-1[A-Z0-9]{50,}";
  static final String ENC_VALUE_ERE = "ENC\\[[^]]*\\]";
  static final String TOKEN_SHAPE_ERE =
      "eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+|(tskey-|gh[opsu]_|github_pat_|dckr_pat_|glpat-|flox_pat_)[A-Za-z0-9_-]{16,}";

  private final Pattern privateKeyHeader = compile(PRIVATE_KEY_HEADER_ERE);
  private final Pattern keyBody = compile(KEY_BODY_ERE);
  private final Pattern ageSecret = compile(AGE_SECRET_ERE);
  private final Pattern encValue = compile(ENC_VALUE_ERE);
  private final Pattern tokenShape = compile(TOKEN_SHAPE_ERE);

  /** The findings for {@code path}'s bytes, each naming the path and a line number only. */
  List<String> inspect(String path, byte[] blob) {
    final String[] lines = new String(blob, StandardCharsets.ISO_8859_1).split("\n", -1);
    final List<String> findings = new ArrayList<>();
    secretMaterialLine(lines)
        .ifPresent(line -> findings.add(path + " (line " + line + "): private key material"));
    final List<Integer> tokens = tokenLines(lines);
    if (!tokens.isEmpty()) {
      findings.add(path + " (line(s) " + join(tokens) + "): a bearer token outside ENC[…]");
    }
    return findings;
  }

  /** The hook's awk program: the last header wins, the body must follow within the window. */
  private Optional<Integer> secretMaterialLine(String[] lines) {
    int header = 0;
    for (int n = 1; n <= lines.length; n++) {
      final String line = lines[n - 1];
      if (privateKeyHeader.matcher(line).find()) {
        header = n;
      }
      if (header > 0
          && n > header
          && n <= header + KEY_BODY_WINDOW
          && keyBody.matcher(line).find()) {
        return Optional.of(n);
      }
      if (ageSecret.matcher(line).find()) {
        return Optional.of(n);
      }
    }
    return Optional.empty();
  }

  private List<Integer> tokenLines(String[] lines) {
    final List<Integer> found = new ArrayList<>();
    for (int n = 1; n <= lines.length; n++) {
      if (tokenShape.matcher(encValue.matcher(lines[n - 1]).replaceAll("")).find()) {
        found.add(n);
      }
    }
    return found;
  }

  /**
   * POSIX ERE to {@link Pattern}: the one construct the hook uses that Java reads differently is a
   * {@code ]} first in a negated class, which ERE takes literally and Java does not.
   */
  static Pattern compile(String ere) {
    return Pattern.compile(ere.replace("[^]]", "[^\\]]"));
  }

  private static String join(List<Integer> numbers) {
    return String.join(",", numbers.stream().map(String::valueOf).toList());
  }
}
