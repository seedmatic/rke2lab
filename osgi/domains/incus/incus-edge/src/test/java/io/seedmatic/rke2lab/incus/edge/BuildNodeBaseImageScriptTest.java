package io.seedmatic.rke2lab.incus.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bundled build script, run against a stub {@code nix} that records what it is asked. */
class BuildNodeBaseImageScriptTest {

  private static final String REF =
      "git+file:///bare/rke2lab.git?rev=0123456789abcdef0123456789abcdef01234567";
  private static final Pattern GIT_CALL = Pattern.compile("(^|[;|&(`$\\s])git(\\s|$)");

  @TempDir Path dir;

  @Test
  void the_script_launches_no_git() {
    final String script =
        new String(
            new BuildRecipe().load(BuildRecipe.NIX_BUILD_SCRIPT_RESOURCE), StandardCharsets.UTF_8);
    final List<String> calls =
        script
            .lines()
            .map(String::strip)
            .filter(line -> !line.startsWith("#"))
            .filter(line -> GIT_CALL.matcher(line).find())
            .toList();
    assertEquals(List.of(), calls);
  }

  @Test
  void it_builds_the_revision_then_skips_while_the_digest_holds() throws Exception {
    final Path artifacts = dir.resolve("artifacts");
    final Path log = dir.resolve("nix.log");
    final Path nix = stubNix(log);

    assertEquals(0, run(artifacts, nix, "digest-1"));
    final List<String> first = Files.readAllLines(log);
    assertTrue(
        first.stream()
            .anyMatch(
                l ->
                    l.startsWith("build ")
                        && l.endsWith(
                            REF
                                + "#nixosConfigurations.rke2-node-base.config.system.build.metadata")),
        String.join("\n", first));
    assertTrue(first.stream().anyMatch(l -> l.startsWith("eval ") && l.contains(REF + "#")));
    assertTrue(Files.isRegularFile(artifacts.resolve("incus.tar.xz")));
    assertTrue(Files.isRegularFile(artifacts.resolve("rootfs.squashfs")));
    assertEquals("digest-1", Files.readString(artifacts.resolve(".image.checksum.sha256")).strip());
    assertEquals("1.33.1+rke2r1", Files.readString(artifacts.resolve("rke2.version")).strip());

    assertEquals(0, run(artifacts, nix, "digest-1"));
    assertEquals(first, Files.readAllLines(log), "an unchanged digest does not call nix");

    assertEquals(0, run(artifacts, nix, "digest-2"));
    assertTrue(Files.readAllLines(log).size() > first.size(), "a new digest rebuilds");
  }

  @Test
  void a_relative_artifact_dir_is_refused() throws Exception {
    assertEquals(2, run(Path.of("relative/artifacts"), stubNix(dir.resolve("nix.log")), "d"));
  }

  private int run(Path artifacts, Path nix, String digest)
      throws IOException, InterruptedException {
    final Path script = dir.resolve("build.sh");
    Files.write(script, new BuildRecipe().load(BuildRecipe.NIX_BUILD_SCRIPT_RESOURCE));
    final Process process =
        new ProcessBuilder(
                "sh", script.toString(), REF, artifacts.toString(), nix.toString(), digest)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    return process.waitFor();
  }

  /**
   * A {@code nix} that logs its verb and last argument, and yields paths shaped like the real
   * outputs.
   */
  private Path stubNix(Path log) throws IOException {
    final Path out = dir.resolve("store");
    final Path nix = dir.resolve("nix");
    Files.writeString(
        nix,
        String.join(
            "\n",
            "#!/bin/sh",
            "verb=\"$1\"; for last; do :; done",
            "printf '%s %s\\n' \"$verb\" \"$last\" >> '" + log + "'",
            "case \"$verb:$last\" in",
            "  build:*.metadata) mkdir -p '"
                + out
                + "/meta' && : > '"
                + out
                + "/meta/x.tar.xz' && echo '"
                + out
                + "/meta' ;;",
            "  build:*.squashfs) mkdir -p '"
                + out
                + "' && : > '"
                + out
                + "/root.squashfs' && echo '"
                + out
                + "/root.squashfs' ;;",
            "  eval:*) echo '1.33.1+rke2r1' ;;",
            "esac",
            ""));
    Files.setPosixFilePermissions(nix, PosixFilePermissions.fromString("rwxr-xr-x"));
    return nix;
  }
}
