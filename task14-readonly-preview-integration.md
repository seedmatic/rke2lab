---
name: task14-readonly-preview-integration
description: "Task 14 integration check = export dev state / import into a throwaway test stack / pulumi PREVIEW only (non-mutant, Claude may run it) + run MedicalRecordDump.main (option b). Option a (in-run reader) is the treats()/in-run-doctor topic, deferred."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

How Task 14 (the end-to-end integration check of the medical-record query API) is to be done, decided
with the user 2026-06-09. Supersedes the plan's original "extend wip/sandbox to drive the full
pipeline mid-up".

**Corrections the user made to my framing (I was wrong 3x, these stick):**
1. `wip/sandbox` is a **resource** (a Pulumi program run as a scenario), NOT a library module. Do not
   pull it into the reactor; do not make the pipeline depend on it. It stays the FOUNDATION proof
   (mid-up, in-process, lock-free self-read; per-node consultationReport persists across runs).
2. The mechanism is **`pulumi stack export` (from dev) → `stack import` (into a throwaway test stack)
   → `pulumi preview`**. Preview is NON-MUTANT, so per CLAUDE.md Claude MAY run export/import/preview
   directly — Task 14 is a check CLAUDE runs, not a live op reserved to the user. "La stack au runtime
   de seed-master, on l'utilise qu'en mode preview, on ne provisionne pas le système."
3. seed-master IS the Pulumi resource, so running it executes the pipeline from INSIDE
   `controlplane.bdd` → package-private encapsulation stays intact. No public promotion, no sandbox
   module. (This killed my 3 bad options: move-to-reactor, promote-public, separate driver class.)

**Option (a) vs (b) — chose (b) (2026-06-09):**
- (b) = run `MedicalRecordDump.main` (already exists, package controlplane.bdd) as a SEPARATE process
  against the test stack/backend. Costs nothing to write; zero risk to provisioning; stays in the
  offline-dump scope we set. `main` already uses `StackHandle.attach` → it DOES exercise the live
  exportStack path (so (b) covers the live branch — my earlier claim that only (a) did was WRONG).
- (a) = wire reader+dump INTO seed-master's `Pulumi.run` mid-preview (guarded by `ctx.dryRun()`).
  Rejected for NOW: it is structurally the **in-run doctor** = the `treats()` topic
  ([[preview-whatif-topic]], [[doctor-remediation-model]]), explicitly out of scope; the preview-only
  guard is itself a smell; it puts doctor code in the central provisioning run. (a) IS the right long-
  term direction (the doctor consults DURING diagnosis; that is also where the OSGi federation +
  recruitment loop live, [[serviceloader-specialist-spi]]) → (a) becomes the first concrete step of
  the treats()/in-run topic, NOT this chantier.

**Technical caveat for the script (applies to both a/b):** `stack export/import` transfers the CURRENT
checkpoint, not necessarily the `.pulumi/history/` tree. So the reconstructed MedicalRecord will have
the current state as 1 visit; a multi-visit longitudinal history needs the history files copied too.
Resolve when scripting.

Relates to [[medical-record-query-api-state]]. Next: rewrite Task 14 in the plan as the
export/import/preview + dump check, run it (non-mutant), record observed YAML in
wip/sandbox/INTEGRATION.md.
