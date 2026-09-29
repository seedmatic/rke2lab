---
name: test-logging-chantier
description: "BACKLOG chantier: make OSGi/Felix/SCR + JUL logs visible and per-test configurable in tests. Today you CANNOT see why an SCR component stays unsatisfied — no logback-test.xml, no jul→slf4j bridge, and pax-logging is absent in the FelixFrameworkExtension topology. Three complementary layers + a latent substring bug to fix. Surfaced 2026-06-22 while debugging ssh-to-age-edge (had to SIMULATE the cause instead of reading logs)."
metadata:
  node_type: memory
  type: project
---

## Why this chantier exists

Debugging ssh-to-age-edge, a mandatory `@Reference` stayed unsatisfied so `ManifestSynthesisService`
never published — and the Felix/SCR diagnostic explaining WHY was invisible. The cause had to be found
by SIMULATING `locateOnClasspath` rather than reading a log line. Root issue: **test-time log output is
not configurable**, and OSGi diagnostics don't even reach logback in one of the two test topologies.

## The findings (facts, verified on the reactor 2026-06-22)

* **No `logback-test.xml` anywhere.** Only `exec/*/src/main/resources/logback.xml` (runtime). Tests run
  with default levels, so `org.apache.felix` / SCR diagnostics are below threshold = invisible.
* **No `jul-to-slf4j` bridge** — not a dependency, not in the BOM, `SLF4JBridgeHandler` never installed.
  So JUL sources (io.grpc, JDK) bypass logback entirely. This is WHY `GrpcChannelNoiseCapture` exists as
  a JUL-level extension (see [[seedlog-logback-migration-backlog]] — "io.grpc-via-JUL with no jul→slf4j
  bridge"). With the bridge, that noise would be plain logback, level-controllable.
* **Two test boot topologies, opposite logging fates:**
  ** `OsgiRuntime` (prod component; e.g. `HostSeamEmbeddedFelixTest`) — installs pax-logging → OSGi
     LogService reaches logback, but unfiltered (no test config to raise levels).
  ** `FelixFrameworkExtension` (testkit; `@OsgiSpike`/`@Osgi` tests) — does NOT install pax-logging, so
     Felix/SCR logs go nowhere at all.

## The design — three complementary layers (NOT substitutes)

Each log source reaches logback by a different path; the chantier wires all three to ONE backend:

[ source → path → lever ]
. our code (slf4j)        → direct                    → logback levels
. io.grpc / JDK (JUL)     → **jul→slf4j bridge**       → SLF4JBridgeHandler.install() + LevelChangePropagator, then logback levels
. Felix / SCR (LogService)→ **pax-logging**            → pax present (add it to the FelixFrameworkExtension topology) + logback levels

Then a **Jupiter extension** (idiom of `GrpcChannelNoiseCapture`: BeforeEach/AfterEach mutate then
restore) raises `org.apache.felix` / SCR / `org.osgi` levels FOR a given test and restores after —
per-test control, the original ask. Once the bridge + pax are in place the extension drives ONE backend
(logback), not JUL + logback separately.

Likely knock-on: the bridge may make `GrpcChannelNoiseCapture` obsolete (grpc noise becomes a logback
level). CONFIRM, don't presume — only retire it if the bridge genuinely subsumes it.

## Latent bug to fix in the same chantier

`FelixFrameworkExtension.locateBundle` (osgi/junit-testkit, ~line 238) has the SAME substring bug
(`p.toString().contains(artifact)`) just fixed in `OsgiRuntime.locateOnClasspath`: a worktree named
after an artifact poisons every classpath entry. Not exploited today, but identical trap — fix it to
leaf/module-dir matching when this chantier opens the testkit.

## Scope / sequencing

Touches `osgi/junit-testkit` (the extension + pax in FelixFrameworkExtension), the BOM (`jul-to-slf4j`),
and possibly retires `GrpcChannelNoiseCapture`. A DISTINCT chantier — own branch off
design/pre-integration, not folded into a feature. Discipline as usual: reactor verify + surefire count,
no @Deprecated/shim, integrate to design/pre-integration never main.

See [[seedlog-logback-migration-backlog]] (the JUL/grpc half), [[external-edges-chantier-handoff]]
(where it surfaced), [[osgi-logs-flow-to-host]] (pax-logging → host logback contract),
[[osgi-test-in-vscode-three-ways]].
