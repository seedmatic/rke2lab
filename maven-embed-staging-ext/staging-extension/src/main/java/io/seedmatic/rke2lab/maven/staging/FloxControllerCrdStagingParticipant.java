package io.seedmatic.rke2lab.maven.staging;

import java.io.File;
import java.io.IOException;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stages the flox-controller CRD (single-sourced from the flake input, never vendored) into
 * manifests-core's resources BEFORE the reactor executes — so the build-cache hashes the CURRENT
 * flake's CRD, never a stale one a previous staging left on disk.
 *
 * <p><b>Why here.</b> The CRD is staged by {@code nix run .#stage-flox-controller-crd} (the {@code
 * stageFloxControllerCrds} snippet). The nix {@code buildReactorExe} runs it in its buildPhase; a
 * plain {@code ./mvnw} build does NOT — so after a flox-controller re-lock the on-disk CRD stayed
 * stale, its unchanged content produced a legitimate cache hit, and the old schema shipped and then
 * rejected FloxEnv CRs declaring newer fields (e.g. {@code spec.inject}). The flake.lock is not a
 * maven-cache input; this participant is the bridge. {@code afterProjectsRead} is the single hook
 * that runs ONCE, after the POMs are read but before any project is hashed/built.
 *
 * <p><b>Guards.</b> Skipped when {@code RKE2LAB_CRD_STAGED} is set — the nix build already staged
 * in its buildPhase and a nested {@code nix run} in that sandbox has no daemon/network. Skipped
 * when manifests-core (which packages the CRD) is not in the reactor, so unrelated {@code -pl}
 * builds pay no nix-eval tax.
 */
@Named
@Singleton
public class FloxControllerCrdStagingParticipant extends AbstractMavenLifecycleParticipant {

  private static final Logger log =
      LoggerFactory.getLogger(FloxControllerCrdStagingParticipant.class);

  private static final String STAGED_MARKER = "RKE2LAB_CRD_STAGED";
  private static final String CRD_PACKAGING_MODULE = "manifests-core";

  @Override
  public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
    if (System.getenv(STAGED_MARKER) != null) {
      log.info(
          "flox-controller CRD staging: skipped ({} set — the nix build already staged it)",
          STAGED_MARKER);
      return;
    }
    final boolean packagesCrd =
        session.getAllProjects().stream()
            .anyMatch(p -> CRD_PACKAGING_MODULE.equals(p.getArtifactId()));
    if (!packagesCrd) {
      return; // this reactor does not build the CRD-packaging module — nothing to stage
    }

    final File root = session.getRequest().getMultiModuleProjectDirectory();
    log.info("flox-controller CRD staging: nix run .#stage-flox-controller-crd (in {})", root);
    try {
      final int rc =
          new ProcessBuilder("nix", "run", ".#stage-flox-controller-crd")
              .directory(root)
              .inheritIO()
              .start()
              .waitFor();
      if (rc != 0) {
        throw new MavenExecutionException(
            "flox-controller CRD staging failed: `nix run .#stage-flox-controller-crd` exited "
                + rc,
            (Throwable) null);
      }
    } catch (final IOException e) {
      throw new MavenExecutionException(
          "flox-controller CRD staging: cannot run `nix` (is the flox env active?)", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MavenExecutionException("flox-controller CRD staging interrupted", e);
    }
  }
}
