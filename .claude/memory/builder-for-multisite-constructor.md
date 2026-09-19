---
name: builder-for-multisite-constructor
description: "Design decision (user, 2026-06-14): when a constructor with multiple call sites must gain a new dependency, route construction through a builder (private ctor) so future deps touch only build() + the sites that refuse the default — but the insulation only works for params that HAVE a sensible default. First applied to Generalist (3 params, 5 sites) gaining a DriftSpecialist with a no-op-writer default. Generalize-later backlog, not a blanket sweep."
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**THE IDEA (user, during problem-oriented-provenance Task 11).** Adding a 3rd ctor param to a
class with N call sites forces editing all N. Route construction through a BUILDER and the callers
DELEGATE — a future dependency then touches only `build()`, not every caller. Matches the project's
own CLAUDE.md rule ("3+ params → builder", `StackCoordinate.builder()` precedent).

**THE NUANCE THAT DECIDES IT (my re-cadrage, user endorsed).** A builder insulates callers ONLY for
params that have a SENSIBLE DEFAULT. For a REQUIRED dependency with no default, the caller must still
write `.dep(x)` — the builder MOVES the edit, it does not REMOVE it. The delegation erases the edit
only when `build()` can supply a default. So "builder ⇒ refactor-proof" is true *iff* the new param
is optional-with-default.

**WHERE IT APPLIED FIRST = `Generalist`.** Reached 3 params (`specialists`, `access`, +new
`DriftSpecialist`), 5 call sites (`HealthSystem.admit` prod + 4 tests). The new `driftSpecialist`
HAS a legitimate default: a `DriftSpecialist` backed by a NO-OP `InterventionLedgerWriter` →
semantics "no ledger wired → inference computed & returned, but not persisted" — coherent with
`LiveMedicalRecordRegistry`'s existing "no backend → empty record" degrade. That default is what makes
the param genuinely insulating. So: builder + PRIVATE ctor (project convention forces the builder),
`build()` requires specialists+access, defaults driftSpecialist to the no-op; migrate the 5 sites
ONCE (the cost paid now), and `HealthSystem.admit` + the Task-12 reconstruction site wire the REAL
`PulumiInterventionLedgerWriter`.

**CAVEAT I assumed honestly (not hidden).** The no-op default risks a future prod path silently
getting the no-op (inferences not persisted). Mitigation: the two real wiring sites set the real
writer explicitly, and "no ledger → no persistence" mirrors the existing degrade. Acceptable; noted.

**SCOPE DISCIPLINE.** Apply to `Generalist` now (concrete: 3 params, 5 sites, in growth). Do NOT
blanket-sweep all multi-site constructors — that's premature abstraction against the
[[hub:sequential-no-compat-workflow]] "one topic at a time" rule. GENERALIZE-LATER backlog: revisit when
a 2nd/3rd concrete multi-site-ctor-gaining-a-dependency case appears (rule of three). Lives alongside
[[intervention-provenance-state]] (the chantier that surfaced it). Validates
[[hub:works-best-from-concrete-code]] — the pattern crystallized from a real refactor, not abstractly.
