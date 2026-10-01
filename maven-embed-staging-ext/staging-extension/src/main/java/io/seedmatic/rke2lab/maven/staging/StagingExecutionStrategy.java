package io.seedmatic.rke2lab.maven.staging;

import io.seedmatic.rke2lab.osgi.bnd.BootStackJar;
import io.seedmatic.rke2lab.osgi.bnd.EmbedCapability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-sources the OSGi bundle staging by reconfiguring, in place, the shade and
 * maven-dependency-plugin executions of each exec JAR — replacing the two hand-maintained pom lists
 * with one derivation ({@link StagingClosure}) from what the bundles declare.
 *
 * <p>It is a {@link MojosExecutionStrategy} because that is the only hook that can reconfigure a
 * plugin: Maven obtains the strategy by a SINGLE {@code container.lookup} (highest {@link Priority}
 * wins) and the strategy DRIVES the execution list, calling {@code runner.run(execution)} per mojo.
 * Mutating an execution's {@link Xpp3Dom} BEFORE we delegate the run lands BEFORE Maven
 * builds+configures the mojo ({@code getConfiguredMojo} runs inside {@code run}). A
 * MojoExecutionListener cannot do this (it fires AFTER getConfiguredMojo; proven), nor can mutating
 * project.getModel() (its copy is frozen into the plan; proven).
 *
 * <p>The dependency set must be resolved before we can derive — but {@code project.getArtifacts()}
 * is empty until a resolution-requiring mojo (shade) runs, which is too late to reconfigure it. So
 * we resolve EXPLICITLY via {@link ProjectDependenciesResolver} at the head of {@code execute}.
 *
 * <p>This {@code @Priority(20)} wins the lookup over Maven's default AND the build-cache strategy
 * ({@code @Priority(10)}); to preserve whatever was there, it DELEGATES the actual run to the
 * highest-priority OTHER strategy (build-cache if present, else Maven's default) — it decorates, it
 * does not replace.
 */
@Named
@Singleton
@Priority(20)
public class StagingExecutionStrategy implements MojosExecutionStrategy {

  private static final Logger log = LoggerFactory.getLogger(StagingExecutionStrategy.class);

  private final Map<String, MojosExecutionStrategy> strategies;
  private final ProjectDependenciesResolver dependenciesResolver;
  private final RepositorySystem repositorySystem;

  @Inject
  public StagingExecutionStrategy(
      Map<String, MojosExecutionStrategy> strategies,
      ProjectDependenciesResolver dependenciesResolver,
      RepositorySystem repositorySystem) {
    this.strategies = strategies;
    this.dependenciesResolver = dependenciesResolver;
    this.repositorySystem = repositorySystem;
  }

  @Override
  public void execute(
      List<MojoExecution> mojoExecutions,
      MavenSession session,
      MojoExecutionRunner mojoExecutionRunner)
      throws LifecycleExecutionException {
    final boolean staging = mojoExecutions.stream().anyMatch(StagingExecutionStrategy::isShade);
    final List<ResolvedBundle> resolved = staging ? resolveBundles(session) : null;
    if (staging) {
      // Inject the STAGING artifact-items BEFORE delegating: stage-embedded-bundles runs at
      // generate-resources (before compile) and the staged SET is demand-independent (every
      // domain/dual-realm bundle is staged regardless). The SHADE excludes are NOT injected here —
      // they encode the dual-realm FLAT-keep, a per-assembly demand read from the exec's OWN
      // compiled classes (DualRealmFlatDemand), which do not exist until compile runs during the
      // delegated loop. So the wrapped runner injects them the moment before the shade mojo runs.
      injectStaging(session, mojoExecutions, resolved);
    }
    final MojoExecutionRunner runner =
        staging
            ? shadeExcludeInjectingRunner(mojoExecutionRunner, session, resolved)
            : mojoExecutionRunner;
    delegate().execute(mojoExecutions, session, runner);
    if (staging) {
      // Run the staging-law gate AFTER delegating: compile has now populated target/classes, so the
      // gate can read the exec's REALM_BOUNDARY governance AND self-scan its host classes. Gating
      // before compile saw an empty (cold) tree — no governance anchor, host classes unseen, and
      // the
      // build failed before compile could ever fill it (a self-deadlock). Running here also matches
      // the gate's declared fail-AT-end intent: collect every violation, fail once, at the true
      // end.
      enforceGates(session, resolved, locateDocsDir(session));
    }
  }

  /**
   * Inject the staging artifact-items (the bundles copied intact under {@code META-INF/bundles/}).
   * The staged SET is demand-independent — every domain/runtime/dual-realm bundle is staged
   * regardless of whether a flat class consumes it — so it is computed with an EMPTY demand and
   * injected up front, before the generate-resources stage mojo runs.
   */
  private void injectStaging(
      MavenSession session, List<MojoExecution> mojoExecutions, List<ResolvedBundle> resolved) {
    final StagingClosure closure = StagingClosure.compute(resolved, Set.of());
    int stageAdded = 0;
    for (MojoExecution execution : mojoExecutions) {
      if (isStageBundles(execution)) {
        stageAdded = injectStagingArtifactItems(execution, closure);
      }
    }
    log.info(
        "[osgi-staging] {}: derived {} bundles; +{} staging items",
        session.getCurrentProject().getArtifactId(),
        closure.staged().size(),
        stageAdded);
  }

  /**
   * Wrap the runner so the shade excludes are injected the instant BEFORE the shade mojo runs —
   * when compile has populated the exec's {@code target/classes}, so {@link DualRealmFlatDemand}
   * sees the exec's OWN host classes (e.g. a scenario referencing a {@code type=dual-realm}
   * contract type). Injecting up front instead read a cold, pre-compile tree and folded such a
   * carrier OSGi-only → the flat host copy vanished → a runtime {@code NoClassDefFoundError}.
   * Mutating the execution's config here still lands BEFORE {@code getConfiguredMojo} (it runs
   * inside the delegated run) — the constraint every staging reconfiguration depends on. The gate
   * ({@link #enforceGates}) already runs post-compile for the same reason; this aligns the demand
   * switch with it.
   */
  private MojoExecutionRunner shadeExcludeInjectingRunner(
      MojoExecutionRunner delegate, MavenSession session, List<ResolvedBundle> resolved) {
    return execution -> {
      if (isShade(execution)) {
        final StagingClosure closure =
            StagingClosure.compute(resolved, flatReferencedDualRealmGas(session, resolved));
        final int added = injectShadeExcludes(execution, closure);
        log.info(
            "[osgi-staging] {}: +{} shade excludes (post-compile dual-realm flat demand)",
            session.getCurrentProject().getArtifactId(),
            added);
      }
      delegate.run(execution);
    };
  }

  /**
   * Run every staging law over the whole bundle set in fail-AT-end mode: collect every violation
   * from every bundle, report each at the {@link EnforcementLevel} its bundle declares for that
   * {@link StagingGate} ({@code @GovernedBy}, default ERROR), then fail ONCE with the complete
   * ERROR list. A per-bundle throw would surface only the first offender, forcing a
   * fix-rebuild-repeat loop to discharge the debt one bundle at a time; accumulating shows the
   * whole debt in a single run so it can be cleared in one pass. WARN violations are logged (a
   * visible, shrinking backlog); IGNORE is silent.
   *
   * <p>The laws (each governable per bundle, default ERROR):
   *
   * <ul>
   *   <li>{@link StagingGate#CONTRACT_PURITY} — a {@code type=contract} bundle may export records /
   *       enums / sealed ADT roots AND service interfaces, but no concrete class. Delegated to a
   *       {@link ContractPurity} instance OF each contract bundle.
   *   <li>{@link StagingGate#SPEC_COVERAGE} — a bundle may export only types named in a {@code
   *       docs/} spec or marked {@code @Transitional}. Delegated to a {@link SpecCoverage} instance
   *       OF each bundle. A {@code null} docs dir (not found) skips this law rather than failing
   *       spuriously.
   * </ul>
   */
  private void enforceGates(
      MavenSession session, List<ResolvedBundle> resolved, java.nio.file.Path docsDir)
      throws LifecycleExecutionException {
    if (docsDir == null) {
      log.warn(
          "[osgi-staging] docs/ not found from the reactor root — spec-coverage check skipped");
    }
    final GateReport report = new GateReport();
    for (ResolvedBundle bundle : resolved) {
      final Map<StagingGate, EnforcementLevel> governance = bundle.governance().levels();
      if (bundle.embed().map(EmbedCapability::isContract).orElse(false)) {
        report.record(
            StagingGate.CONTRACT_PURITY,
            governance,
            bundle,
            bundle.contractPurity().violations(),
            "exports a concrete class (only records / enums / sealed ADT / interfaces allowed)");
      }
      if (docsDir != null && bundle.isBundle() && bundle.embed().isPresent()) {
        report.record(
            StagingGate.SPEC_COVERAGE,
            governance,
            bundle,
            bundle.specCoverage(docsDir).violations(),
            "exports types absent from docs/ specs and not @Transitional");
      }
      if (bundle.isBundle() && bundle.embed().isPresent()) {
        report.record(
            StagingGate.INSTANCE_DISCIPLINE,
            governance,
            bundle,
            bundle.instanceDiscipline().violations(),
            "exports public static behaviour helpers (pass instances; factories exempt)");
      }
    }

    // ---- REALM_BOUNDARY: no class references a type unreachable in its realm ----
    // The bundle-only packages: everything a bundle-SIDE carrier exports — a domain (model/edge) OR
    // a contract. A contract is staged, not flat, and not a seam, so its exported vocabulary
    // (manifests.contract, …) is exactly as unreachable from the flat host as a domain's. Deciding
    // "forbidden" on isDomain() alone left contracts out, so a flat class naming a contract type
    // (EntryGatePolicyEnforcer → ManifestUpdateGate) leaked past the gate to a runtime
    // NoClassDefFoundError. isDomain() || isContract() closes that hole.
    final Set<String> forbidden = new java.util.LinkedHashSet<>();
    for (ResolvedBundle b : resolved) {
      if (isBundleSide(b)) {
        forbidden.addAll(b.ourExportedPackages());
      }
    }
    if (!forbidden.isEmpty()) {
      // Flat realm: the exec's own classes + the flat tail + the seams. Its visible set is every
      // package that loads flat (our flat packages + the seam exports); a forbidden (bundle-only)
      // package referenced from here cannot be loaded by the flat host classloader at runtime.
      final Set<String> flatVisible = new java.util.LinkedHashSet<>();
      final List<ResolvedBundle.ClassEntry> flatClasses = flatRealmClasses(session, resolved);
      for (ResolvedBundle b : resolved) {
        if (!b.launcher() && !isBundleSide(b)) {
          flatVisible.addAll(b.ourExportedPackages()); // seams included — they are flat too.
        }
      }
      final RealmBoundary flat = new RealmBoundary("flat", forbidden, flatVisible);
      final List<String> flatViolations = new ArrayList<>();
      for (ResolvedBundle.ClassEntry c : flatClasses) {
        flatViolations.addAll(flat.violations(c.binaryName(), c.bytes()));
      }
      // Attribute every flat-realm violation at the governance of the exec project (its
      // package-info).
      report.record(
          StagingGate.REALM_BOUNDARY,
          execGovernance(session),
          execPseudoBundle(session),
          flatViolations,
          "flat-realm classes reference bundle-only packages (host/seam leak)");

      // Bundle realms: each bundle-side carrier (domain + contract) may reference only its own +
      // imported + system packages.
      for (ResolvedBundle b : resolved) {
        if (!isBundleSide(b)) {
          continue;
        }
        final Set<String> visible = new java.util.LinkedHashSet<>(b.ourExportedPackages());
        visible.addAll(b.imports().names());
        final RealmBoundary realm =
            new RealmBoundary("bundle:" + b.symbolicName().orElse(b.ga()), forbidden, visible);
        final List<String> v = new ArrayList<>();
        for (ResolvedBundle.ClassEntry c : b.classEntries()) {
          v.addAll(realm.violations(c.binaryName(), c.bytes()));
        }
        report.record(
            StagingGate.REALM_BOUNDARY,
            b.governance().levels(),
            b,
            v,
            "bundle-realm classes reference packages they do not import (OSGi-internal leak)");
      }
    }

    // ---- DUPLICATE_REALM_CLASS: no package lives flat AND in a staged bundle at once ----
    // The closure tells us which jars stage as bundles; everything else (non-launcher) shades flat.
    // A staged bundle's exported package that ALSO appears flat is one class in two realms — the
    // loader-constraint collision that surfaces as a LinkageError when an instance crosses the
    // seam. Attributed to the EXEC, not the bundle: a duplication is a property of THIS assembly
    // (the same bundle in an exec without the flat copy would not collide), so it is governed by
    // the
    // exec's package-info — exactly as REALM_BOUNDARY attributes its flat-realm leaks to the exec.
    final StagingClosure closure =
        StagingClosure.compute(resolved, flatReferencedDualRealmGas(session, resolved));
    final Set<String> stagedGas = closure.stagedGas();
    final Set<String> flatPackages = new java.util.LinkedHashSet<>();
    final java.nio.file.Path execClasses =
        java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
    for (ResolvedBundle.ClassEntry c : ResolvedBundle.classEntriesOf(execClasses)) {
      addPackageOf(flatPackages, c.binaryName());
    }
    for (ResolvedBundle b : resolved) {
      if (b.launcher() || stagedGas.contains(b.ga())) {
        continue; // the framework, and the staged bundles, are NOT flat.
      }
      for (ResolvedBundle.ClassEntry c : b.classEntries()) {
        addPackageOf(flatPackages, c.binaryName());
      }
    }
    final Set<String> seamSurface = new java.util.LinkedHashSet<>();
    for (ResolvedBundle b : resolved) {
      if (b.embed().map(EmbedCapability::isSeam).orElse(false)) {
        seamSurface.addAll(b.exports().names());
      }
    }
    final DuplicateRealmClass duplicate = new DuplicateRealmClass(flatPackages, seamSurface);
    final List<String> duplicateViolations = new ArrayList<>();
    for (ResolvedBundle b : closure.staged()) {
      for (String pkg : duplicate.violations(b)) {
        duplicateViolations.add(b.symbolicName().orElse(b.ga()) + " exports " + pkg);
      }
    }
    report.record(
        StagingGate.DUPLICATE_REALM_CLASS,
        execGovernance(session),
        execPseudoBundle(session),
        duplicateViolations,
        "staged bundles export packages that ALSO live flat in this assembly (one class in two"
            + " realms → LinkageError)");

    // DUAL_REALM_JUSTIFIED is retired: a dual-realm carrier's flat copy is no longer gated after
    // the
    // fact — the staging closure MATERIALISES it only where a flat class references it (the demand
    // switch, DualRealmFlatDemand → StagingClosure.compute). "Flat copy present ⟺ flat consumer
    // present" now holds by construction, per (carrier, exec-assembly) pair, so a carrier consumed
    // flat by one exec (manifests-cli) and OSGi-only by another (seed-outcluster) is each handled
    // right,
    // and there is nothing left for a gate to catch.

    // ---- SYNTHESIS_PATTERN: a synthesis phase implements Phase.Execution and pushes via its Sink.
    // Phases live in the manifests-core bundle (its internal.synthesis *Phase classes); the scan
    // still spans the exec's target/classes too (the dual-surface scan REALM_BOUNDARY also uses),
    // so a stray phase in host code would attribute to the exec's package-info.
    report.record(
        StagingGate.SYNTHESIS_PATTERN,
        execGovernance(session),
        execPseudoBundle(session),
        ManifestsSynthesisPattern.violations(
            ResolvedBundle.classEntriesOf(
                java.nio.file.Path.of(
                    session.getCurrentProject().getBuild().getOutputDirectory()))),
        "synthesis phases that implement Phase without a nature (Execution)");
    for (ResolvedBundle b : resolved) {
      if (b.launcher()) {
        continue; // the framework carrier holds no phases.
      }
      report.record(
          StagingGate.SYNTHESIS_PATTERN,
          b.governance().levels(),
          b,
          ManifestsSynthesisPattern.violations(b.classEntries()),
          "synthesis phases that implement Phase without a nature (Execution)");
    }

    // ---- REALM_WIRING_INTEGRITY: the assembled uber-jar actually boots and fully resolves ----
    // The embedded-boot smoke test: boot the exec's own -exec.jar in an isolated child classloader
    // and observe the REAL resolver — every staged bundle must resolve, and the flat
    // (system-bundle)
    // and installed-bundle export sets must stay disjoint. A static manifest scan cannot see an
    // unsatisfied import, an unattachable fragment, or a split package that slipped past
    // deriveSystemExports; only the live resolver can. Attributed to the EXEC (a wireability
    // property
    // of THIS assembly), governed by its package-info like the other exec-level laws. A missing
    // -exec.jar is not a violation: booting is skipped, not failed.
    final java.io.File uber = execUberJar(session);
    final List<String> wiringViolations = new ArrayList<>();
    if (uber != null && uber.isFile()) {
      try {
        wiringViolations.addAll(new BootedRealmDiagnostic(uber.toPath()).observe());
      } catch (Exception ex) {
        wiringViolations.add("the assembled framework failed to boot: " + ex);
      }
    }
    report.record(
        StagingGate.REALM_WIRING_INTEGRITY,
        execGovernance(session),
        execPseudoBundle(session),
        wiringViolations,
        "the assembled uber-jar must boot with every bundle resolved and the flat/bundle export sets"
            + " disjoint");

    report.flush();
  }

  /**
   * A BUNDLE-SIDE carrier — one whose exported packages live only in a staged bundle, never flat: a
   * domain (model/edge) or a contract. Both are the "forbidden" set the flat realm may not reach
   * and are themselves excluded from the flat realm. A seam ({@code type=seam}) is NOT bundle-side
   * (it is host-flat), a library is dual (flat too), the launcher is the framework — none belong
   * here.
   */
  private static boolean isBundleSide(ResolvedBundle b) {
    return b.embed().map(e -> e.isDomain() || e.isContract()).orElse(false);
  }

  /**
   * The dual-realm carriers THIS exec's flat/host realm actually references — the demand switch
   * {@link StagingClosure#compute} turns on to keep a dual-realm carrier flat (referenced) or fold
   * it OSGi-only (not). Computed from {@link #flatRealmClasses}, the one view that can see the
   * exec's own {@code target/classes} (a flat consumer like the manifests-cli version bumper lives
   * THERE, not in a resolved dependency).
   */
  private Set<String> flatReferencedDualRealmGas(
      MavenSession session, List<ResolvedBundle> resolved) {
    final List<ResolvedBundle> dualRealms =
        resolved.stream()
            .filter(b -> b.embed().map(EmbedCapability::isDualRealm).orElse(false))
            .toList();
    return DualRealmFlatDemand.flatReferencedGas(dualRealms, flatRealmClasses(session, resolved));
  }

  /**
   * The flat/host realm's compiled classes: the exec's own {@code target/classes} PLUS every
   * non-launcher, non-bundle-side dependency's classes (the flat tail + the seams). The set a
   * host/seam leak is scanned in ({@code REALM_BOUNDARY}), and the set whose references drive the
   * demand switch for a {@code type=dual-realm} library's flat copy ({@link DualRealmFlatDemand}).
   */
  private static List<ResolvedBundle.ClassEntry> flatRealmClasses(
      MavenSession session, List<ResolvedBundle> resolved) {
    final List<ResolvedBundle.ClassEntry> flat =
        new ArrayList<>(
            ResolvedBundle.classEntriesOf(
                java.nio.file.Path.of(
                    session.getCurrentProject().getBuild().getOutputDirectory())));
    for (ResolvedBundle b : resolved) {
      if (b.launcher() || isBundleSide(b)) {
        continue; // the framework, and bundle-side carriers (domain + contract), are not flat.
      }
      flat.addAll(b.classEntries());
    }
    return flat;
  }

  /**
   * Add the package of a {@code com/foo/Bar} binary name to {@code packages} (dotted, no class).
   */
  private static void addPackageOf(Set<String> packages, String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    if (slash > 0) {
      packages.add(binaryName.substring(0, slash).replace('/', '.'));
    }
  }

  /**
   * Accumulates each gate's violations by the {@link EnforcementLevel} their bundle declares: ERROR
   * lines become the single aggregated build failure, WARN lines a visible backlog logged at the
   * end, IGNORE is dropped. The {@code @GovernedBy(gate, level)} default is ERROR, so an
   * unspecified domain is locked by default.
   */
  private final class GateReport {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<StagingGate, int[]> tally =
        new java.util.EnumMap<>(StagingGate.class); // [errors, warnings]

    void record(
        StagingGate gate,
        Map<StagingGate, EnforcementLevel> governance,
        ResolvedBundle bundle,
        List<String> violations,
        String what) {
      if (violations.isEmpty()) {
        return;
      }
      final EnforcementLevel level = governance.getOrDefault(gate, EnforcementLevel.ERROR);
      if (level == EnforcementLevel.IGNORE) {
        return;
      }
      final String line =
          "  [" + gateLabel(gate) + "] " + bundle.ga() + " " + what + ": " + violations;
      final int[] counts = tally.computeIfAbsent(gate, g -> new int[2]);
      if (level == EnforcementLevel.ERROR) {
        errors.add(line);
        counts[0] += violations.size();
      } else {
        warnings.add(line);
        counts[1] += violations.size();
      }
    }

    void flush() throws LifecycleExecutionException {
      logSummary();
      if (!warnings.isEmpty()) {
        log.warn(
            "[osgi-staging] {} staging-law violation(s) at WARN (@GovernedBy(...,WARN)) — visible"
                + " backlog, raise to the ERROR default once cleared:\n{}",
            warnings.size(),
            String.join("\n", warnings));
      }
      if (!errors.isEmpty()) {
        throw new LifecycleExecutionException(
            "osgi-staging: "
                + errors.size()
                + " staging-law violation(s) at ERROR — fix each, mark it @Transitional(to,spec), or"
                + " lower the gate to @GovernedBy(gate, WARN) on the package-info while the debt is"
                + " paid down:\n"
                + String.join("\n", errors));
      }
    }

    /**
     * One line per gate: how many violations it found, split ERROR vs WARN — the état des lieux.
     */
    private void logSummary() {
      final StringBuilder summary = new StringBuilder();
      for (StagingGate gate : StagingGate.values()) {
        final int[] counts = tally.getOrDefault(gate, new int[2]);
        summary
            .append("\n  ")
            .append(gateLabel(gate))
            .append(": ")
            .append(counts[0])
            .append(" error, ")
            .append(counts[1])
            .append(" warn");
      }
      log.info("[osgi-staging] gate summary (violations by gate):{}", summary);
    }

    private String gateLabel(StagingGate gate) {
      return gate.name().toLowerCase().replace('_', '-');
    }
  }

  /** The current exec project's governance, read from its own compiled package-info classes. */
  private Map<StagingGate, EnforcementLevel> execGovernance(MavenSession session) {
    final java.nio.file.Path classes =
        java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
    final Map<StagingGate, EnforcementLevel> levels = new java.util.EnumMap<>(StagingGate.class);
    for (ResolvedBundle.ClassEntry c : ResolvedBundle.classEntriesOf(classes)) {
      if (c.binaryName().endsWith("package-info")) {
        GovernanceReader.readInto(c.bytes(), levels);
      }
    }
    return levels;
  }

  /** A ResolvedBundle view of the exec module itself, for the report's ga() label. */
  private ResolvedBundle execPseudoBundle(MavenSession session) {
    final MavenProject p = session.getCurrentProject();
    return ResolvedBundle.read(p.getGroupId(), p.getArtifactId(), p.getVersion(), null);
  }

  /**
   * The assembled, self-contained uber-jar of the exec — the shade {@code exec}-classified
   * artifact, carrying the flat host classes + Felix + SCR + slf4j PLUS the bundles under {@code
   * META-INF/bundles}. This is the ONLY jar a child classloader can boot to observe the real
   * wiring; the plain main artifact is the un-shaded module jar and holds no launcher. Read from
   * the attached artifacts (classifier {@code exec}), falling back to the conventional {@code
   * <finalName>-exec.jar} on disk.
   */
  private static java.io.File execUberJar(MavenSession session) {
    final MavenProject project = session.getCurrentProject();
    for (org.apache.maven.artifact.Artifact attached : project.getAttachedArtifacts()) {
      if ("exec".equals(attached.getClassifier()) && attached.getFile() != null) {
        return attached.getFile();
      }
    }
    final java.io.File byPath =
        new java.io.File(
            project.getBuild().getDirectory(), project.getBuild().getFinalName() + "-exec.jar");
    return byPath.isFile() ? byPath : null;
  }

  /**
   * The repo's {@code docs/} directory, found by walking up from the current module's basedir — the
   * one tree a Mojo can reach (via the session) that a bundle classloader cannot. {@code null} if
   * no ancestor holds a {@code docs/} directory.
   */
  private static java.nio.file.Path locateDocsDir(MavenSession session) {
    java.nio.file.Path dir = session.getCurrentProject().getBasedir().toPath();
    while (dir != null) {
      final java.nio.file.Path docs = dir.resolve("docs");
      if (java.nio.file.Files.isDirectory(docs)) {
        return docs;
      }
      dir = dir.getParent();
    }
    return null;
  }

  /**
   * Resolve the project's full compile+runtime dependency set, with each jar's file on disk, PLUS
   * the boot stack re-resolved by coordinate.
   *
   * <p>The boot stack is {@code optional} in {@code osgi/runtime} so it stays off the host JCL
   * (bundle-on-jcl-is-wrong-classpath invariant) — which means it is ABSENT from this resolved
   * graph. But the closure must still see it to stage it. So we re-resolve each {@link
   * BootStackJar} directly by its coordinate (version from the project's managed-version map, i.e.
   * the BOM) and fold it into the set the closure seeds from. The graph gives us OUR embed bundles
   * + their flat tail; the registry gives us the third-party boot stack the optional scope hid.
   */
  private List<ResolvedBundle> resolveBundles(MavenSession session)
      throws LifecycleExecutionException {
    final MavenProject project = session.getCurrentProject();
    final DefaultDependencyResolutionRequest request =
        new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
    request.setResolutionFilter(
        new ScopeDependencyFilter(List.of(JavaScopes.COMPILE, JavaScopes.RUNTIME), List.of()));
    final DependencyResolutionResult result;
    try {
      result = dependenciesResolver.resolve(request);
    } catch (DependencyResolutionException ex) {
      throw new LifecycleExecutionException(
          "osgi-staging could not resolve dependencies of " + project.getArtifactId(), ex);
    }
    final Set<String> directGas = directlyDeclaredGas(result);
    final List<ResolvedBundle> bundles = new ArrayList<>();
    for (Dependency dependency : result.getResolvedDependencies()) {
      final org.eclipse.aether.artifact.Artifact a = dependency.getArtifact();
      final ResolvedBundle bundle =
          ResolvedBundle.read(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile());
      bundles.add(directGas.contains(bundle.ga()) ? bundle.asDirectlyDeclared() : bundle);
    }
    bundles.addAll(resolveBootStack(session, project));
    return bundles;
  }

  /**
   * The {@code groupId:artifactId} keys the exec-module declares DIRECTLY — the root graph node's
   * immediate children. A direct third-party dependency is the developer's explicit "I need this
   * host-flat" intent (the parallel of a {@code type=dual-realm} self-declaring its dual nature);
   * the closure treats such a bundle as a realm library so it is staged AND kept flat. The jar
   * carries no directness signal — only the graph shape does — so it is read here, once, from the
   * resolution result.
   */
  private static Set<String> directlyDeclaredGas(DependencyResolutionResult result) {
    final org.eclipse.aether.graph.DependencyNode root = result.getDependencyGraph();
    if (root == null) {
      return Set.of();
    }
    final Set<String> direct = new LinkedHashSet<>();
    for (org.eclipse.aether.graph.DependencyNode child : root.getChildren()) {
      final org.eclipse.aether.artifact.Artifact a = child.getArtifact();
      if (a != null) {
        direct.add(a.getGroupId() + ":" + a.getArtifactId());
      }
    }
    return direct;
  }

  /**
   * Re-resolve the {@link BootStackJar} registry by coordinate. The version is the one the project
   * manages (the BOM line); resolution pulls the jar's file from the local/remote repo regardless
   * of the optional scope that kept it out of the graph above.
   */
  private List<ResolvedBundle> resolveBootStack(MavenSession session, MavenProject project)
      throws LifecycleExecutionException {
    final Map<String, org.apache.maven.artifact.Artifact> managed = project.getManagedVersionMap();
    final List<ResolvedBundle> stack = new ArrayList<>();
    for (BootStackJar jar : BootStackJar.values()) {
      final String key = jar.groupId() + ":" + jar.artifactId();
      final org.apache.maven.artifact.Artifact managedArtifact = managed.get(key + ":jar");
      if (managedArtifact == null) {
        throw new LifecycleExecutionException(
            "osgi-staging: boot-stack jar "
                + key
                + " has no managed version (expected a BOM line)");
      }
      stack.add(resolveByCoordinate(session, jar, managedArtifact.getVersion()));
    }
    return stack;
  }

  private ResolvedBundle resolveByCoordinate(MavenSession session, BootStackJar jar, String version)
      throws LifecycleExecutionException {
    final ArtifactRequest request =
        new ArtifactRequest()
            .setArtifact(new DefaultArtifact(jar.groupId(), jar.artifactId(), "jar", version))
            .setRepositories(session.getCurrentProject().getRemoteProjectRepositories());
    try {
      final ArtifactResult resolved =
          repositorySystem.resolveArtifact(session.getRepositorySession(), request);
      final org.eclipse.aether.artifact.Artifact a = resolved.getArtifact();
      return ResolvedBundle.read(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile());
    } catch (ArtifactResolutionException ex) {
      throw new LifecycleExecutionException(
          "osgi-staging could not resolve boot-stack jar "
              + jar.groupId()
              + ":"
              + jar.artifactId()
              + ":"
              + version,
          ex);
    }
  }

  /** Add a shade {@code <exclude>ga</exclude>} per staged bundle, skipping ones already listed. */
  private int injectShadeExcludes(MojoExecution execution, StagingClosure closure) {
    final Xpp3Dom config = configurationOf(execution);
    final Xpp3Dom excludes = child(child(config, "artifactSet"), "excludes");
    final Set<String> present = childValues(excludes);
    int added = 0;
    for (String ga : closure.shadeExcludeGas()) {
      if (present.add(ga)) {
        final Xpp3Dom exclude = new Xpp3Dom("exclude");
        exclude.setValue(ga);
        excludes.addChild(exclude);
        added++;
      }
    }
    return added;
  }

  /**
   * Add a dependency-plugin {@code <artifactItem>} per staged bundle, skipping ones already listed.
   */
  private int injectStagingArtifactItems(MojoExecution execution, StagingClosure closure) {
    final Xpp3Dom config = configurationOf(execution);
    final Xpp3Dom artifactItems = child(config, "artifactItems");
    final Set<String> present = new LinkedHashSet<>();
    for (Xpp3Dom item : artifactItems.getChildren("artifactItem")) {
      present.add(textOf(item, "groupId") + ":" + textOf(item, "artifactId"));
    }
    int added = 0;
    for (ResolvedBundle bundle : closure.staged()) {
      if (present.add(bundle.ga())) {
        artifactItems.addChild(artifactItem(bundle));
        added++;
      }
    }
    return added;
  }

  private static Xpp3Dom artifactItem(ResolvedBundle bundle) {
    final Xpp3Dom item = new Xpp3Dom("artifactItem");
    appendChild(item, "groupId", bundle.groupId());
    appendChild(item, "artifactId", bundle.artifactId());
    appendChild(item, "version", bundle.version());
    appendChild(item, "destFileName", bundle.stagedFileName());
    return item;
  }

  /** The strategy to actually run the executions — the best one that is NOT this decorator. */
  private MojosExecutionStrategy delegate() {
    return strategies.entrySet().stream()
        .filter(e -> !(e.getValue() instanceof StagingExecutionStrategy))
        .map(Map.Entry::getValue)
        .max(java.util.Comparator.comparingInt(StagingExecutionStrategy::priorityOf))
        .orElseThrow(
            () -> new IllegalStateException("no delegate MojosExecutionStrategy to run the build"));
  }

  private static int priorityOf(MojosExecutionStrategy strategy) {
    final Priority p = strategy.getClass().getAnnotation(Priority.class);
    return p == null ? 0 : p.value();
  }

  private static boolean isShade(MojoExecution execution) {
    return "maven-shade-plugin".equals(execution.getArtifactId())
        && "shade".equals(execution.getGoal());
  }

  private static boolean isStageBundles(MojoExecution execution) {
    return "maven-dependency-plugin".equals(execution.getArtifactId())
        && "stage-embedded-bundles".equals(execution.getExecutionId());
  }

  private static Xpp3Dom configurationOf(MojoExecution execution) {
    final Object config = execution.getConfiguration();
    if (config instanceof Xpp3Dom dom) {
      return dom;
    }
    final Xpp3Dom created = new Xpp3Dom("configuration");
    execution.setConfiguration(created);
    return created;
  }

  /**
   * Get-or-create a named child element (the shade {@code artifactSet}/{@code excludes} may be
   * absent).
   */
  private static Xpp3Dom child(Xpp3Dom parent, String name) {
    final Xpp3Dom existing = parent.getChild(name);
    if (existing != null) {
      return existing;
    }
    final Xpp3Dom created = new Xpp3Dom(name);
    parent.addChild(created);
    return created;
  }

  private static Set<String> childValues(Xpp3Dom parent) {
    final Set<String> values = new LinkedHashSet<>();
    for (Xpp3Dom c : parent.getChildren()) {
      values.add(c.getValue());
    }
    return values;
  }

  private static String textOf(Xpp3Dom parent, String name) {
    final Xpp3Dom c = parent.getChild(name);
    return c == null ? null : c.getValue();
  }

  private static void appendChild(Xpp3Dom parent, String name, String value) {
    final Xpp3Dom c = new Xpp3Dom(name);
    c.setValue(value);
    parent.addChild(c);
  }
}
