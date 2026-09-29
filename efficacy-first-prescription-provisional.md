---
name: efficacy-first-prescription-provisional
description: "MedicalRecord.efficacyOf is per-symptom (fixed a cross-symptom bleed) but the 'first prescription' attribution is PROVISIONAL — no weighting/ponderation logic behind it; revisit before efficacy drives a decision"
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Task 8 of the medical-record query API ([[medical-record-query-api-state]]) implemented
`MedicalRecord.efficacyOf(Symptom)`. Two decisions, ONE settled, ONE provisional:

**SETTLED — per-symptom gate (a correctness fix, 2026-06-08).** The quality reviewer caught a
*cross-symptom bleed*: the original gate was `treated = !visit.prescriptions().isEmpty()` (visit-wide).
A Visit holds several `ConsultationReport`s (one per symptom), so a visit raising X *untreated* but
also raising Y *with a prescription* would credit Y's treatment as X's efficacy attempt. Fixed: a
private `treatmentFor(visit, symptom)` filters `report.symptom() == symptom` then takes that report's
prescription. Now efficacy is per-symptom, consistent with `historyOf` (which already filtered per
symptom via `firstCheckpointRaising`). Locked by a test
`efficacyOf_isPerSymptom_anotherSymptomsPrescriptionIsNotCredited` (15 tests green, commit 2697eeb7).
This part is NOT in doubt.

**PROVISIONAL — "first prescription" attribution (user flagged: "we're not sure", "we don't have any
ponderation logic inside").** When a symptom carries SEVERAL prescriptions in one visit, the attempt
attributes the FIRST one (`findFirst()`). The user and I chose "first" but the user rightly objected
that picking "first" is **arbitrary**: there is NO weighting / priority / ponderation model, so "first"
is NOT "the most important treatment", just the first written. We did NOT pretend this is settled —
it carries an explicit `// CAVEAT (provisional, revisit)` comment in `efficacyOf`. **Open question to
settle before efficacy drives any real decision:** either (a) introduce a real ponderation rule on
prescriptions, or (b) emit one Attempt per prescription (they'd share the same `recurred` outcome).
Deferred because efficacy currently feeds only the offline YAML dump (human reads it), not an
automated decision — so an arbitrary-but-documented pick is acceptable for now.

**WHY this is in memory not just a comment:** the user's instinct ("warn about it, we're not sure")
is a standing collaboration signal — when a choice is arbitrary, mark it provisional honestly rather
than bake it in silently as if principled. Mirrors the errors-as-control-values stance
([[hub:error-handling-layered-contract]]): don't deport an unresolved decision into invisible code.
