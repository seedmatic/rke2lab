---
name: dependency-analyze-gate-backlog
description: "BACKLOG: automate `mvn dependency:analyze-only` with failOnWarning=true in build-parent as the anti-drift gate the user runs by hand today. NOT done — the reactor has 215 analyze warnings, ~95% structural OSGi false positives that need a calibrated ignore set first. Holds the measured inventory so it need not be re-run."
metadata:
  node_type: memory
  type: project
---

## Goal (the user's, 2026-06-23)

The user runs `dependency:analyze` by hand regularly to catch dependencies that have DRIFTED
(declared-but-unused / used-but-undeclared). The target is to AUTOMATE it as a build gate:
`maven-dependency-plugin` `analyze-only` bound to a phase with `failOnWarning=true`, in `build-parent`
(plugin already there in `pluginManagement`, version 3.11.0, but bound to NO phase → inert today).

## Why deferred

A first full-reactor `dependency:analyze-only -DignoreNonCompile=false` (2026-06-23) reported **215
warnings, BUILD FAILURE**. Filtering the known test-aggregator noise, the drift falls into 4
STRUCTURAL false-positive categories that OSGi makes invisible to bytecode analysis — turning the gate
on without a calibrated ignore set would fail the build on legitimate declarations. That calibration
(a judgement call per dependency × 40 modules) is a chantier of its own.

## The measured inventory (so we don't re-run it)

**False positives to IGNORE (structural, legitimate):**
1. `org.osgi.annotation.versioning` (15×, provided) — used ONLY in `package-info.java` `@Version`
   (SOURCE retention) → invisible to analyze. Inherited from bundle-parent.
2. **`runtime`-scope deps** (slf4j-api, pax-logging-api/logback, felix.scr, jansi, DS-trio runtime,
   paranamer, jakarta.annotation-api…) — never referenced in bytecode by definition; needed at runtime.
   *Since 2026-06-25:* the DS TEST stack (`org.apache.felix.scr` + `org.osgi.service.component` +
   `org.osgi.util.promise` + `org.osgi.util.function`, all scope=test) moved INTO `bundle-test-parent`
   (a `withScr()` in-container test installs them bundle-to-bundle; SCR loads them at run time, so
   bytecode never references them → "unused declared" on EVERY `-test` fragment). Ignore them ONCE on
   bundle-test-parent's own analyze, not per-fragment. `org.osgi.service.component` ALSO carries the
   runtime DTOs `ScrDiagnostics` reads — there it IS used in bytecode (used-undeclared would be wrong).
3. **Bundles installed into Felix by classpath substring** (bench-host, bench-config, bench-scr-consumer,
   jgiven-probe, jgiven-probe-test, jgiven-wrap, junit-testkit provided) — resolved at RUN time by the
   testkit, never in bytecode → "unused declared" but indispensable.
4. **Test aggregators** (`junit-jupiter` → unused, while `junit-jupiter-api` → used-undeclared; plus
   `jgiven-junit5`, `mockito-core`, `mockito-junit-jupiter` unused when a module doesn't touch them).
   Inherited from build-parent. DECIDED ignore strategy: `ignoredUsedUndeclaredDependencies =
   org.junit.jupiter:*` + `ignoredUnusedDeclaredDependencies` listing the aggregators.

**REAL drift candidates to examine one-by-one (compile scope, "unused declared"):**
`jackson-databind` (2×), `guava`, `gson`, `ipaddress`, `dbus-java-transport-tcp`, some
`felix.framework:compile`. Each is either genuinely unused OR used via reflection/resources — needs a
look, not a blind ignore.

## Approach when picked up

Two calibration shapes were on the table (user leaned none yet, deferred whole thing):
- **Wide gate + structural ignores**: failOnWarning=true, ignore the 4 categories by pattern, then fix
  the real compile drift. Keeps test-dep drift visible (what the dedup work aimed at).
- **`ignoreNonCompile=true` (narrow gate)**: only compile/provided analysed; kills categories 2-3-4 at
  a stroke; just ignore `annotation.versioning` + fix compile drift. Loses TEST-dep drift coverage.

Related to the test-dep dedup just shipped (commit 327c8c4c, [[felixframeworkextension-renamed-outofcontainer]]
sibling work): `bundle-test-parent` single-sourced the fragments' recipe — the gate would protect that
from re-drifting. See [[dbus-systemd-edge-spec-state]].
