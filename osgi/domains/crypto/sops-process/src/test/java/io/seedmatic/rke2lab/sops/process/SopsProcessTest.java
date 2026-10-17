package io.seedmatic.rke2lab.sops.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one sops runner against a real sops and a throwaway age key: a failure quotes nothing of what
 * sops failed to read, the operator's stderr opt-in reaches a DEBUG line only, never IN_CLUSTER,
 * and the binary is the configured one or a store one, never a shadowing copy on PATH.
 */
class SopsProcessTest {

  // A YAML scalar where sops expects a mapping makes it answer `cannot unmarshal !!str `<value>``,
  // the value whole when it is short — so the marker stays short.
  private static final String MARKER = "LEAK7f3a";
  private static final EnclosureGate OPERATOR = () -> false;
  private static final EnclosureGate IN_CLUSTER = () -> true;

  @TempDir Path dir;
  private Path policy;
  private final RecordingLogger log = new RecordingLogger();

  @BeforeEach
  void throwawayPolicy() throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = dir.resolve("key.txt");
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]+").matcher(Files.readString(key));
    assertTrue(recipient.find());
    policy = dir.resolve(".sops.yaml");
    Files.writeString(
        policy, "creation_rules:\n  - path_regex: '.*'\n    age: " + recipient.group() + "\n");
  }

  @AfterEach
  void noOptIn() {
    System.clearProperty(SopsProcess.STDERR_OPT_IN);
  }

  @Test
  void aFailedRunQuotesNothingOfThePlaintext() {
    final SopsEncryptionException failure =
        assertThrows(
            SopsEncryptionException.class,
            () -> runner(Optional.of(OPERATOR), System.getenv()).run(encrypt(), MARKER + "\n"));

    assertTrue(failure.getMessage().contains("unparseable input"), failure.getMessage());
    for (Throwable link = failure; link != null; link = link.getCause()) {
      assertFalse(String.valueOf(link.getMessage()).contains(MARKER), "a message quotes sops");
      assertFalse(link.toString().contains(MARKER), "a toString quotes sops");
    }
    assertTrue(log.lines(System.Logger.Level.DEBUG).stream().noneMatch(l -> l.contains(MARKER)));
  }

  @Test
  void theOptedInStderrGoesToTheDebugLineAndNeverToTheMessage() {
    System.setProperty(SopsProcess.STDERR_OPT_IN, "true");
    final SopsEncryptionException failure =
        assertThrows(
            SopsEncryptionException.class,
            () -> runner(Optional.of(OPERATOR), System.getenv()).run(encrypt(), MARKER + "\n"));

    assertFalse(failure.toString().contains(MARKER), "the message stays sanitized");
    assertTrue(
        log.lines(System.Logger.Level.DEBUG).stream().anyMatch(line -> line.contains(MARKER)),
        "the stderr is on the DEBUG line");
  }

  @Test
  void inClusterTheOptInIsIgnoredWithOneWarning() {
    System.setProperty(SopsProcess.STDERR_OPT_IN, "true");
    final SopsProcess runner = runner(Optional.of(IN_CLUSTER), System.getenv());
    assertThrows(SopsEncryptionException.class, () -> runner.run(encrypt(), MARKER + "\n"));

    assertTrue(
        log.all().stream().noneMatch(line -> line.contains(MARKER)), "nothing of the stderr");
    final List<String> warnings = log.lines(System.Logger.Level.WARNING);
    assertEquals(1, warnings.size(), "one warning");
    assertTrue(warnings.get(0).contains(SopsProcess.STDERR_OPT_IN), warnings.get(0));
    assertTrue(warnings.get(0).contains("rke2lab-system"), warnings.get(0));
  }

  @Test
  void anAbsentEnclosureIsTheOperatorOne() {
    System.setProperty(SopsProcess.STDERR_OPT_IN, "true");
    assertThrows(
        SopsEncryptionException.class,
        () -> runner(Optional.empty(), System.getenv()).run(encrypt(), MARKER + "\n"));
    assertTrue(log.lines(System.Logger.Level.WARNING).isEmpty(), "no in-cluster warning");
  }

  @Test
  void aSopsOutsideTheStoreFirstOnPathIsRefusedByName() throws IOException {
    final Path shadow = Files.createDirectories(dir.resolve("shadow"));
    final Path fake = shadow.resolve("sops");
    Files.writeString(fake, "#!/bin/sh\nexit 0\n");
    Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
    final SopsProcess runner = runner(Optional.of(OPERATOR), pathFirst(shadow));

    final SopsEncryptionException refused =
        assertThrows(SopsEncryptionException.class, () -> runner.run(encrypt(), "a: b\n"));
    assertTrue(refused.getMessage().contains(fake.toString()), refused.getMessage());
    assertTrue(refused.getMessage().contains("outside /nix/store"), refused.getMessage());
  }

  @Test
  void aSymlinkIntoTheStoreFirstOnPathIsAccepted() throws IOException {
    final Path store = storeSops();
    assumeTrue(store.startsWith(Path.of("/nix/store")), "the ambient sops is a store one");
    final Path links = Files.createDirectories(dir.resolve("links"));
    Files.createSymbolicLink(links.resolve("sops"), store);

    final String sealed = runner(Optional.of(OPERATOR), pathFirst(links)).run(encrypt(), "a: b\n");
    assertTrue(sealed.contains("sops:"), "sealed by the store sops the link points at");
  }

  @Test
  void theConfiguredBinaryMustBeAbsoluteAndExecutable() {
    final Map<String, String> env = new HashMap<>(System.getenv());
    env.put(SopsProcess.BINARY_ENV, "relative/sops");
    final SopsEncryptionException refused =
        assertThrows(
            SopsEncryptionException.class,
            () -> runner(Optional.of(OPERATOR), env).run(encrypt(), "a: b\n"));
    assertTrue(refused.getMessage().contains(SopsProcess.BINARY_ENV), refused.getMessage());
  }

  private SopsProcess runner(Optional<EnclosureGate> enclosure, Map<String, String> inherited) {
    return new SopsProcess(Map.of(), enclosure, inherited, log);
  }

  private List<String> encrypt() {
    return List.of(
        "--encrypt",
        "--config",
        policy.toString(),
        "--filename-override",
        "x.yaml",
        "--input-type",
        "yaml",
        "--output-type",
        "yaml",
        "/dev/stdin");
  }

  private static Map<String, String> pathFirst(Path first) {
    final Map<String, String> env = new HashMap<>(System.getenv());
    env.remove(SopsProcess.BINARY_ENV);
    env.put("PATH", first + ":" + System.getenv("PATH"));
    return env;
  }

  private static Path storeSops() throws IOException {
    for (String entry : System.getenv("PATH").split(":")) {
      final Path candidate = Path.of(entry, "sops");
      if (Files.isExecutable(candidate)) {
        return candidate.toRealPath();
      }
    }
    throw new IllegalStateException("no sops on PATH");
  }

  private static boolean onPath(String tool) {
    for (String entry : System.getenv("PATH").split(":")) {
      if (Files.isExecutable(Path.of(entry, tool))) {
        return true;
      }
    }
    return false;
  }

  /** A logger that keeps what it is given, by level. */
  private static final class RecordingLogger implements System.Logger {
    private final List<Map.Entry<Level, String>> lines = new ArrayList<>();

    List<String> lines(Level level) {
      return lines.stream().filter(e -> e.getKey() == level).map(Map.Entry::getValue).toList();
    }

    List<String> all() {
      return lines.stream().map(Map.Entry::getValue).toList();
    }

    @Override
    public String getName() {
      return "recording";
    }

    @Override
    public boolean isLoggable(Level level) {
      return true;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
      lines.add(Map.entry(level, msg));
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
      lines.add(Map.entry(level, params == null ? format : MessageFormat.format(format, params)));
    }
  }
}
