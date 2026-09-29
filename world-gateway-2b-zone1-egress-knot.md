---
name: world-gateway-2b-zone1-egress-knot
description: World-gateway 2B zone-1 (systemd-adapter consult path) is entangled with the Pulumi egress + medical-record reconstruction the user requires preserved — the consultation Document must carry the STRUCTURED plan/observations/expectations (not just rendered strings) so readers stay unchanged. Resolved design (A-struct) recorded here.
metadata:
  type: project
---

On feature/cluster-edge, executing 2B zone-1 (Task 4, systemd-adapter consult path → Document)
surfaced a knot the plan did not anticipate: the probe's `Observation` and the consult's
`ConsultationReport` are BOTH dual-purpose — they feed (1) the consult/runbook path (2B's target)
AND (2) the Pulumi egress (`SystemdAdapterResource`/`ClusterReadinessResource` →
`ConsultationReport.toOutputMap()` + `report.expectations(recordedAt)` → `ConsultationReport.OUTPUT_KEY`
/ `Expectation.OUTPUT_KEY`), which `ConsultationReportReader`/`ExpectationReader` read back to
reconstruct the medical record. The egress + reconstruction is nominally 2C, but it CANNOT be left
broken while migrating the consult.

**User's hard constraint (2026-06-28):** the Pulumi output must keep the SAME INFORMATION as before
(form/structure may differ) AND the medical record must be reconstructible from the output (the stack
IS the patient's record store). This is an invariant on whatever 2B does, not a task preference.

**Why strings-only fails:** Task 2 shipped a consultation Document carrying only `narration` +
`diagnosisAdoc` (rendered strings). That LOSES the structured `plan{replies{assessment,prescription},
generalistSummary}` + `observations[]` + derived `expectations[]` the readers rebuild from → breaks
reconstruction → violates the constraint.

**Resolved design — A-struct (user-chosen):** the consultation Document the doctor returns carries the
RENDERED strings (narration, diagnosisAdoc) AND the STRUCTURED reconstruction data, the latter in the
EXACT shape the existing `toOutputMap()`s produce (`checkpointId`, `observations[]`, `plan{}`,
`expectations[]`), so:
- `Generalist.consult(Document)` (Task 2 must be AMENDED — it was under-built): build the
  `ConsultationReport` internally as today, then put `report.toOutputMap()`'s entries + the expectations
  sub-tree into the consultation Document payload, alongside narration + diagnosisAdoc. OSGi owns the
  plan, so it serializes it.
- The checkpoint Document gains `recordedAt` (ISO string, host-native — no doctor type) so OSGi can
  compute `expectations(recordedAt)` too, keeping that derivation off the host.
- `ConsultationLog` becomes a log of consultation **Documents** (keyed by checkpointId), not
  `ConsultationReport`s.
- Host (`SystemdAdapterStage.consultDoctor`, then `ClusterReadinessStage`): build the checkpoint
  (symptomKind via `SymptomKind.X.slug()` + summary + details + recordedAt), call `consult(checkpoint)`,
  log narration, store the Document. The probe KEEPS returning `Observation` (it is also the egress
  `summary`/`toOutputMap()` source + the jGiven scenario assertion target) — only the CONSULT
  reasoning/rendering leaves the host. So `SystemdAdapterStage`/`ClusterReadinessStage` drop `Symptom`,
  `RemediationPlan`, `ConsultationReport` imports but the probe interface stays `Observation` until the
  egress increment.
- Egress (`ResourceCreationPipeline.consultationFor` → resources): writes the consultation Document's
  structured payload sub-trees to the SAME output keys (`ConsultationReport.OUTPUT_KEY`,
  `Expectation.OUTPUT_KEY`) — opaque copy, host holds no doctor type. Because the shapes match the old
  `toOutputMap()`s, `ConsultationReportReader`/`ExpectationReader` stay UNCHANGED → reconstructibility
  guaranteed, info preserved, only the producer changed (host→OSGi).

**Consequence for the worklist:** zone-1 removes the CONSULT-reasoning doctor types from the host
stages, but `Observation` remains (egress + scenario), so the stage stays partially on the worklist —
expected, the full clear is the egress increment. This is the corrected "green per zone".

See [[world-gateway-2a-execution-state]] [[world-gateway-document-design]]
[[document-codec-instance-in-2d-backlog]].
