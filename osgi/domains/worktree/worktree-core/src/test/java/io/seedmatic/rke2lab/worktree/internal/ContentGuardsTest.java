package io.seedmatic.rke2lab.worktree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Java port of the pre-commit content guards: its literals are the hook's (the drift test reads
 * {@code .githooks/pre-commit}), and its logic is the hook's, down to the key-body window.
 */
class ContentGuardsTest {

  private static final String HEADER = "-----BEGIN RSA " + "PRIVATE" + " KEY-----";
  private static final String BODY =
      "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun";

  private final ContentGuards guards = new ContentGuards();

  @Test
  void the_literals_are_the_hooks() throws IOException {
    final Map<String, String> hook = literalsOf(Files.readString(preCommit()));
    System.out.println("pre-commit literals, extracted: " + hook);
    assertEquals(javaLiterals(), hook);
  }

  @Test
  void the_drift_test_bites_when_the_hook_moves() throws IOException {
    final String text = Files.readString(preCommit());
    for (String[] mutation :
        List.of(
            new String[] {"{40,}$/", "{41,}$/"},
            new String[] {"NR <= hdr + 2", "NR <= hdr + 3"},
            new String[] {"flox_pat_)", "flox_pat_|xoxb-)"},
            new String[] {"AGE-SECRET[-]KEY-1[A-Z0-9]{50,}", "AGE-SECRET[-]KEY-1[A-Z0-9]{40,}"})) {
      final String mutated = text.replace(mutation[0], mutation[1]);
      assertFalse(mutated.equals(text), "the mutation " + mutation[0] + " applies to the hook");
      assertFalse(javaLiterals().equals(literalsOf(mutated)), "drift caught: " + mutation[0]);
    }
  }

  @Test
  void the_key_body_must_follow_the_header_within_the_window() {
    assertEquals(List.of("k (line 2): private key material"), inspect(HEADER + "\n" + BODY + "\n"));
    assertEquals(
        List.of("k (line 3): private key material"), inspect(HEADER + "\n\n" + BODY + "\n"));
    assertEquals(List.of(), inspect(HEADER + "\n\n\n" + BODY + "\n"), "outside the window");
    assertEquals(List.of(), inspect(HEADER + "\n"), "a header alone is a mention");
    assertEquals(List.of(), inspect(BODY + "\n"), "a body without a header is not key material");
  }

  @Test
  void an_age_secret_is_found_anywhere() {
    final String age = "AGE-SECRET" + "-KEY-1" + "Q".repeat(58);
    assertEquals(List.of("k (line 2): private key material"), inspect("x\n" + age + "\n"));
  }

  @Test
  void a_token_counts_only_outside_enc_values() {
    final String token = "tsk" + "ey-" + "kQ7fGhJkLmNoPqRs1234";
    assertEquals(
        List.of("k (line(s) 2): a bearer token outside ENC[…]"),
        inspect("a: ENC[AES256_GCM," + token + "]\nb: " + token + "\n"));
    assertTrue(inspect("a: ENC[AES256_GCM,data:" + token + "]\n").isEmpty());
  }

  private List<String> inspect(String text) {
    return guards.inspect("k", text.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> javaLiterals() {
    final Map<String, String> literals = new LinkedHashMap<>();
    literals.put("privateKeyHeader", ContentGuards.PRIVATE_KEY_HEADER_ERE);
    literals.put("keyBody", ContentGuards.KEY_BODY_ERE);
    literals.put("keyBodyWindow", String.valueOf(ContentGuards.KEY_BODY_WINDOW));
    literals.put("ageSecret", ContentGuards.AGE_SECRET_ERE);
    literals.put("encValue", ContentGuards.ENC_VALUE_ERE);
    literals.put("tokenShape", ContentGuards.TOKEN_SHAPE_ERE);
    return literals;
  }

  /** The hook's literals, read where the hook writes them; a missing one reads as drift. */
  private static Map<String, String> literalsOf(String hook) {
    final Map<String, String> literals = new LinkedHashMap<>();
    literals.put("privateKeyHeader", group(hook, "\\n\\s*/(BEGIN [^\\n]*KEY)/ \\{ hdr = NR \\}"));
    literals.put("keyBody", group(hook, "NR <= hdr \\+ \\d+ && /([^\\n]*)/ \\{ found = 1"));
    literals.put("keyBodyWindow", group(hook, "NR <= hdr \\+ (\\d+) &&"));
    literals.put("ageSecret", group(hook, "\\n\\s*/(AGE-SECRET[^\\n]*)/ \\{ found = 1"));
    literals.put("encValue", group(hook, "sed 's/(ENC[^\\n]*)//g'"));
    literals.put("tokenShape", group(hook, "grep -nE '([^'\\n]*)'"));
    return literals;
  }

  private static String group(String text, String regex) {
    final Matcher matcher = Pattern.compile(regex).matcher(text);
    return matcher.find() ? matcher.group(1) : "<absent>";
  }

  private static Path preCommit() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      final Path hook = dir.resolve(".githooks/pre-commit");
      if (Files.isRegularFile(hook)) {
        return hook;
      }
    }
    throw new IllegalStateException(
        "no .githooks/pre-commit above " + Path.of("").toAbsolutePath());
  }
}
