---
name: pipeline-modules-destined-to-disappear
description: "The pipeline/ modules (pipeline-jgiven, pipeline-testkit, pipeline-port) are TRANSITIONAL and destined to disappear once the BDD-as-engine migration completes; the jGiven-by-two-paths mess and the unwired consult are symptoms of that unfinished migration, not standalone bugs."
metadata:
  type: project
---

**User frame (2026-07-08):** the disorder around jGiven dependencies is NOT an isolated
declaration bug — it "sits around the pipeline migration, which is not finished. To term, the
`pipeline` modules should disappear."

## RESOLVED 2026-07-09 — jGiven single-path done, wrap relocated to runtime

The two-path jGiven mess is GONE. The wrap moved out of the `pipeline/` aggregator to
`osgi/runtime/jgiven-wrap` (artifactId `jgiven-wrap`, BSN unchanged `io.seedmatic.rke2lab.jgiven.wrap`).
It is the ONE jGiven carrier; scenario-engine and every BDD consumer depend on it. The `pipeline/`
aggregator now holds only pipeline-port (the grammar) + pipeline-testkit/probe/probe-test (the
in-container jGiven testkit + its guard).

**LESSON — do NOT fold jGiven's export into scenario-engine (tried 2026-07-09, reverted).** The
tempting move was "scenario-engine IS the world of the playable, so let it export
com.tngtech.jgiven.* and delete the wrap entirely." It builds but breaks resolution: scenario-engine
is DUAL-REALM (its base package host-flat, its `.container` package in-container), and it is installed
in RUNNER-ONLY worlds too (e.g. DoctorPortInContainerTest installs it just for the `.container` runner
package, via withJUnitRunner). Re-exporting jGiven drags jGiven's whole tail (guava, gson,
jakarta.annotation, paranamer, jansi) into scenario-engine's Import-Package → those runner-only worlds,
which never install the tail, fail to resolve. Making the tail `resolution:=optional` "fixes" it only
by papering every import with optional — a smell that says the two concerns were wrongly merged. A
single-responsibility wrap, installed ONLY in worlds that actually play jGiven, avoids all of it.
Permanence of a dependency (jGiven is forever) justifies NOT decoupling the CHOICE, not merging the
PACKAGING. The grammar SEAM (pipeline-port) likewise never exports jGiven — that would put it in two
realms → LinkageError; separate bundles make it impossible by construction.

**Still true — the `pipeline-*` grammar modules are transitional.** `pipeline-port` (now
`Topic.Execution` only, jGiven-free after Checkpoint/Pipeline were killed) is the shared fluent
DERIVATION grammar, destined to be renamed (working name derivation/fold, on trial — see
[[pipeline-migration-strategy-revised]]). The testkit/probe modules are jGiven test assets, orthogonal
to the grammar.

**Measured state (2026-07-08), scenario-engine dependents:**
- LEGITIMATE (use the launcher/harness, no jGiven import): `cluster-edge`, `dbus-systemd-edge` (both
  use `OutOfContainerFrameworkExtension`); `seed-master` (uses `OsgiConnection` + `JUnitLauncherCore`
  + jGiven API — all real).
- SUSPECT (neither launcher nor jGiven types in src): `incus-edge`, `bbox-edge` — a `scenario-engine`
  dep they do not use in source (transitive/test residue or dead). Flag when the migration sweeps.
- `pipeline-jgiven` dependents: `cluster-bdd`, `doctor-core-test`, `manifests-core-test`,
  `pipeline-testkit`.

**The unwired `consult` is the SAME symptom.** In production there is NO
`@Component(service=ConsultingService.class)` — only `FakeConsultingService` publishes it. The real
`consult` lives on `Generalist`, assembled PER-PATIENT via `HealthSystem.admit(Patient)`, which is
called ONLY in doctor-core-test — never host-side. The host's
`awaitService(ConsultingService.class)` therefore resolves only against the fake today: the real
consult path is **not yet wired in prod**. This is a migration gap, not a broker-design problem —
the broker (Phase 1) must not try to invent the real-consult publication; that belongs to the
doctor's own migration increment. See [[gateway-is-rest-in-jvm-insight]] Phase 1.

**How to apply:** do NOT treat the jGiven dep cleanup as a standalone chore now. It is part of the
pipeline migration; fix it when that migration sweeps (and expect the `pipeline-*` modules to be
deleted, not repaired). When designing the seed-broker door, remember `consult` is asymmetric from
`assess`/`canonicalize` (per-patient admission, currently fake-only) — a known gap to carry, not to
paper over.
