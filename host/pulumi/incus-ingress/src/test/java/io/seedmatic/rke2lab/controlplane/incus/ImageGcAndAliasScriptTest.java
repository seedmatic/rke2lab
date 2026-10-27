package io.seedmatic.rke2lab.controlplane.incus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grow's image GC and alias script, run against a stub {@code incus} holding a project's
 * images: the server's and the agent's are both kept whichever of them changed, every other image
 * is deleted, and each alias lands on its own fingerprint.
 */
class ImageGcAndAliasScriptTest {

  private static final String SERVER_OLD = "a".repeat(64);
  private static final String SERVER_NEW = "b".repeat(64);
  private static final String AGENT_OLD = "c".repeat(64);
  private static final String AGENT_NEW = "d".repeat(64);
  private static final String STRAY = "e".repeat(64);

  @TempDir Path dir;

  @Test
  void the_server_changed_and_the_agent_did_not() throws Exception {
    final Run run =
        run(List.of(SERVER_OLD, SERVER_NEW, AGENT_OLD, STRAY), kept(SERVER_NEW, AGENT_OLD));
    assertEquals(Set.of(SERVER_OLD, STRAY), run.deleted());
    assertEquals(Map.of("node-server", SERVER_NEW, "node-agent", AGENT_OLD), run.aliases());
  }

  @Test
  void the_agent_changed_and_the_server_did_not() throws Exception {
    final Run run = run(List.of(SERVER_OLD, AGENT_OLD, AGENT_NEW), kept(SERVER_OLD, AGENT_NEW));
    assertEquals(Set.of(AGENT_OLD), run.deleted());
    assertEquals(Map.of("node-server", SERVER_OLD, "node-agent", AGENT_NEW), run.aliases());
  }

  @Test
  void both_changed() throws Exception {
    final Run run =
        run(List.of(SERVER_OLD, SERVER_NEW, AGENT_OLD, AGENT_NEW), kept(SERVER_NEW, AGENT_NEW));
    assertEquals(Set.of(SERVER_OLD, AGENT_OLD), run.deleted());
    assertEquals(Map.of("node-server", SERVER_NEW, "node-agent", AGENT_NEW), run.aliases());
  }

  @Test
  void neither_changed_and_a_rerun_deletes_nothing() throws Exception {
    final Run run = run(List.of(SERVER_OLD, AGENT_OLD), kept(SERVER_OLD, AGENT_OLD));
    assertEquals(Set.of(), run.deleted());
    assertEquals(Map.of("node-server", SERVER_OLD, "node-agent", AGENT_OLD), run.aliases());
  }

  @Test
  void a_grow_with_no_agent_image_keeps_the_server_only() throws Exception {
    final Map<String, String> serverOnly = new LinkedHashMap<>();
    serverOnly.put("node-server", SERVER_NEW);
    final Run run = run(List.of(SERVER_OLD, SERVER_NEW), serverOnly);
    assertEquals(Set.of(SERVER_OLD), run.deleted());
    assertEquals(Map.of("node-server", SERVER_NEW), run.aliases());
  }

  private static Map<String, String> kept(String server, String agent) {
    final Map<String, String> kept = new LinkedHashMap<>();
    kept.put("node-server", server);
    kept.put("node-agent", agent);
    return kept;
  }

  private record Run(Set<String> deleted, Map<String, String> aliases) {}

  /**
   * Run the script with a stub {@code incus} on PATH that lists {@code images} and logs every call.
   */
  private Run run(List<String> images, Map<String, String> kept)
      throws IOException, InterruptedException {
    final Path log = dir.resolve("incus.log");
    final Path bin = Files.createDirectories(dir.resolve("bin"));
    final Path incus = bin.resolve("incus");
    final String listing =
        images.stream()
            .map(fp -> "\"/1.0/images/" + fp + "?project=rke2lab\"")
            .collect(Collectors.joining(",", "[", "]"));
    Files.writeString(
        incus,
        String.join(
            "\n",
            "#!/bin/sh",
            "printf '%s\\n' \"$*\" >> '" + log + "'",
            "case \"$1\" in",
            "  query) echo '" + listing + "' ;;",
            "esac",
            ""));
    Files.setPosixFilePermissions(incus, PosixFilePermissions.fromString("rwxr-xr-x"));
    final Path script = dir.resolve("gc.sh");
    Files.writeString(script, InstanceGrow.imageGcAndAliasScript("bioskop-nixos", "rke2lab", kept));
    final ProcessBuilder builder =
        new ProcessBuilder("sh", script.toString()).redirectErrorStream(true);
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    final Process process = builder.start();
    final String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
    final List<String> calls = Files.readAllLines(log);
    final Set<String> deleted =
        calls.stream()
            .filter(c -> c.startsWith("image delete "))
            .map(c -> c.split(" ")[2].replace("bioskop-nixos:", ""))
            .collect(Collectors.toSet());
    final Map<String, String> aliases = new LinkedHashMap<>();
    calls.stream()
        .filter(c -> c.startsWith("image alias create "))
        .forEach(c -> aliases.put(c.split(" ")[3].replace("bioskop-nixos:", ""), c.split(" ")[4]));
    assertTrue(
        kept.values().stream().noneMatch(deleted::contains), "a kept image was deleted: " + calls);
    return new Run(deleted, aliases);
  }
}
