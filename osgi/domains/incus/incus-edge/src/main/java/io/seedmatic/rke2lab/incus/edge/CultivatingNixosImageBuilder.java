package io.seedmatic.rke2lab.incus.edge;

import io.seedmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.seedmatic.rke2lab.incus.contract.ImageBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.osgi.service.component.annotations.Component;

/**
 * The CULTIVATING incus image-build edge — the single live door. Builds the NixOS node substrate's
 * Incus artifacts ({@code incus.tar.xz} + {@code rootfs.squashfs}) by running the bundled nix build
 * script: {@code nix build …#nixosConfigurations.rke2-node-base.config.system.build.{metadata,
 * squashfs}}. It is BUILD-ONLY — it does NOT import the image; the incus PROVIDER does that (the
 * host GROW declares an {@code Image} resource sourcing these artifacts, so the engine orders
 * Project → Image → Instance and the import rides the provider's channel). It runs the script
 * LOCALLY — nix offloads the aarch64-linux build to its configured remote builder, the operator's
 * OWN channel — on a REVISION ({@code git+file://<repository>?rev=<sha>}), never a working tree,
 * and the script runs no git.
 *
 * <p>One of the ImageBuilder PAIR: registered with {@code rke2lab.gardening=cultivating} so the
 * frontier picks it when the ambient RunGate is cultivating. Its twin, {@link
 * SurveyingImageBuilder}, plans the build without shelling anything. The build script (the bundle
 * resource) is owned by the shared {@link BuildRecipe}, so both impls report the SAME {@link
 * #recipeDigest()} — the host's image-cache key must not move between the two modes.
 *
 * <p><b>Runtime dependency:</b> {@code nix} locally. No incus dependency (the provider imports).
 * Unlike the former distrobuilder edge it needs no root and no tmpfs scratch: nix realises the
 * artifacts into {@code /nix/store} and only the two finished files are published to the artifact
 * dir.
 */
@Component(service = ImageBuilder.class, property = "rke2lab.gardening=cultivating")
public final class CultivatingNixosImageBuilder implements ImageBuilder {

  private final BuildRecipe recipe = new BuildRecipe();

  @Override
  public void build(ImageBuildRequest request) {
    // The edge-private ImageBuildException (a RuntimeException carrying its message and, where it
    // wraps one, its cause) is thrown deep in the process plumbing and propagates to the scenario,
    // which chains it into the failed step so the runbook shows the reason AND the stack — rather
    // than the caller collapsing it into a bare summary string here.
    // No freshness short-circuit here: the script's own gate compares the source digest with the
    // one recorded beside the artifacts, and nix is idempotent when the derivation is realised.
    final String nix = tryResolveExecutable(request.builderBinary());
    if (nix.isBlank()) {
      throw new ImageBuildException(
          "nix is not available locally (builderBinary=" + request.builderBinary() + ")");
    }
    final Path script = materializeScript();
    try {
      final Path artifactDir = Path.of(request.artifactDir());
      Files.createDirectories(artifactDir);
      runCommandOrThrow(
          artifactDir,
          List.of(
              "sh",
              script.toString(),
              request.flakeRef(),
              request.artifactDir(),
              nix,
              request.sourceDigest()),
          "Failed to build the NixOS node image with nix");
    } catch (IOException ex) {
      throw new ImageBuildException(
          "cannot create the artifact dir " + request.artifactDir() + ": " + ex.getMessage(), ex);
    } finally {
      try {
        Files.deleteIfExists(script);
      } catch (IOException ignored) {
        // A leftover temp script is harmless; the build outcome already stands.
      }
    }
  }

  @Override
  public String recipeDigest() {
    return recipe.digest();
  }

  /** Write the bundled nix build script to a temp file for a local run. */
  private Path materializeScript() {
    try {
      final Path script = Files.createTempFile("build-node-base-", ".sh");
      Files.write(script, recipe.load(BuildRecipe.NIX_BUILD_SCRIPT_RESOURCE));
      return script;
    } catch (IOException ex) {
      throw new ImageBuildException(
          "Failed to materialise the nix build script: " + ex.getMessage(), ex);
    }
  }

  private String tryResolveExecutable(String configuredBinary) {
    if (configuredBinary == null || configuredBinary.isBlank()) {
      return "";
    }

    final String trimmedBinary = configuredBinary.trim();
    if (trimmedBinary.contains("/")) {
      final Path explicitPath = Path.of(trimmedBinary).toAbsolutePath().normalize();
      if (Files.isExecutable(explicitPath)) {
        return explicitPath.toString();
      }
      return "";
    }

    final Set<Path> searchDirectories = new LinkedHashSet<>();

    final String pathEnv = System.getenv("PATH");
    if (pathEnv != null && !pathEnv.isBlank()) {
      for (String entry : pathEnv.split(":")) {
        if (entry != null && !entry.isBlank()) {
          searchDirectories.add(Path.of(entry).toAbsolutePath().normalize());
        }
      }
    }

    searchDirectories.add(Path.of("/run/current-system/sw/bin"));
    searchDirectories.add(Path.of("/nix/var/nix/profiles/default/bin"));
    searchDirectories.add(Path.of("/opt/homebrew/bin"));
    searchDirectories.add(Path.of("/usr/local/bin"));
    searchDirectories.add(Path.of("/usr/bin"));
    searchDirectories.add(Path.of("/bin"));

    for (Path directory : searchDirectories) {
      final Path candidate = directory.resolve(trimmedBinary).normalize();
      if (Files.isExecutable(candidate)) {
        return candidate.toString();
      }
    }

    return "";
  }

  /**
   * The last {@code maxChars} of {@code text}, trimmed forward to the next line boundary — a
   * bounded, readable excerpt for an error message that must cross the JSON seed-broker seam
   * without exceeding the SeedCodec read limit.
   */
  private static String tail(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    final int cut = text.length() - maxChars;
    final int nl = text.indexOf('\n', cut);
    final int from = nl >= 0 ? nl + 1 : cut;
    return "…(" + from + " earlier chars elided)\n" + text.substring(from);
  }

  private void runCommandOrThrow(
      Path workingDirectory, List<String> command, String failureMessage) {
    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    try {
      final Process process = pb.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int exitCode = process.waitFor();

      if (exitCode != 0) {
        // A failed nix build dumps every derivation's output — the full log runs to many MB (the
        // functional-test suite alone is ~20 MB). Print it so the operator sees the real failure in
        // the run log, but the exception message carries only a BOUNDED TAIL: this message rides
        // the
        // jGiven runbook back across the seed-broker seam, and a multi-MB string blows the
        // SeedCodec
        // read limit — the decode then fails and masks the real cause behind "Failed to decode
        // SeedEnvelope" (see ClusterSeedScenario the_instance_is_provisioned).
        System.out.println(output);
        throw new ImageBuildException(
            failureMessage
                + " (exit="
                + exitCode
                + ")\nCommand: "
                + String.join(" ", command)
                + "\nOutput (tail; full log above):\n"
                + tail(output, 4000));
      }
    } catch (IOException ex) {
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    }
  }
}
