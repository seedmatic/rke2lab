---
name: medecin-conseil-efficacy-analyst-design
description: "DESIGN settled 2026-06-26 (brainstorm, NOT yet built) — the DriftSpecialist is reframed as the 'médecin-conseil', a PURE-READER efficacy analyst that produces a bounded, dated EfficacyReport referencing (not copying) the problem consultations + resolved symptoms, keeping the 'why' instead of throwing it. It INFERS nothing and WRITES nothing: the external-intervention inference is dropped; the unexplained case is a RENVOI to recruitment, not an efficacy verdict. Resolves the clinician-genus 'fused/missing entity' fork for Drift."
metadata:
  node_type: memory
  type: project
---

A 2026-06-26 brainstorm (whiteboard `.claude/claude-preview.adoc`, throwaway) reframed the
`DriftSpecialist` — the last actor that didn't fit the clinical model after the
specialist-distribution + prescriptor-split work. Builds on [[clinician-genus-entity-value-detector]]
(it flagged Drift as a "missing/mis-folded entity"), [[intervention-provenance-state]] (the confounding
mechanism as built), [[doctor-remediation-model]] (the recruit-a-specialist concern, deliberately set
aside), [[efficacy-first-prescription-provisional]] (the gate that will consume the report).

## The reframe — the médecin-conseil (Sécu analogy, the user thinks best by analogy)

Our model maps onto the French health system: `HealthSystem` = l'Assurance Maladie, `Generalist` =
médecin traitant, `Specialist` = spécialiste (diagnose), `Remediator` = pharmacien/infirmier
(administer), `MedicalRecord` = DMP, `InterventionLedger` = relevé des actes. The ONE actor with no
title was the DriftSpecialist. The user named it: the **médecin-conseil** — a doctor employed by the
SYSTEM (not the patient), who does NOT heal; he reviews a posteriori and reports on efficacy.

**The real subject is NOT "what title for Drift" — it is the GATE, and the gate rests on one question:
did a problem resolve BECAUSE OF US, or not?** That is an efficacy measurement. So the médecin-conseil's
job is to produce the analysis report the gate needs.

## What the médecin-conseil IS (settled)

- **A pure-reader analyst.** It reads (ledger + record), produces a report, and WRITES NOTHING. This
  is the decisive change: today's DriftSpecialist does `writer.append(EXTERNAL_CHANGE_DETECTED)` — the
  only write. Dropping the external-change INFERENCE removes that write entirely ⇒ the médecin-conseil
  is pure by construction. **This dissolves the old "does its write violate doctor purity?" fork** —
  there is no write left. It touches neither the live system nor the dossier.
- **It INFERS nothing.** The fragile deduction "resolved + unexplained → external change" is DROPPED.
  It only reasons over KNOWN facts.

## The efficacy verdicts (the "two drifts" collapsed into one measurement)

Not "two kinds of drift" but THREE issues of ONE efficacy measurement of a prescription:

| verdict | observation | creditable? |
|---|---|---|
| **effective** | administered → symptom does NOT recur | yes |
| **ineffective** | administered → symptom PERSISTS/recurs | no (plain therapeutic failure) |
| **confounded-declared** | symptom resolved, but a KNOWN operator-declared intervention in the window explains it | no |

"Drift" is therefore not an actor — it is what makes an attempt NON-creditable. The most RELIABLE
signal is *ineffective* (it does not depend on the operator's declarative discipline); *confounded*
depends on a declared `OPERATOR_MANUAL`. NOTE the optimistic default the user did NOT finally accept as
the whole story: resolved + nothing-declared is NOT silently credited — see the renvoi below.

## External intervention — kept, but never INFERRED (user corrected himself twice here)

The user first dropped external intervention, then reinstated it: it WILL happen and the patient's
history must be able to mention it. The fix is to separate two gestures the old DriftSpecialist fused:
- **inferring** "external" by absence — DROPPED (fragile; cannot tell a true external change from an
  operator who forgot to declare).
- **recording** a KNOWN external intervention in the history — KEPT.

So an external intervention enters the history ONLY by **declaration** (`OPERATOR_MANUAL` via
`RecordInterventionCommand`, already built — that IS the operator recording an out-of-band fix) or, one
day, by **observation** (compare observed vs expected state) — NEVER by the médecin-conseil's
"unexplained ⇒ external" guess. The analyst READS these interventions to render its verdict; it does
not mint them.

## The unexplained case = a RENVOI to recruitment (the bridge, named not built)

When a symptom resolves and NO known fact explains it, the médecin-conseil does not write an efficacy
verdict — it SIGNALS that a phenomenon is unmodelled. That is the recruitment trigger
([[doctor-remediation-model]] §3 recruit-a-specialist): the "external intervention (observed)" is
characterised and entered into the history DURING a recruitment, not by the analyst. Recruitment itself
stays set aside; only the bridge is named: the efficacy report's "unexplained" slot points TO
recruitment, without doing it.

## The EfficacyReport shape (settled in spirit; maille DEFERRED)

`TreatmentEfficacy(symptom, List<Attempt>)` exists but is too thin: `everWorked()` folds ALL history
into one "ever" boolean. The user's three requirements for the report:
1. **bounded to an interval** `[from, to]` — a measurement valid for that window, not "since forever".
2. **dated** `measuredAt` — a report is taken at an instant, re-measurable later (another report).
3. **keeps references, not copies** — each finding POINTS to the problem consultation + the resolved
   symptom (same spirit as the multiplexor path-addressing), and KEEPS the "why" (the Drift letter that
   is thrown away today — `reviewOpenProblems`' returned `List<ReferralReply>` is currently discarded
   by `DriftReview.reviewAtReconstruction`).

⇒ a NEW `EfficacyReport(interval, measuredAt, List<Finding>)` where a `Finding` carries the verdict +
references + the why. The médecin-conseil's `review` PRODUCES findings instead of appending to the
ledger in silence. The GATE consumes the report (not a boolean): "do not re-prescribe X — ineffective
on the last interval, see consultation Y."

## DEFERRED — the maille, to settle when real recruitment use-cases appear (user's call)

Frozen here on purpose; the *what/why* is done, the *how* sharpens with code in hand. Open questions:
- report per-run (all symptoms) or per-symptom?
- the 3-verdict set final, or more?
- references point to what exactly — `ConsultationReport`, `Expectation`, `Visit`?
- the interval — the last two visits, or an explicit `[from,to]` passed in?

## What this decides about the genus (Fork answers)

- Fork "is Drift a Clinician" → YES in spirit (it has an operation model), but as a **pure analyst** it
  may not need to be a `Specialist`/`Remediator` peer — it is a SYSTEM-employed reviewer (médecin-conseil),
  distinct tier. Name/SPI placement DEFERRED with the maille.
- Fork "purity / its write" → DISSOLVED (no write left once inference is dropped).
- Fork "distribution" → stays core-internal (no `domain()`, per-run, reads the run's ledger) — same
  non-distributing profile as the Generalist. Not a DS `@Component`.

State at freeze: code UNCHANGED this brainstorm (design only). The built DriftSpecialist still infers +
writes EXTERNAL_CHANGE_DETECTED; the redesign (drop inference, produce a report, close the discarded-letter
circuit) is NOT yet applied. See [[clinician-genus-entity-value-detector]] [[intervention-provenance-state]]
[[efficacy-first-prescription-provisional]] [[doctor-remediation-model]] [[multiplexor-two-models-design]].
