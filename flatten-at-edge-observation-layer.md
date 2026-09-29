---
name: flatten-at-edge-observation-layer
description: Design DECIDED (2026-07-01) — host-side OutputContributor SPI + central OutputContributionRegistry; structured internally, flatten only at the Pulumi output edge. Surfaced by null-safety on the systemd runtime-status path.
metadata:
  type: project
---

Surfaced 2026-07-01 while null-safety-hardening `exec/seed-master`; **design now DECIDED** (was an
open brainstorm). The null in `SeedNodeBootstrapWatcher` was a symptom — the disease is a value that
**flattens to `Map<String,Object>` at PRODUCTION time instead of at the Pulumi output edge**, forcing
an internal consumer to re-parse an untyped map with `toBoolean`/`toInt`/`stringValue` +
`@Nullable Object`. Classic "the null points at the real error, at the source."

## WHY systemd diverged (the diagnosis — NOT a special need)
Not a logic need — the **number of consumers**. An output with ONE reader (the Pulumi edge) can
flatten at production undetectably: no internal leg carries the map. The 3 sibling summaries
(`registrySummary`/`imageBuildSummary`/`manifestSynthSummary`) are here — assembled inline via
`Map.of(...)` in the resource builder straight from the already-structured `bootstrapResult`, handed
only to `OutputBuilder`. They already honour "structured in, flat at the edge" (their `toOutputMap()`
is just anonymous/hand-written). systemd is the FIRST output to gain a SECOND, internal consumer
(the watcher, which polls) — its producer `SeedSystemdAdapterRuntimeStatusSnapshot.snapshot()` was
modelled on the OUTPUT shape (K8s envelope apiVersion/kind/status), returns `Map`, so the flat map
travels internally to the watcher. **The defect was latent in the design; adding a 2nd consumer bit.**

## THE AMORCE (proof the pattern already works)
`controlplane.bdd.ObservationView` is a typed record with TWO edge projections: `toWire()` (seam
Document) + `toOutputMap()` (Pulumi output). It stays structured internally, flattens only at each
boundary. `OutputBuilder` already does `policy.toOutputMap()` (L82) and `bbox.summaryMap()` (L96) —
named projections. So the contract exists as a CONVENTION; it's just not enforced.

## THE DECISION (2026-07-01) — host-side SPI + central registry
User drove it (not OSGi, not ServiceLoader): a **central component defines a SPI, the others
contribute, the component orchestrates and guarantees.** Stay host-side (nearly everything is
host-side today). Chosen mechanism: **explicit registry + `add()`** (NOT ServiceLoader/META-INF —
contributors are known, stateful (hold state in fields), deterministic order, testable by injecting
the list; matches the codebase "pass instances, no static discovery" discipline). Rejected:
ServiceLoader (needs no-arg + can't feed state, unless state moves to `contribute(ctx)` — user chose
field-state instead); OSGi `@Component`/`@Reference` (would reopen the host↔OSGi seam; premature).

```java
interface PulumiOutputContributor { String namespace(); Map<String,Object> contribute(); }

final class PulumiOutputRegistry {                 // the central component
  PulumiOutputRegistry add(PulumiOutputContributor c) { ... }   // fluent
  Map<String,Object> assemble() { ... }  // GUARANTEES: unique namespace, non-null, deterministic order
}
```
Names: `PulumiOutput*` (not bare `Output*`) — these are the Pulumi STACK outputs, not logs/files.

**WHERE it lives (2026-07-01):** the PURE SPI + registry (`PulumiOutputContributor`,
`PulumiOutputRegistry` — only `String`+`Map`, zero seed-master type) go in the **`pulumi-edge`**
module (`host/pulumi/pulumi-edge`, pkg `io.seedmatic.rke2lab.pulumi.edge`), beside `StackHandle`/
`SnapshotView` — it IS a Pulumi-edge concept. Verified safe: `seed-master` already depends on
`pulumi-edge` (pom L45) and `pulumi-edge` does NOT depend on `controlplane` → no cycle. `pulumi-edge`
is already `@NullMarked` (this session) so the SPI is born null-clean. The stateful IMPLEMENTATIONS
(`SystemdRuntimeStatusReport`, registry/image/manifest contributors — they hold `bootstrapResult`/
probe) stay in `controlplane` and depend on the edge SPI. Peer-model: the edge owns the contract,
controlplane contributes.
The registry is the value-add over a bare `putAll`: duplicate namespace = named error (not silent
overwrite), null contribution rejected, completeness known, and **`contribute()` is the ONLY flatten
point** — called once, by the registry, never by an internal consumer.

