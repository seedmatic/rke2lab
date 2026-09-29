---
name: pipeline-spec-recut-plan
description: "The executable plan for re-cutting pipeline-spec.adoc by NATURE (per specs-decompose-like-modules). Mapped 2026-07-08; NOT executed — blind execution is unsafe (no adoc link-checker in repo, ~15 inbound links depend on the #the-avant anchor, and the seed-vs-host-pipeline split is an unresolved judgment). This note is the map so the re-cut is review-and-execute, not discovery."
metadata:
  type: project
---

**Goal (from [[specs-decompose-like-modules]]):** `pipeline-spec.adoc` violates no-mixing — it fuses
three natures. Split it so each doc has ONE nature, homed where the reader looks.

**Section → nature → destination (mapped from the full read, 2026-07-08):**

- THESIS (BDD-as-engine, ~90%): C1/C2 context, dogfooding rationale, the compass, the founding
  constraint, the-model (5 decisions + scenario tree + fan-in + fail-fast), RunMode-preview
  (ExecutionCondition + PreviewExecutor), the DAG-gate. → **`docs/architecture/bdd/bdd.adoc`** (the
  `bdd/` dir already exists, holds `bdd-diagnostic-pattern.adoc`). This is a transverse PATTERN (the
  `-bdd` role + scenarios-as-engine).
- SEED ORCHESTRATION: ownership-and-the-seam, "OSGi world is detached by design", the placement &
  naming rule (`*Test`/`*Scenario`, `-core`/`-bdd`), migration. → a **seed** view. UNRESOLVED JUDGMENT:
  does this become `atlas/seed.adoc` (rename `atlas/host-pipeline.adoc`, which ALREADY is the seed/host
  view) or a new doc? The placement rule is arguably bdd-role content (stays in bdd.adoc); ownership/
  seam/detached is seed. Decide WITH the user — this is the "map first, decide after" point.
- THE AVANT (fluent Topic/State): already just a pointer to git `39fe4d8a`, not full text. → KEEP as a
  `[[the-avant]]` anchor wherever the thesis lands. DO NOT DELETE the anchor — see the link hazard.

**THE LINK HAZARD (why not blind):** no adoc link-checker exists; grep is the only guard. Inbound
links to `pipeline-spec.adoc` = ~30 across 15 dirs. Of these, ~15 target `#the-avant` specifically
(bootstrap-contract, provisioning-slice, context-registry, vcluster-gitops, vcluster-impl,
config-restructuring ×2, staged-post-cluster, systemd-architecture, manifest-conditional-inclusion,
manifests-architecture, bdd-diagnostic-pattern ×2, osgi-boot-decomposition ×3, README). The `[[the-avant]]`
anchor MUST survive the move or all 15 break silently.

**Mechanical steps (all grep-verifiable, git-reversible) when executing WITH the user:**

1. `git mv docs/architecture/osgi/pipeline-spec.adoc docs/architecture/bdd/bdd.adoc` (history + the
   `[[the-avant]]` anchor move together). Retitle "= BDD-as-engine — the seeding as jGiven scenarios".
2. Fix bdd.adoc's OWN 8 outbound links (dir changed osgi/→bdd/): `link:X` siblings become `link:../osgi/X`;
   `link:../atlas/Y` stays (bdd/ and osgi/ are both one level under architecture/). The 8:
   bdd-pipeline-poc-design, engine-lifecycle-spec, jgiven-osgi-testkit-handoff, osgi-boot-decomposition,
   pulumi-edge-handoff, staging-gates-governance, world-gateway-spec (→ seed-broker), ../atlas/host-pipeline.
3. Repoint all ~30 inbound links: `osgi/pipeline-spec.adoc` → `bdd/bdd.adoc`, recomputing the `../`
   prefix per linking-file depth (README uses `architecture/…`; siblings in osgi/ use `../bdd/bdd.adoc`;
   deeper dirs already use `../osgi/…` → `../bdd/…`). Preserve every `#the-avant` / `#anchor` suffix.
4. `grep -rl "osgi/pipeline-spec" docs/` == 0 as the acceptance check. Plus every `#the-avant` still
   resolves (grep the anchor exists in bdd.adoc).
5. Update README.adoc nav + integration-atlas the two link lines.
6. SEPARATE decision + step: the seed extraction (§SEED above) and whether `atlas/host-pipeline.adoc`
   renames to `atlas/seed.adoc` (breaks the `#the-bdd-as-engine-turn` deep-link — same repoint drill).

**Scope call (2026-07-08):** the vision commit `3fd85e6cb` is the net. The re-cut is a SEPARATE pass,
deferred to do WITH the user because (a) no link-checker → the 15 `#the-avant` links are a silent-break
risk, (b) the seed-vs-host-pipeline home is an unresolved judgment, (c) a pure rename without the split
would misname seed-content as bdd. See [[specs-decompose-like-modules]] [[gateway-is-rest-in-jvm-insight]].
