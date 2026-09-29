---
name: osgi-runtime-migration-state
description: "DESIGN phase (worktree design/osgi-runtime-migration, off design/target-module-layout @3190dc59): the FINAL OSGi push — rebuild the pre-OSGi services as NATIVE OSGi services up to the RUNTIME, until master can be provisioned under a real Felix framework. Deliverable = a spec in wip/specs/ that (1) maps the whole migration surface, (2) defines the runtime target (where Felix starts in main(), SCR vs bare registry, declare→publish→consume→boot order), (3) cuts it into testable slices (some -Plive gated). NO runtime code this phase — cartography + plan only. Cap decision ALREADY TAKEN (do not re-litigate): we GO to the OSGi runtime; the 'prod mounts no framework' argument does NOT block — prod-target IS an OSGi system. Born 2026-06-18."
metadata:
  node_type: memory
  type: project
---

## The cap decision (settled — do not re-open)

We go ALL THE WAY to a real OSGi runtime, until master can be provisioned under Felix. The objection
"prod doesn't mount a framework today" (the corrected fact #3 in [[step2-decomposition-state]]: Felix =
resolution ALGORITHM, not framework) does NOT block this phase — it describes the transitional state we
are now deliberately leaving. The prod-TARGET *is* an OSGi system.

## The key distinction to respect (geste A vs geste B)

- **Geste A — DECLARATION** (`@Component`/`@ServiceProvider`/`@Capability`, build-time): bnd reads the
  annotation and emits manifest headers / `OSGI-INF/*.xml`. NO runtime needed. Already proven on the
  bench ([[bnd-annotations-spike-state]] — annotations ARE the source of truth).
- **Geste B — CONSUMPTION** (`@Reference`, registry lookup, lifecycle): requires SCR (Declarative
  Services runtime) + a launched framework. This is the new lift this phase designs.
- The test harness has a REAL Felix (`FelixFrameworkExtension`, [[osgi-test-in-vscode-three-ways]]) but
  **`felix.scr` is NOT in the BOM** — so geste B is unproven; standing up SCR is part of the plan.

## What this phase produces (DESIGN, not impl)

A spec in `wip/specs/` (date-prefixed) that:
1. **Maps the migration surface** — every pre-OSGi service to rebuild as native OSGi. Anchor =
   [[osgi-logging-and-cli-debt]]: the ServiceLoader→DS sites (UnitResolver, NodeEnvContributorRegistry,
   DefaultManifestUpdateGate, + host IncusResourceBootstrap/EntryGatePolicyEnforcer; 5 META-INF/services
   files) across the 3 spaces (osgi/ host/ exec/).
2. **Defines the runtime target** — where Felix boots in `main()`, SCR-vs-bare-registry choice, the
   declare→publish→consume→boot ordering.
3. **Cuts into testable slices** — each independently validable; some `-Plive` gated.

PLUS a side-task: update `wip/specs/2026-06-17-target-module-layout.adoc` to document the **nature=exec**
axis (the 3rd axis — purity osgi/host + direction north/south + **nature exec**) that
[[exec-aggregator-state]] shipped but never wrote into the spec.

## Roadmap context (where this sits)

