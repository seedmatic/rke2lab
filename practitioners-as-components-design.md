---
name: practitioners-as-components-design
description: "DESIGN direction settled 2026-06-26 (brainstorm, NOT built) — correct the system so EVERY doctor actor declares itself as a component, not just the stateless ones. The one knot: the Generalist holds per-run ClinicalAccess (closed over the admitted patient), so it can't be a DS singleton. Fix = split the EMPLOYED practitioner (stable @Component, identity + operational mode) from the per-run CONSULTATION (a value holding the access). Generalises the Clinician genus to carry an operational mode alongside clinicianId(). Absorbs the Generalist-visibility/practitioner-directory backlog. Fork A/B/C (call-param / DS ComponentFactory / employment registry) deferred to build."
metadata:
  node_type: memory
  type: project
---

A 2026-06-26 brainstorm (same session as [[medecin-conseil-efficacy-analyst-design]]), the user's
call: **"je préfère qu'on corrige le système et que tous les acteurs se déclarent comme un composant
du système… chaque acteur devrait avoir maintenant un mode opérationnel."** Spec written:
`docs/architecture/doctor/practitioners-as-components-design.adoc` (with C4 figures).

## The fact (verified in code 2026-06-26)

@Component today: `DefaultHealthSystem` + the 3 distributed `Specialist`s. NOT components: `Generalist`,
`DriftSpecialist` — built per-run by `DoctorGraph.assemble` at each `admit(patient)`.

`DefaultHealthSystem` IS a singleton precisely because its 3 fields are all `@Reference` (specialists,
registry, ledgerWriter) — zero per-run state. The Generalist's blocking field is exactly ONE:
`ClinicalAccess` — which has CLOSED OVER the admitted `patient` + its `GrantPolicy` + bound
`ClinicianId` (deliberately: a clinician can't read as another id nor mint its own access). The
DriftSpecialist's `ledgerWriter` is run-infra, NOT per-patient → resists far less.

## The correction (settled direction)

The real defect is **we recreate the doctor at every patient instead of employing him once and
creating a consultation**. Real health system: practitioner hired ONCE (identity, directory slot);
each consultation is a distinct act bound to one patient. So SPLIT the fused Generalist:
- **practitioner** (EMPLOYED) = behaviour (consult/route/synthesize) + identity + operational mode →
  a stable `@Component`, no per-run state.
- **consultation / engagement** (PER-RUN) = a VALUE holding the `ClinicalAccess(patient, grants)`,
  minted at `admit(patient)`, that the practitioner operates over.

Once per-run state lives in the consultation, the practitioner is singleton-able like every other
actor. Same move frees the efficacy analyst (ex-DriftSpecialist) and any future actor: **behaviour is
a component; the run is a value it operates over.**

## The genus, generalised (the user's "operational mode" point)

`Clinician` today declares only `clinicianId()` (identity — verified: typed value, the join key for
grants + cohort correlation; Specialist defaults it from its specialty, Generalist = "generalist").
Add the SECOND universal: every clinician declares its OPERATIONAL MODE (its verbs), already surfaced
this cycle — Specialist: assess+prescribe, Remediator: administer, GeneralPractitioner: consult,
analyst: review. Identity = who; operational mode = what it does. Both belong on the genus.

## Instantiation mechanism — CHOSEN: C (user, 2026-06-26)

The deciding question was WHERE the per-run `ClinicalAccess` lives. Three options (figured as C4 in the
whiteboard per [[options-always-as-c4-diagrams]]; repo uses NO ComponentFactory/PROTOTYPE today):
- **A — access as call parameter**: access circulates → "closed over / cannot read as another id"
  guarantee LOST; also changes every ConsultingService signature. REJECTED.
- **B — DS ComponentFactory**: access in a factory-component field → guarantee kept, but passing a rich
  `ClinicalAccess` through DS component-properties (scalar maps) is contra-natural, and it spins a
  component lifecycle up/down per admit for no gain. REJECTED.
- **C — employment registry (CHOSEN)**: practitioner is a singleton @Component employed once; the
  HealthSystem mints a `Consultation(ClinicalAccess)` VALUE per admit and runs the practitioner over it
  (`practitioner.engage(consultation)` → a ConsultingService view bound to it). Access stays closed over
  (in the value, navigable), DS serves stable services only, no host signature change, directory free.

## Falls out for free — the practitioner directory

Once every actor is an employed component with identity + operational mode, "who is employed here?" is
uniform: HealthSystem holds the directory; Specialists arrive by `@Reference`, practitioner(s) by
employment. This ABSORBS the deferred Generalist-visibility slice ([[clinician-genus-entity-value-detector]]
backlog) — directory uniform even if arrival mechanism differs.

## Status

Design direction only; code UNCHANGED. The Generalist + DriftSpecialist are still per-run objects.
NOTE: this is a DIRECTION (the A/B/C mechanism is unsettled), not a build-ready design — graven per the
"specs up to date at end of brainstorm" discipline ([[specs-current-at-brainstorm-end]]). See
[[medecin-conseil-efficacy-analyst-design]] (the analyst freed by the same split),
[[clinician-genus-entity-value-detector]] (the genus + the Generalist-visibility backlog),
[[healthsystem-keystone-state]] / [[healthsystem-access-control-model]] (the institution + ClinicalAccess),
[[object-graph-navigability-principle]].
