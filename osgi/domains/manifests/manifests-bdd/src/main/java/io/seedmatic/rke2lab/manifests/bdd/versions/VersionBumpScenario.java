package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.auth.contract.GithubWriterTokenMint;
import io.seedmatic.rke2lab.manifests.bdd.GhAppCase;
import io.seedmatic.rke2lab.manifests.contract.ManifestVersionsBumpInput;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.worktree.GitIdentity;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The component-version bump scenario — a production jGiven scenario told in the MANIFESTS DOMAIN's
 * own vocabulary, played IN-CONTAINER on its own {@link
 * io.seedmatic.rke2lab.manifests.contract.ManifestsCoordinate#VERSIONS} coordinate. Its own plant,
 * NOT a fork of the synthesis: it READS the pins (the {@link Component} SSOT enum), queries the
 * upstream GitHub releases, and — on {@code apply} — rewrites the source pin literals, refreshes
 * the vendored {@code release-<version>.yaml} manifests, and COMMITS the change to the worktree as
 * the rke2lab bot.
 *
 * <p>The report IS the runbook: each component is narrated as a nested step ({@code @NestedSteps} +
 * a {@link Narration} sub-stage), so the operator reads the diff — and, on apply, the bumps, the
 * staging and the bot commit — from the rendered runbook itself, never a side-channel log.
 *
 * <p>Its collaborators are INJECTED from the framework registry by the {@link OsgiService} bridge,
 * all bundle-side (unreachable to the flat host, which is exactly why the bump is a scion): the
 * {@link Worktree} (jgit stage/commit) and the {@link NdhKeystoreReader} (the tailnet {@code
 * authorityDomain} the bot email is minted from). The GitHub token for the release query is NOT
 * shelled here: it is minted ON DEMAND from the one org-owned App — the durable App credentials
 * revealed at {@link GhAppCase} + the {@link GithubWriterTokenMint} edge — empty for a standalone
 * CLI run with no sealed credentials, so the query then runs anonymously (rate-limited), never
 * against a personal {@code gh auth token}.
 */
@SeedScenario
public class VersionBumpScenario
    extends ScenarioTestBase<
        VersionBumpScenario.Given, VersionBumpScenario.When, VersionBumpScenario.Then>
    implements InputReceiver<ManifestVersionsBumpInput>,
        CellarReceiver<ScenarioCellar>,
        ScenarioPlayer.Playable {

  /** The inbound channel the {@code VersionsRunbookHandler} seeds the bump facet through. */
  @RegisterExtension
  public static final ScenarioInputSeed<ManifestVersionsBumpInput> INPUT =
      new ScenarioInputSeed<>(ManifestVersionsBumpInput.class, "manifests-versions-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ManifestVersionsBumpInput input;

  /** The in-container cellar (injected before the body) — where the sealed GitHub token is read. */
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(ManifestVersionsBumpInput input) {
    this.input = input;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_component_versions_are_bumped() {
    final ManifestVersionsBumpInput.BumpFacet facet =
        Objects.requireNonNull(input, "the bump facet was not seeded before the body").facet();
    final Cellar tx =
        Objects.requireNonNull(cellar, "the ScenarioCellar was not injected before the body");
    given().the_bump_policy(facet.level(), facet.apply(), facet.component());
    when().the_component_versions_are_processed(tx);
    then().the_pins_are_current_within_the_gate();
  }

  /**
   * Given: the operator's bump policy — the level ceiling, the apply flag, the component filter.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState BumpLevel level;
    @ProvidedScenarioState boolean apply;
    @ProvidedScenarioState Optional<Component> component;

    public Given the_bump_policy(BumpLevel level, boolean apply, Optional<Component> component) {
      this.level = level;
      this.apply = apply;
      this.component = component;
      return self();
    }
  }

  /** When: query upstream and report — or, on apply, rewrite the pins and commit as the bot. */
  public static class When extends Stage<When> {

    /** The tailnet authority whose {@code domain} scopes the bot email (single source of trust). */
    private static final String TAILNET_AUTHORITY = "mammoth-skate";

    /** The per-tool discriminator — the SUFFIX the minter appends to the rke2lab bot base. */
    private static final String TOOL = "manifests-bumper";

    /** The ndh SSH key the bot SSH-signs its commit with (git SSHSIG) — rke2lab's own key. */
    private static final String SIGNING_KEY = "github-signing";

    @ExpectedScenarioState BumpLevel level;
    @ExpectedScenarioState boolean apply;
    @ExpectedScenarioState Optional<Component> component;

    @OsgiService private Optional<Worktree> worktree = Optional.empty();

    /**
     * The current plot — the key the sealed GitHub token is revealed under; absent for a survey.
     */
    @OsgiService(await = false)
    private Optional<Parcel> parcel = Optional.empty();

    @OsgiService private Optional<NdhKeystoreReader> ndh = Optional.empty();

    // The on-demand push-token mint (auth-edge, cultivating): mints a FRESH token from the App
    // credentials for the release query, at the point of use. Absent for a standalone CLI bump (no
    // sealed App creds / a survey frontier) → the query runs anonymously.
    @OsgiService(await = false)
    private Optional<GithubWriterTokenMint> writerTokenMint = Optional.empty();

    @ScenarioStage Narration narration;

    @NestedSteps
    @As("the component versions are processed")
    public When the_component_versions_are_processed(@Hidden Cellar cellar) {
      final VersionBumper bumper = new VersionBumper(level, resolveGithubToken(cellar));
      if (apply) {
        applyBump(bumper);
      } else {
        reportBump(bumper);
      }
      return self();
    }

    private void reportBump(VersionBumper bumper) {
      for (VersionReport row : bumper.report()) {
        if (component.isPresent() && component.get() != row.component()) {
          continue;
        }
        narration.the_pin_$_reports_$(row.component().slug(), verdict(row, bumper.level()));
      }
    }

    private void applyBump(VersionBumper bumper) {
      final Worktree tree =
          worktree.orElseThrow(
              () -> new IllegalStateException("no Worktree service — cannot apply a bump"));
      final VersionBumper.BumpApplication application = bumper.apply(component, tree.root());
      for (VersionBumper.AppliedBump bump : application.bumps()) {
        narration.the_pin_$_is_$(bump.component().slug(), change(bump));
      }
      if (!application.anyChanged()) {
        narration.there_is_nothing_to_commit();
        return;
      }
      final NdhKeystoreReader keystore =
          ndh.orElseThrow(
              () -> new IllegalStateException("no ndh key-store — cannot mint the bot"));
      tree.stage(application.changedPaths());
      final GitIdentity identity =
          new GitBotIdentities(keystore.authorityDomain(TAILNET_AUTHORITY)).forTool(TOOL);
      // rke2lab signs its own bot commit with the ndh github-signing key (git SSHSIG).
      final String sha =
          tree.commit(
              commitMessage(application), identity, Optional.of(keystore.sshPrivate(SIGNING_KEY)));
      narration.the_change_is_committed_as_$_at_$(
          identity.name() + " <" + identity.email() + ">", sha);
    }

    /**
     * The GitHub token, from the ONE source of trust: mint a FRESH token from the one org-owned App
     * at the point of use — reveal the durable App credentials at {@link GhAppCase} (the ghapp
     * registration sealed them) and mint via the {@code auth} {@link GithubWriterTokenMint} edge.
     * The token is ephemeral and never stored, so it can't go stale (the trap a durable seal fell
     * into). Empty when there is no plot, no sealed credentials (a standalone CLI bump), or the
     * {@code cultivating}-gated mint edge is filtered out — the upstream release query then runs
     * anonymously (rate-limited), never against a personal {@code gh auth token}.
     */
    private Optional<String> resolveGithubToken(Cellar cellar) {
      return parcel
          .flatMap(plot -> cellar.fetch(plot, GhAppCase.GITHUB_APP, GithubAppMaterial.class))
          .flatMap(
              app ->
                  writerTokenMint.map(
                      mint -> mint.mint(app.appId(), app.installationId(), app.privateKeyPem())));
    }

    private String commitMessage(VersionBumper.BumpApplication application) {
      final StringBuilder body = new StringBuilder();
      body.append("chore(manifests): bump component versions (")
          .append(level.slug())
          .append(")\n\n");
      for (VersionBumper.AppliedBump bump : application.bumps()) {
        bump.newPin()
            .ifPresent(
                pin ->
                    body.append("* ")
                        .append(bump.component().slug())
                        .append(' ')
                        .append(bump.oldPin())
                        .append(" -> ")
                        .append(pin)
                        .append(bump.assetRefreshed() ? "  [pin + asset]" : "  [pin]")
                        .append('\n'));
      }
      return body.toString().stripTrailing() + "\n";
    }

    private static String verdict(VersionReport report, BumpLevel level) {
      if (report.bumpAvailable()) {
        return "pinned "
            + report.currentPin()
            + " -> bump to "
            + report.allowedTarget().orElseThrow();
      }
      if (!report.note().isEmpty()) {
        return "pinned " + report.currentPin() + " (" + report.note() + ")";
      }
      if (report.heldByGate()) {
        return "pinned "
            + report.currentPin()
            + " (latest "
            + report.upstreamLatest().orElseThrow()
            + " beyond the "
            + level.slug()
            + " gate)";
      }
      return "pinned " + report.currentPin() + " (current)";
    }

    private static String change(VersionBumper.AppliedBump bump) {
      return bump.newPin()
          .map(
              pin ->
                  bump.oldPin()
                      + " -> "
                      + pin
                      + (bump.assetRefreshed() ? " (pin + asset)" : " (pin)"))
          .orElse(bump.oldPin() + " unchanged (" + bump.note() + ")");
    }
  }

  /** The per-component narration — each call renders a nested step under the processing step. */
  public static class Narration extends Stage<Narration> {

    @As("$ $")
    public Narration the_pin_$_reports_$(@Quoted String component, @Quoted String verdict) {
      return self();
    }

    @As("$ $")
    public Narration the_pin_$_is_$(@Quoted String component, @Quoted String change) {
      return self();
    }

    @As("nothing to commit — every pin already current within the gate")
    public Narration there_is_nothing_to_commit() {
      return self();
    }

    @As("committed as $ at $")
    public Narration the_change_is_committed_as_$_at_$(
        @Quoted String identity, @Quoted String sha) {
      return self();
    }
  }

  /** Then: the run reached its verdict — the narrated report/commit is the outcome. */
  public static class Then extends Stage<Then> {

    public Then the_pins_are_current_within_the_gate() {
      return self();
    }
  }
}