## IMPLEMENTATION PLAN (todo order)
1. `OutputContributor` (SPI) + `OutputContributionRegistry` (central, guarantees).
2. `SystemdRuntimeStatusReport implements OutputContributor` — record (Status enum
   {ok/execution-error/deferred-preview}, `Optional<SystemdStatusSnapshot> snapshot`, `String summary`);
   `contribute()` builds the K8s envelope; factories `ok(snapshot)`/`executionError(msg)`/`deferredPreview()`.
   `SeedSystemdAdapterRuntimeStatusSnapshot.snapshot()`/`deferredPreview()` return the Report (no Map).
3. `SeedNodeBootstrapWatcher` reads `report.snapshot()` TYPED → delete `toBoolean`/`toInt`/`stringValue`,
   the `@Nullable Object` params, and `YamlSummaryContext`'s map field. **Null fixed at the source.**
4. Migrate registry/imageBuild/manifestSynth to contributors (records + field state + `contribute()`);
   `ResourceManager.ResourceCreationResult` (L64-74) stops carrying any raw `Map`/`Object` summary.
5. `OutputBuilder.buildOutputs` becomes: build registry, `.add(...)` each, `return assemble()`. Core
   metadata (git/provisioning/build/runtime, L26-101) becomes contributors or one "core" contributor.

## KEY FILES
- `exec/.../controlplane/pipeline/OutputBuilder.java` — today's omniscient hub (pulls each summary by
  name L82-101); melts into the registry.
- `exec/.../controlplane/systemd/SeedSystemdAdapterRuntimeStatusSnapshot.java` — `snapshot()` L32-65
  flattens too early; `deferredPreview()` L36 has NO underlying probe (dry-run) → the Report must
  represent "no snapshot" (hence `Optional<SystemdStatusSnapshot>`).
- `exec/.../controlplane/resources/SeedNodeBootstrapWatcher.java` — the re-parser (the null lives here).
- `exec/.../controlplane/resources/ResourceCreationPipeline.java` L206-209/284 — the 2 snapshot edge
  sites; L232-234 the 3 sibling summaries born as `Map.of()`.
- `exec/.../controlplane/resources/ResourceManager.java` L64-74 — `ResourceCreationResult` holds the
  raw maps to purge.
- `osgi/.../systemd-port/.../SystemdStatusSnapshot.java` — the already-typed port record the Report wraps.
- Whiteboard: `.claude/claude-preview.adoc`.

## SPEC WRITTEN (2026-07-01) — the two gates, DESIGN not built
The brainstorm converged and is now GRAVED as a spec (per [[specs-current-at-brainstorm-end]] — a
settled design absent from docs/ is drift). `docs/architecture/osgi/two-gates-spec.adoc` (+ docs/README
entry, 📐 Design) specifies BOTH gates together, because they are the same idea twice:
- **living gate (IN)** — `LiveGate` (preview/up state, `through(live, deferred)`) held by the pipeline;
  replaces 7 scattered `Deployment.getInstance().isDryRun()` reads; FIXES a real bug (readiness preview
  hangs 1 min on `waitForKubeconfigPublished` because `ClusterReadinessScenario.checking` calls the
  live probe unconditionally — `System.setProperty(JGIVEN_DRY_RUN)` only skips the REPORT, not the
  step bodies).
- **output gate (OUT)** — `PulumiOutputContributor` + `PulumiOutputRegistry` (both already CREATED in
  pulumi-edge, null-clean, NOT yet wired), single flatten point, unique-key guarantee; `OutputBuilder`
  melts into `registry.add(...).assemble()`.
- **`SystemdRuntimeStatusReport`** — domain-pure record `{Status{OK,EXECUTION_ERROR}, Optional<Snapshot>,
  summary}`; deferred-preview is NOT a status (it's the living gate closed), the contributor builds
  that envelope. Watcher reads it typed → helpers + `@Nullable Object` deleted at the source.
THE RULE: crossing a boundary (touch the live system, or write the stack) is the PIPELINE's decision,
never the domain's — the pipeline is the sole orchestrator of both gates (OutputBuilder is in the
`pipeline` package too). LiveGate/Registry are MECHANISMS the pipeline holds, not orchestrators.
Build order in the spec: (1) living gate first (fixes the hang, commit alone), (2) systemd report,
(3) output registry, (4) sweep the remaining isDryRun sites. Whiteboard `.claude/claude-preview.adoc`
holds the frozen C4. STILL OWED: the ATLAS entry (capability + scenario) — deferred INTO the broad
atlas↔codebase reconciliation chantier (much shipped off-atlas since 2026-06-30), not done piecemeal.

See [[nullaway-jdk25-recipe]] for the arc state this was found in. See [[specs-current-at-brainstorm-end]].
