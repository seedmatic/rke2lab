package io.seedmatic.rke2lab.maven.staging;

import io.seedmatic.rke2lab.osgi.bnd.BootStackJar;
import io.seedmatic.rke2lab.osgi.bnd.Clause;
import io.seedmatic.rke2lab.osgi.bnd.EmbedCapability;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of dependency jars an exec-module must STAGE as OSGi bundles (copied intact under {@code
 * META-INF/bundles/} and excluded from the flat uber-jar), derived as a CLOSURE over what the
 * bundles declare — replacing the two hand-maintained pom lists. Mirrors the runtime install logic
 * ({@code BootPlanner.plan}) at build time, so the jars on disk match the bundles the runtime will
 * install.
 *
 * <p>The seed is the same two sources the runtime installs from:
 *
 * <ul>
 *   <li>OUR bundles — the {@link io.seedmatic.rke2lab.osgi.bnd.EmbedCapability#isDomain()
 *       model/edge} jars, recognised by the embed capability they self-declare.
 *   <li>The {@link BootStackJar boot-stack} — felix.scr/resolver + pax, recognised by symbolic
 *       name. The seed BY NATURE: nothing of ours imports felix.scr (DS wires by reflection), so no
 *       Import-Package closure reaches it; it must be named.
 * </ul>
 *
 * <p>From that seed we CLOSE over {@code Import-Package}, BOUNDED by what the host already provides
 * flat — the mirror of {@code BootPlanner.deriveSystemExports}. A package a staged bundle imports
 * pulls in its exporter(s) only when ALL of:
 *
 * <ul>
 *   <li>the import is MANDATORY — an {@code resolution:=optional} import does not force resolution
 *       in OSGi, so it does not force staging (this is what keeps felix.scr's optional {@code
 *       org.osgi.service.cm} from dragging in the whole compendium);
 *   <li>the package is NOT already in {@code system.packages.extra} — i.e. not imported by any of
 *       our model/edge bundles, since the runtime mirrors exactly those imports as host-flat
 *       exports. This is why fanning out from a model/edge stages nothing (their imports DEFINE the
 *       covered set) — only the BOOT-STACK's uncovered imports pull anything in.
 * </ul>
 *
 * <p>When several resolved bundles export a wired package we stage them ALL: multiple installed
 * exporters of a package is LEGAL in OSGi — the framework resolver selects the wire at resolution
 * time, by version and {@code uses:} constraints. We do not second-guess it, and we do not police
 * what the developer keeps on the classpath: a package that is both staged and left flat (e.g.
 * jackson's {@code com.fasterxml.jackson.*}, deliberately host-flat AND staged as a realm-library
 * bundle for the model world) is a legitimate per-realm copy in one case and a leaked aggregate
 * ({@code osgi.cmpn}) in another — the difference is the developer's intent, which a derivation
 * cannot read. Keeping the classpath clean (excluding aggregates like {@code osgi.cmpn} in the BOM,
 * backed by the build-parent {@code bannedDependencies}) is the developer's job; this class only
 * derives what to stage. The one exception the derivation DOES encode: a realm-library candidate
 * whose package the boot-stack already provides in-framework ({@code org.slf4j} via
 * pax-logging-api) is NOT staged — a second in-framework exporter would break slf4j 2.x resolution.
 * See {@code isRealmLibrary}.
 */