[[step2-decomposition-state]] froze a 6-stage static→dynamic roadmap (4 OSGi planes: resolution /
delivery=ConfigAdmin / activation=DS / registry). Shipped: stage 1 slice 1 (BootstrapConfig relocate),
the config-extender spike (stage 2), the bnd-annotations source-of-truth proof. The Maven re-layout
(osgi//host//exec/, steps 1–5) is DONE+merged into design/target-module-layout. THIS phase = stages 3–6
(the runtime lift): the part [[step2-decomposition-state]] flagged as "a real architectural lift, NOT
small increments — no container to host them today."

## ★ CARTOGRAPHY DONE + SPEC WRITTEN 2026-06-18 (this session)

Deliverable shipped: `wip/specs/2026-06-18-osgi-runtime-migration-design.adoc` (7 sections + slice
table). Side-task done: `wip/specs/2026-06-17-target-module-layout.adoc` now documents **axis 3 =
nature** (`exec/`), rewrote the 2-axis grid → 3 spaces, dropped `north-adapters` from `host/` (drivers
moved to `exec/`). THREE load-bearing findings (verified on HEAD bytecode + poms, not memory):

1. **The BOM ALREADY carries the Felix runtime — only `org.apache.felix.scr` is missing.** Present:
   `felix.framework` 7.0.5, `felix.configadmin` 1.9.26, `felix.metatype` 1.2.4, `felix.log` 1.3.0,
   `osgi.core` 8.0.0, the 3 `*.annotations`. So geste B is blocked by exactly TWO things: scr absent +
   no framework launched in `main()`. The lift is SHORT, not "stand up a runtime from scratch."
2. **The test harness already LAUNCHES a real framework.** `FelixFrameworkExtension`
   (osgi-bench-testkit) does `newFramework().init().start()`, `installFromClasspath(artifactId).start()`,
   `resolveBundles`. `MetatypeIntrospectionSpikeTest` proves the install-runtime-bundle-then-read-typed-
   service pattern. → every runtime slice validates on embedded Felix in SUREFIRE (no `-Plive`);
   `-Plive` reserved for the ONE slice that actualises a real master over Pulumi.
3. **Surface is small + clean:** 5 SPIs (4 singleton + `NodeEnvContributor`=6-impl aggregate-extender),
   5 META-INF/services files (rk2lab typo ALREADY fixed in step 5), 7 `ServiceLoader.load` sites. Pivot =
   `IncusResourceBootstrap.singleSpiProvider(Class<T>)` (the exactly-one helper = `@Reference` 1..1).

**The runtime target (spec §4):** Pulumi owns `main()` (`ApplicationPipeline.run`→`Pulumi.run(ctx)`),
so Felix boots **host-side INSIDE the Pulumi callback** (Pulumi outer, Felix inner, gRPC stays flat →
#1565 can't occur, the osgi-grpc-host spike already proved the TCCL-pinned seam). **SCR recommended over
bare registry** (continues the bnd-annotations source-of-truth decision). **Consume seam splits:** bundle
consumers → `@Component`+`@Reference` (declarative); the HOST seam (IncusResourceBootstrap,
EntryGatePolicyEnforcer — must NOT become bundles, they hold gRPC) → plain `getServiceReference` with
`system.packages.extra` sharing the API package (the bench's typed-access trick). Order =
declare(bnd,geste A)→publish(scr activates)→consume(@Reference|registry)→boot(actualise over Pulumi).

**Diagrams added (user asked — "c'est du lourd, aide-moi à valider"):** geste A/B crossing (§1), C4 L1
context (who owns the lifecycle — Felix born INSIDE the seed-master process, not around it), C4 L2
components (the two classloader worlds + the yellow consume-seam, gRPC never crosses), declare→publish→
consume→boot sequence (§4.4). **★ MANDATORY atlas before/after (§7, user: "pas de si, c'est obligatoire
en fin de spec"):** Diagram G (static ServiceLoader surface today) → H (live registry beside it, R1-R4
monotone) + verdict. THE honest point the ritual forces: R5 DELIBERATELY erases the blue ServiceLoader
box — the ONE justified non-additive step, named + `-Plive` gated (uniformity discipline: one mechanism,
not registry+legacy-cousin). Color convention copied from atlas: built `#eef/#88a`, neu `#dfd/#2a2`.
Read the durable atlas (`docs/architecture/integration-atlas.adoc`, 539 lines, §"The ritual" + Diagrams
E/F config exemplar) to stay faithful before drawing. Graduates as a new "runtime" per-subsystem atlas
view at merge.

★ ATLAS GRADUATED 2026-06-19: the durable atlas (`docs/architecture/integration-atlas.adoc`) now CARRIES
the runtime view (`[[runtime-view]]`, Diagrams I/J) — the graduation of spec §7's G/H, PLUS the two
post-spec decisions named in its verdict (the API-extraction `host/*-bridge-api` box; the proof reframe
R4-by-`pulumi preview` not `-Plive`). R1–R3 listed SHIPPED, R4 + API-extraction GREEN, R5 amber. So the
atlas is no longer stale (it had stopped at the bench/config view). Flip R4/extraction blue at their
merge. Do NOT re-graduate G/H — it's done.

**The 7 slices (spec §5):** R1 add felix.scr + prove publish/bind on embedded Felix; R2 declare 5 SPIs
`@Component` (geste A, ServiceLoader STAYS, zero behaviour change); R3 consume intra-bundle via @Reference
(NodeEnvContributorRegistry + DefaultManifestUpdateGate, osgi/manifests only); R4 boot seam in seed-master
(`-Plive`); R5 retire ServiceLoader/singleSpiProvider/META-INF (`-Plive`); R6 CLI consumers (recommend
shared exec/ Felix bootstrap, off critical path); R7 deferred = ConfigAdmin DELIVERY + DS ACTIVATION
config (stages 3-4). Stage-6 living registry = v2 horizon (substrate built by R1-R5, no live re-resolution).

## ★ The phase chain to main (the road, so we don't reconstruct it)

`design/target-module-layout` is the LONG-LIVED integration branch. Sub-phases branch off it and
re-merge into it; we touch `main` ONLY when master can be provisioned under Felix. Traced:

```
main
 └─ design/target-module-layout  (integration, in standby)
      ├─ [✓] layout — 5 steps (osgi/ host/ exec/), all SHIPPED
      ├─ [✓] osgi-runtime-migration — DESIGN (this spec), SHIPPED
      ├─ [ ] runtime impl slices R1–R6 (spec §5) — each its own worktree off the integration branch
      ├─ [ ] unitrepo-pulumi — the ACL/mediation seam (actualisation OSGi↔Pulumi engine)
      └─ [ ] live proof: provision master end-to-end (-Plive)  → ONLY THEN merge to main
```

Other deferred chantiers that fold in along the way (not blockers): doctor split bdd-core/bdd-ledger
(oracle-validated), R7 ConfigAdmin/DS config (stages 3-4), stage-6 living registry (v2 horizon).
The terminal gate to `main` = "capable of provisioning master", nothing less.

## Workspace / close discipline

- Worktree `design/osgi-runtime-migration`, branch same, base `design/target-module-layout` @3190dc59
  (our long-lived integration branch). External-worktree model. sops clean-filter noise on `.secrets`
  at session start (pre-existing, working tree clean — not my concern unless I touch it).
- MEMORY dir is `.claude/memory/` (NOT the path the boot prompt guessed); hub memories under
  `.claude/hub/memory/` (docrepo-dag-state, check-osgi-standard-before-modeling live there).
- CLOSE = commit everything (code AND `.claude/memory/`), build GREEN, then HAND OFF to the
  design/target-module-layout session for the squash-merge — **this session does NOT saw its own
  worktree** ([[merge-from-target-worktree]]). Act broadly without asking ([[standing-autonomy-except-runtime-config]]);
  when I do ask, lead with a (Recommended) option ([[ask-question-mark-recommended]]).
- Build-verify FULL `-Posgi -Dmaven.build.cache.skipCache=true` ([[build-verification-gotchas]]).

See [[step2-decomposition-state]] [[osgi-logging-and-cli-debt]] [[hub:docrepo-dag-state]]
[[bnd-annotations-spike-state]] [[hub:check-osgi-standard-before-modeling]] [[exec-aggregator-state]]
[[osgi-test-in-vscode-three-ways]].
</content>
</invoke>
