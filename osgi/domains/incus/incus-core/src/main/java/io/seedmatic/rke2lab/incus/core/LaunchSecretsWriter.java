package io.seedmatic.rke2lab.incus.core;

import io.seedmatic.rke2lab.auth.contract.AuthTokenContact;
import io.seedmatic.rke2lab.auth.contract.AuthTokenSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upserts the flox launch token into the worktree's {@code .secrets}, preserving the file's
 * comments and layout. A beat of the incus PREPARE (§ provisioning-slice delta #10): a FILE
 * materialisation (regex YAML upsert, no Pulumi engine) over the SAME FS the scion computes its
 * {@code BootstrapPaths} on, run BEFORE the {@code worktree.dir} mount binds {@code .secrets} into
 * the instance. It was host-only in {@code main} only because it was nested in {@code
 * IncusResourceBootstrap} — an accident of place; in I6 every materialisation is a scion gesture.
 *
 * <p>Precedence: an environment variable wins, else the {@link AuthTokenContact} is asked ({@code
 * flox auth token}). A blank result leaves the file untouched; the file is written only when the
 * upsert changed it. (GitHub is no longer written here — its credential flows from the App via
 * {@code ghapp}, sealed in the cellar, not into {@code .secrets}.)
 */
public final class LaunchSecretsWriter {

  private static final Pattern FLOX_HEADER = Pattern.compile("^([\\t ]*)flox:\\s*(#.*)?$");
  private static final Pattern TOKEN =
      Pattern.compile("^([\\t ]*token\\s*:\\s*)([^#]*)(\\s*(#.*)?)$");

  private final Optional<AuthTokenContact> tokens;
  private final Function<String, Optional<String>> env;

  /**
   * A world booted without {@code auth-edge} publishes no {@link AuthTokenContact}; the writer
   * still resolves tokens from the environment (its higher-precedence source), so it takes the
   * contact as an {@link Optional} rather than requiring one.
   */
  public LaunchSecretsWriter(Optional<AuthTokenContact> tokens) {
    this(tokens, key -> Optional.ofNullable(System.getenv(key)));
  }

  /** Test seam: an injected environment accessor so token precedence is exercised hermetically. */
  LaunchSecretsWriter(Optional<AuthTokenContact> tokens, Function<String, Optional<String>> env) {
    this.tokens = tokens;
    this.env = env;
  }

  /** Upsert the flox token into {@code secretsFile}. */
  public void ensureTokensPresent(Path secretsFile) {
    ensureFloxToken(secretsFile);
  }

  private void ensureFloxToken(Path secretsFile) {
    final String token =
        resolve(
            AuthTokenSource.FLOXHUB,
            List.of(
                env.apply("FLOXHUB_TOKEN"), env.apply("FLOX_TOKEN"), env.apply("FLOX_AUTH_TOKEN")));
    if (token.isBlank()) {
      return;
    }
    rewrite(secretsFile, original -> upsertFlox(original, token));
  }

  private String resolve(AuthTokenSource source, List<Optional<String>> envCandidates) {
    for (Optional<String> candidate : envCandidates) {
      final Optional<String> present =
          candidate.map(String::trim).filter(value -> !value.isBlank());
      if (present.isPresent()) {
        return present.get();
      }
    }
    return tokens.flatMap(contact -> contact.tokenFor(source)).orElse("");
  }

  private void rewrite(Path secretsFile, java.util.function.UnaryOperator<String> upsert) {
    try {
      final String original = Files.readString(secretsFile, StandardCharsets.UTF_8);
      final String updated = upsert.apply(original);
      if (!original.equals(updated)) {
        Files.writeString(secretsFile, updated, StandardCharsets.UTF_8);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to update launch secrets: " + secretsFile, ex);
    }
  }

  private String upsertFlox(String content, String floxToken) {
    final String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";
    final List<String> lines = new ArrayList<>(List.of(content.split("\\r?\\n", -1)));
    final String tokenValue = yamlSingleQuoted(floxToken);

    final int headerIndex = findHeader(lines, FLOX_HEADER);
    if (headerIndex < 0) {
      appendBlock(lines, "flox:", "  token: " + tokenValue);
      return String.join(lineSeparator, lines);
    }

    final String headerIndent = leadingWhitespace(lines.get(headerIndex));
    final String childIndent = headerIndent + "  ";
    final int blockStart = headerIndex + 1;
    final int blockEnd = blockEnd(lines, blockStart, indentationWidth(headerIndent));

    for (int i = blockStart; i < blockEnd; i++) {
      final Matcher tokenMatcher = TOKEN.matcher(lines.get(i));
      if (tokenMatcher.matches()) {
        lines.set(i, tokenMatcher.group(1) + tokenValue + nullToEmpty(tokenMatcher, 3));
        return String.join(lineSeparator, lines);
      }
    }
    lines.add(blockEnd, childIndent + "token: " + tokenValue);
    return String.join(lineSeparator, lines);
  }

  private int findHeader(List<String> lines, Pattern header) {
    for (int i = 0; i < lines.size(); i++) {
      if (header.matcher(lines.get(i)).matches()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The first line at or above the header's indentation (a comment/blank line stays in the block).
   */
  private int blockEnd(List<String> lines, int blockStart, int headerIndentWidth) {
    for (int i = blockStart; i < lines.size(); i++) {
      final String trimmed = lines.get(i).trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (indentationWidth(lines.get(i)) <= headerIndentWidth) {
        return i;
      }
    }
    return lines.size();
  }

  private void appendBlock(List<String> lines, String... block) {
    if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
      lines.add("");
    }
    lines.addAll(List.of(block));
  }

  private String nullToEmpty(Matcher matcher, int group) {
    return matcher.group(group) == null ? "" : matcher.group(group);
  }

  private String leadingWhitespace(String line) {
    return line.substring(0, indentationWidth(line));
  }

  private int indentationWidth(String line) {
    int width = 0;
    while (width < line.length()) {
      final char c = line.charAt(width);
      if (c != ' ' && c != '\t') {
        break;
      }
      width++;
    }
    return width;
  }

  private String yamlSingleQuoted(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
