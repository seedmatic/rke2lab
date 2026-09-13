package io.seedmatic.rke2lab.incus.edge;

import io.seedmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.seedmatic.rke2lab.incus.contract.ImageBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
 * LOCALLY when {@code nix} resolves on {@code PATH} — nix offloads the aarch64-linux build to its
 * configured remote builder, the operator's OWN channel; otherwise it streams the same script over
 * {@code ssh} to the configured builder host.
 *
 * <p>One of the ImageBuilder PAIR: registered with {@code rke2lab.gardening=cultivating} so the
 * frontier picks it when the ambient RunGate is cultivating. Its twin, {@link
 * SurveyingImageBuilder}, plans the build without shelling anything. The build script (the bundle
 * resource) is owned by the shared {@link BuildRecipe}, so both impls report the SAME {@link
 * #recipeDigest()} — the host's image-cache key must not move between the two modes.
 *
 * <p><b>Runtime dependency:</b> {@code nix} locally, or {@code ssh} and key-based access to the
 * builder host. No incus dependency (the provider imports). Unlike the former distrobuilder edge it
 * needs no root and no tmpfs scratch: nix realises the artifacts into {@code /nix/store} and only
 * the two finished files are published to the artifact dir.
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
    // No freshness short-circuit: nix build is idempotent and cheap when the derivation is already
    // realised, and the import tail is fingerprint-guarded — so we always run the script and let
    // nix
    // decide whether a rebuild is needed. (The former distrobuilder cache existed only because a
    // distrobuilder run always rebuilt from scratch; nix does not, and keying a cache on the build
    // script's hash alone would serve a STALE image whenever only the committed sources changed.)
    //
    // Build through the operator's OWN nix channel: when nix resolves locally (the seed-master host
    // — the Mac — has it), run the script HERE. nix offloads the aarch64-linux build to its
    // configured remote builder. The script is now a pure ARTIFACT producer — it does NOT touch
    // incus; importing the built image is the incus PROVIDER's job (the host GROW declares an Image
    // resource sourcing the artifacts), so the build no longer needs a local incus daemon. Only
    // when
    // nix is NOT local do we stream the build over ssh to the configured builder host.
    final String localNix = tryResolveExecutable(request.builderBinary());
    if (!localNix.isBlank()) {
      runLocalBuildOrThrow(request, localNix);
    } else {
      runRemoteBuildOrThrow(request);
    }
  }

  @Override
  public String recipeDigest() {
    return recipe.digest();
  }

  private void runLocalBuildOrThrow(ImageBuildRequest request, String nixExecutable) {
    final Path script = materializeScript();
    try {
      runCommandOrThrow(
          Path.of(request.workspaceDir()),
          List.of(
              "sh",
              script.toString(),
              request.workspaceDir(),
              request.localArtifactDir(),
              nixExecutable),
          "Failed to build the NixOS node image with nix");
    } finally {
      try {
        Files.deleteIfExists(script);
      } catch (IOException ignored) {
        // A leftover temp script is harmless; the build outcome already stands.
      }
    }
  }

  private void runRemoteBuildOrThrow(ImageBuildRequest request) {
    final String remoteHost = request.remoteHost();
    if (remoteHost.isBlank()) {
      throw new ImageBuildException(
          "nix is not available locally and no remote image.builderHost is configured");
    }

    final String binary = request.builderBinary().isBlank() ? "nix" : request.builderBinary();

    runRemoteBootstrapOverSshOrThrow(
        Path.of(request.workspaceDir()),
        remoteHost,
        List.of(request.remoteWorkspaceDir(), request.remoteArtifactDir(), binary),
        "Failed to build the NixOS node image on remote builder host " + remoteHost);
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

  private void runRemoteBootstrapOverSshOrThrow(
      Path workingDirectory, String remoteHost, List<String> scriptArgs, String failureMessage) {
    final String bootstrap = remoteBootstrap();

    final List<String> command = new ArrayList<>();
    command.add("ssh");
    command.add(remoteHost);
    command.add("sh");
    command.add("-xs");
    command.addAll(scriptArgs);

    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    try {
      final Process process = pb.start();
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      }

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

  /**
   * The self-contained bootstrap streamed to the builder over {@code ssh … sh -s}. It decodes the
   * bundled build script from a base64 heredoc into a throwaway temp dir and runs it. base64 keeps
   * the heredoc delimiter collision-free and needs nothing beyond coreutils. Positional args from
   * {@code sh -s}: {@code $1}=remote workspace, {@code $2}=artifact dir, {@code $3}=nix binary,
   * {@code $4}=incus project.
   */
  private String remoteBootstrap() {
    final String scriptB64 =
        Base64.getEncoder().encodeToString(recipe.load(BuildRecipe.NIX_BUILD_SCRIPT_RESOURCE));
    return String.join(
        "\n",
        "set -eu",
        "export PATH=\"/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH\"",
        "tmp_dir=$(mktemp -d)",
        "trap 'rm -rf \"$tmp_dir\"' EXIT",
        "base64 -d > \"$tmp_dir/build.sh\" <<'B64SCRIPT'",
        scriptB64,
        "B64SCRIPT",
        "chmod 700 \"$tmp_dir/build.sh\"",
        "\"$tmp_dir/build.sh\" \"$1\" \"$2\" \"$3\" \"$4\"");
  }
}
