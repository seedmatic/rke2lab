---
name: seeded-history-automation-api
description: "To give the medical-record a clinical history to reconstruct (dev has none): seed a TEST stack via the Automation API in JAVA test tooling (exportStack→mutate Map→importStack), visits TAGGED seeded; importStack(StackDeployment) verified to exist in pulumi 1.28.0."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Decided with the user 2026-06-09, extending Task 14. dev's 283 deployments carry ZERO
consultationReport (dev never ran the doctor write-side), so the medical-record reconstructs with
empty reports — plumbing proven, clinical content not. To get a real clinical history to reconstruct
(and the substrate for the dogfooding/recruitment loop, which needs a record that HAS a history):

**Approach (user's two corrections to my first instinct):**
1. NOT hand-edited JSON / bash / python. Write the seeding tooling **in Java, on the TEST classpath**
   (composes with StackHistoryFixture, typed, same serialization as prod). My bash/python reflex was
   the wrong medium.
2. Inject via the **Automation API**, not by sed-ing checkpoints: `exportStack()` → mutate the
   `StackDeployment` deployment Map (inject consultationReport into resource nodes' `outputs`) →
   `importStack(mutated)`. VERIFIED via javap on the real `pulumi-1.28.0.jar`:
   `WorkspaceStack.importStack(StackDeployment)` and `LocalWorkspace.importStack(String,
   StackDeployment)` both EXIST (also `exportStack`, `up`, `preview`). `importStack` writes state
   only — no provider calls — so it is non-mutant to infra and Claude may run it.

**Version fact corrected:** the BOM pins `pulumi.version = 1.28.0` (bom/pom.xml:25) and
`pulumi-1.28.0.jar` contains `com.pulumi.automation.*`. A stale `pulumi-1.0.0.jar` also sits in ~/.m2
(transitive, has NO automation package) — ignore it; always javap the 1.28.0 jar.

**TAGGING the seeded visits is REQUIRED (user insisted, I agree):** these consultationReports are
INJECTED, not doctor-produced — the YAML must show it, or it is a fake-proof (the "green build lies"
trap). Free mechanism: the additive guarantee. DiagnosisReader puts any unknown key into
`Dossier.details` and round-trips it, so a dossier carrying `{"seeded":"synthetic — injected for
Task 14, not doctor-produced"}` surfaces verbatim in the YAML on every seeded visit; also tag the
checkpointId (e.g. `seeded-systemd-adapter`).

**OPEN before coding:** does `importStack` append a NEW history entry (so repeated imports build a
multi-visit timeline) or only overwrite the current checkpoint? If it only overwrites, a multi-visit
seeded history needs another route (copy real dev checkpoints into the test backend, then inject) —
to verify. Real dev checkpoint shape (verified): top {version, checkpoint:{stack, latest}};
latest.resources[] ~23 nodes, each a Map with `outputs`; node[0]=pulumi:pulumi:Stack; component nodes
like rke2lab:controlplane:SystemdAdapter are natural injection targets (mirror registerOutputs).

Test stack/backend must be a throwaway, gitignored (model: wip/sandbox/.sb-state). NEVER touch dev.
Relates to [[task14-readonly-preview-integration]], [[medical-record-query-api-state]],
[[hub:shared-test-fixtures-module]].