public record StagingClosure(
    List<ResolvedBundle> staged, List<String> trace, Set<String> realmLibraryGas) {

  /**
   * Compute the staging closure over an exec-module's resolved dependency jars. {@code
   * flatReferencedDualRealmGas} is the demand switch: the {@code groupId:artifactId} of every
   * {@code type=dual-realm} carrier a flat class of THIS assembly references (see {@link
   * DualRealmFlatDemand}). A dual-realm carrier IN the set is kept flat (staged AND shaded flat);
   * one NOT in it folds OSGi-only (staged as a plain bundle, excluded from the flat uber-jar). So
   * the flat copy exists IFF the host uses it — the invariant that supersedes the old {@code
   * DUAL_REALM_JUSTIFIED} gate.
   */
  public static StagingClosure compute(
      List<ResolvedBundle> resolved, Set<String> flatReferencedDualRealmGas) {
    return new Computation(resolved, flatReferencedDualRealmGas).run();
  }

  /** The {@code groupId:artifactId} keys of the staged jars, in discovery order. */
  public Set<String> stagedGas() {
    return staged.stream()
        .map(ResolvedBundle::ga)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * The staged jars to EXCLUDE from the flat uber-jar — staged minus the realm libraries (a realm
   * library is staged as a bundle AND kept flat in the host).
   */
  public Set<String> shadeExcludeGas() {
    final Set<String> excludes = new LinkedHashSet<>(stagedGas());
    excludes.removeAll(realmLibraryGas);
    return excludes;
  }

  /** Drives the fixpoint; one instance per computation so the state stays local and explicit. */
  private static final class Computation {

    private final List<ResolvedBundle> resolved;
    private final Set<String> flatReferencedDualRealmGas;
    private final Map<String, List<ResolvedBundle>> exportersByPackage = new LinkedHashMap<>();
    private final Set<String> hostFlatPackages = new LinkedHashSet<>();
    private final Set<String> bootStackSymbolicNames =
        java.util.Arrays.stream(BootStackJar.values())
            .map(BootStackJar::symbolicName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private final Map<String, ResolvedBundle> stagedByGa = new LinkedHashMap<>();
    private final Map<String, ResolvedBundle> realmLibraryByGa = new LinkedHashMap<>();
    private final Set<String> stagedExportedPackages = new LinkedHashSet<>();
    private final Deque<ResolvedBundle> frontier = new ArrayDeque<>();
    private final List<String> trace = new ArrayList<>();

    Computation(List<ResolvedBundle> resolved, Set<String> flatReferencedDualRealmGas) {
      this.resolved = resolved;
      this.flatReferencedDualRealmGas = flatReferencedDualRealmGas;
      indexExporters();
      indexHostFlatPackages();
    }

    StagingClosure run() {
      seed();
      seedRealmLibraries();
      close();
      propagateRealmLibraries();
      return new StagingClosure(
          new ArrayList<>(stagedByGa.values()), trace, Set.copyOf(realmLibraryByGa.keySet()));
    }

    /** Map every exported package to ALL the resolved bundles that export it. */
    private void indexExporters() {
      for (ResolvedBundle bundle : resolved) {
        if (!bundle.isBundle()) {
          continue;
        }
        for (String pkg : bundle.exports().names()) {
          exportersByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(bundle);
        }
      }
    }

    /**
     * The packages the host already serves flat — the build-time mirror of {@code
     * system.packages.extra}: every package a model/edge bundle OR our {@code type=dual-realm}
     * carrier imports. A {@code dual-realm} carrier is itself kept flat host-side, so the jackson
     * (etc.) packages IT imports must be served flat too, exactly as a domain's imports are. An
     * import whose package is here resolves against the host classloader, so its exporter stays
     * flat, never staged.
     */
    private void indexHostFlatPackages() {
      for (ResolvedBundle bundle : resolved) {
        if (bundle.embed().map(e -> e.isDomain() || e.isDualRealm()).orElse(false)) {
          hostFlatPackages.addAll(bundle.imports().names());
        }
      }
    }

    /**
     * Seed: our model/edge/runtime/dual-realm bundles (by capability) + the boot-stack (by symbolic
     * name). Dual-realm carriers are seeded here so a FOLDED one (no flat consumer in this
     * assembly) is still staged as a bundle — its realm-library (flat) marking, when it IS consumed
     * flat, is added by {@link #seedRealmLibraries()} on top of this idempotent staging.
     */
    private void seed() {
      for (ResolvedBundle bundle : resolved) {
        final Optional<EmbedCapability> embed = bundle.embed();
        if (embed.map(e -> e.isDomain() || e.isRuntime() || e.isDualRealm()).orElse(false)) {
          stage(bundle, "seed: embed type=" + embed.orElseThrow().type());
        } else if (bundle.symbolicName().map(bootStackSymbolicNames::contains).orElse(false)) {
          stage(bundle, "seed: boot-stack");
        }
      }
    }

    /**
     * Third-party OSGi bundles exporting a package a domain OR {@code type=dual-realm} bundle
     * imports — staged AND kept flat. A {@code library} (e.g. the codec) drives realm-library
     * detection just like a domain: the jackson datatype jars it imports (jackson-datatype-jdk8)
     * must be staged for the OSGi wire AND kept flat for the library's host copy.
     */
    private void seedRealmLibraries() {
      final Set<String> domainImports = new LinkedHashSet<>();
      for (ResolvedBundle b : resolved) {
        if (b.embed().map(e -> e.isDomain() || e.isDualRealm()).orElse(false)) {
          domainImports.addAll(b.imports().names());
        }
      }
      final Set<String> bootStackExports = bootStackExportedPackages();
      for (ResolvedBundle b : resolved) {
        if (isRealmLibrary(b, domainImports, bootStackExports)) {
          realmLibraryByGa.put(b.ga(), b);
          stage(b, "seed: realm library (a domain bundle imports its export)");
        }
      }
    }

    /**
     * Close the realm-library set over MANDATORY imports: a realm library is kept FLAT, and a flat
     * class can only load if every type it touches is also flat. So each package a realm library
     * imports must itself be served flat — and when a STAGED bundle is that package's exporter,
     * that exporter must become a realm library too (flat AND staged), transitively. This is what
     * carries the whole junit-platform stack flat: {@code junit-jupiter-engine} is a realm library
     * (a direct dep), it imports {@code org.junit.platform.engine.support.hierarchical}, exported
     * by {@code junit-platform-engine} — which is only transitive, so the seed pass left it
     * staged-only. The flat {@code JupiterTestEngine} then fails to load its superclass {@code
     * HierarchicalTestEngine} host-side. Promoting the exporter closes the gap. Guards mirror
     * {@code close()}: optional imports do not force it, a host-flat package (already served by the
     * exec's own flat tail) needs no promotion, and the boot-stack guard still forbids a second
     * in-framework exporter.
     */
    private void propagateRealmLibraries() {
      final Set<String> bootStackExports = bootStackExportedPackages();
      final Deque<ResolvedBundle> pending = new ArrayDeque<>(realmLibraryByGa.values());
      while (!pending.isEmpty()) {
        final ResolvedBundle library = pending.removeFirst();
        for (Clause imported : library.imports().clauses()) {
          final String pkg = imported.name();
          if (isOptional(imported) || hostFlatPackages.contains(pkg)) {
            continue; // optional, or already served by the host's own flat tail — nothing to keep.
          }
          for (ResolvedBundle exporter : exportersByPackage.getOrDefault(pkg, List.of())) {
            if (realmLibraryByGa.containsKey(exporter.ga())
                || !stagedByGa.containsKey(exporter.ga())
                || exporter.launcher()
                || exporter.embed().isPresent()
                || exporter.exports().names().stream().anyMatch(bootStackExports::contains)) {
              // already a realm library / not staged / the framework / one of ours / a second
              // in-framework exporter the boot-stack forbids — none becomes a new flat copy.
              continue;
            }
            realmLibraryByGa.put(exporter.ga(), exporter);
            trace.add(exporter.ga() + "  <-  realm-library: " + library.ga() + " imports " + pkg);
            pending.addLast(exporter);
          }
        }
      }
    }

    /**
     * The packages the boot-stack already provides in-framework (e.g. pax-logging-api → org.slf4j).
     */
    private Set<String> bootStackExportedPackages() {
      final Set<String> exports = new LinkedHashSet<>();
      for (ResolvedBundle b : resolved) {
        if (b.symbolicName().map(bootStackSymbolicNames::contains).orElse(false)) {
          exports.addAll(b.exports().names());
        }
      }
      return exports;
    }

    /**
     * A realm library — staged AND kept flat (dual). Two kinds:
     *
     * <ul>
     *   <li>OUR OWN dual-realm library: a bundle self-declaring {@code embed; type=dual-realm}
     *       (e.g. {@code gateway-document-codec}). It states its dual nature explicitly, so it is a
     *       realm library by declaration — no import analysis needed.
     *   <li>a THIRD-PARTY OSGi bundle (not ours, not a seam, not the launcher) that EITHER exports
     *       a domain import OR is DIRECTLY DECLARED by the exec-module. A direct declaration is the
     *       developer's explicit "keep it host-flat" intent — the parallel of a {@code
     *       type=dual-realm} self-declaring its dual nature — so a directly-declared third-party
     *       bundle that also gets staged (e.g. {@code gson}: the exec declares it compile AND the
     *       an edge's client pulls it into the staging closure) is kept flat too. Either signal is
     *       subject to the SAME boot-stack guard: a package already served in-framework by a
     *       boot-stack bundle (pax-logging-api exports {@code org.slf4j}) needs no realm-library
     *       copy — staging one would add a second in-framework exporter and break slf4j 2.x
     *       resolution.
     * </ul>
     */
    private boolean isRealmLibrary(
        ResolvedBundle b, Set<String> domainImports, Set<String> bootStackExports) {
      if (!b.isBundle() || b.launcher()) {
        return false;
      }
      if (b.embed().isPresent()) {
        // Ours: only a type=dual-realm carrier CAN live in both realms (staged + flat);
        // model/edge/record/seam never do. And a dual-realm one is kept flat ONLY where a flat
        // class of THIS assembly references it (the demand switch) — elsewhere it folds OSGi-only.
        return b.embed().orElseThrow().isDualRealm() && flatReferencedDualRealmGas.contains(b.ga());
      }
      // A third-party bundle whose exports the boot-stack already serves in-framework is never a
      // realm library — a second exporter would break resolution (slf4j). This guards BOTH signals.
      if (b.exports().names().stream().anyMatch(bootStackExports::contains)) {
        return false;
      }
      // A pure framework-binding leaf — every mandatory package it imports is one the
      // boot-stack already serves in-framework (e.g. slf4j-jdk14 imports
      // org.slf4j/.spi/.helpers/.event, all from pax-logging-api), and it exports nothing a
      // domain wires to. Such a bundle is a HOST binding onto the framework's provider: the
      // dev declares it direct to keep it flat, where slf4j routes onto the JDK JUL bus. The
      // boot-stack (pax-logging) ALREADY owns the slf4j provider in-framework, so staging
      // slf4j-jdk14 too would add a SECOND in-framework provider — wrong by ROLE, not merely
      // because it fails to resolve today. (It also happens not to resolve now — it requires
      // the osgi.serviceloader.registrar extender the boot-stack lacks — but that is
      // incidental: once that extender is integrated it COULD resolve, and it must STILL stay
      // flat-only. Resolving is not the same as belonging in-framework.) Symmetric to the
      // export guard above: that catches a provider the boot-stack duplicates by EXPORT; this
      // catches one that only binds onto it by IMPORT.
      if (bindsOnlyToBootStack(b, bootStackExports)) {
        return false;
      }
      if (b.directlyDeclared()) {
        return true;
      }
      for (String exported : b.exports().names()) {
        if (!ResolvedBundle.isOurs(exported) && domainImports.contains(exported)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Whether {@code b} is a pure framework-binding leaf: it has at least one mandatory import and
     * ALL of them are served in-framework by the boot-stack. Such a bundle (an slf4j backend
     * binding) only makes sense flat host-side onto the framework's provider — never staged as its
     * own bundle.
     */
    private static boolean bindsOnlyToBootStack(ResolvedBundle b, Set<String> bootStackExports) {
      boolean anyMandatory = false;
      for (Clause imported : b.imports().clauses()) {
        if (isOptional(imported)) {
          continue;
        }
        anyMandatory = true;
        if (!bootStackExports.contains(imported.name())) {
          return false;
        }
      }
      return anyMandatory;
    }

    /** Close over MANDATORY, host-uncovered Import-Package until no new bundle is pulled in. */
    private void close() {
      while (!frontier.isEmpty()) {
        final ResolvedBundle bundle = frontier.removeFirst();
        for (Clause imported : bundle.imports().clauses()) {
          final String pkg = imported.name();
          if (isOptional(imported)
              || hostFlatPackages.contains(pkg)
              || stagedExportedPackages.contains(pkg)) {
            // optional, served host-flat via system.packages.extra, or already provided by an
            // already-staged bundle — nothing new to pull in.
            continue;
          }
          for (ResolvedBundle exporter : exportersByPackage.getOrDefault(pkg, List.of())) {
            if (isStageable(exporter)) {
              stage(
                  exporter,
                  "closure: " + bundle.symbolicName().orElse(bundle.ga()) + " imports " + pkg);
            }
          }
        }
      }
    }

    private static boolean isStageable(ResolvedBundle exporter) {
      if (exporter.launcher()) {
        return false; // the framework itself — system bundle 0, never staged.
      }
      return exporter.embed().map(e -> !e.isSeam()).orElse(true); // a seam is host-flat.
    }

    private static boolean isOptional(Clause imported) {
      return "optional".equals(imported.attributes().get("resolution"));
    }

    private void stage(ResolvedBundle bundle, String reason) {
      if (stagedByGa.putIfAbsent(bundle.ga(), bundle) == null) {
        stagedExportedPackages.addAll(bundle.exports().names());
        frontier.addLast(bundle);
        trace.add(bundle.ga() + "  <-  " + reason);
      }
    }
  }
}
