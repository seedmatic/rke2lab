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
 * Stages the {@code ClusterAdoption} CRD (single-sourced from the rke2-adoption-controller flake
 * input, never vendored) into manifests-core's resources BEFORE the reactor executes — the twin of
 * {@link FloxControllerCrdStagingParticipant}, for the second CRD the manifest synthesis emits into
 * the cluster's {@code crds} layer ({@code Rke2AdoptionControllerManifestsUnit}).
 *
 * <p><b>Why here.</b> The CRD is staged by {@code nix run .#stage-rke2-adoption-controller-crd}.
 * The nix {@code buildReactorExe} runs it in its buildPhase; a plain {@code ./mvnw} build does NOT
 * — so without this bridge a controller re-lock would leave the on-disk CRD stale (a legitimate
 * cache hit shipping the old schema). {@code afterProjectsRead} runs ONCE, after the POMs are read
 * but before any project is hashed/built.
 *
 * <p><b>Guards.</b> Skipped when {@code RKE2LAB_CRD_STAGED} is set (the nix build already staged in
 * its buildPhase and a nested {@code nix run} in that sandbox has no daemon/network — the SAME
 * marker the flox-controller participant honours, so one nix build sets it once and both skip).
 * Skipped when manifests-core is not in the reactor, so unrelated {@code -pl} builds pay no
 * nix-eval tax. Skipped when {@code -Dflox.crd-staging.skip=true} — the shared explicit opt-out for
 * a build with no {@code nix} on PATH.
 */
@Named
@Singleton
public class Rke2AdoptionControllerCrdStagingParticipant extends AbstractMavenLifecycleParticipant {

  private static final Logger log =
      LoggerFactory.getLogger(Rke2AdoptionControllerCrdStagingParticipant.class);

  private static final String STAGED_MARKER = "RKE2LAB_CRD_STAGED";
  private static final String SKIP_PROPERTY = "flox.crd-staging.skip";
  private static final String CRD_PACKAGING_MODULE = "manifests-core";
  private static final String STAGE_APP = ".#stage-rke2-adoption-controller-crd";

  @Override
  public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
    if (System.getenv(STAGED_MARKER) != null) {
      log.info(
          "ClusterAdoption CRD staging: skipped ({} set — the nix build already staged it)",
          STAGED_MARKER);
      return;
    }
    if (skipRequested(session)) {
      log.info("ClusterAdoption CRD staging: skipped (-D{}=true)", SKIP_PROPERTY);
      return;
    }
    final boolean packagesCrd =
        session.getAllProjects().stream()
            .anyMatch(p -> CRD_PACKAGING_MODULE.equals(p.getArtifactId()));
    if (!packagesCrd) {
      return; // this reactor does not build the CRD-packaging module — nothing to stage
    }

    final File root = session.getRequest().getMultiModuleProjectDirectory();
    log.info("ClusterAdoption CRD staging: nix run {} (in {})", STAGE_APP, root);
    try {
      final int rc =
          new ProcessBuilder("nix", "run", STAGE_APP).directory(root).inheritIO().start().waitFor();
      if (rc != 0) {
        throw new MavenExecutionException(
            "ClusterAdoption CRD staging failed: `nix run " + STAGE_APP + "` exited " + rc,
            (Throwable) null);
      }
    } catch (final IOException e) {
      throw new MavenExecutionException(
          "ClusterAdoption CRD staging: cannot run `nix` (is the flox env active?)", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MavenExecutionException("ClusterAdoption CRD staging interrupted", e);
    }
  }

  /**
   * {@code -Dflox.crd-staging.skip=true} (a CLI {@code -D} lands in userProperties, else system).
   */
  private static boolean skipRequested(final MavenSession session) {
    String v = session.getUserProperties().getProperty(SKIP_PROPERTY);
    if (v == null) {
      v = session.getSystemProperties().getProperty(SKIP_PROPERTY);
    }
    return Boolean.parseBoolean(v);
  }
}
