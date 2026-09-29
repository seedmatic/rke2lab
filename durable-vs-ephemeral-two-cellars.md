---
name: durable-vs-ephemeral-two-cellars
description: "Two carriers for facts crossing scion↔host, split by LIFETIME: the Cellar (durable, cross-run) vs the ReportModel tagMap (ephemeral, within-run) — the tagMap is 'a Cellar scoped to the scenario'."
metadata:
  type: project
---

## Two carriers for a fact crossing the realm — split by LIFETIME, not by kind (discovered 2026-07-15, butting on `liveRoot`)

The seed model has TWO ways a fact travels scion→host, and the axis that distinguishes them is
DURABILITY, not "narration vs data" (my earlier framing, which was WRONG and led me to reach for a
tag as a data smuggler — the user caught it):

| carrier | scope | lifetime | crosses the realm via |
|---|---|---|---|
| **`Cellar`** (`store`/`fetch`) | cross-run, cross-scenario | **DURABLE** — reread at run N+1 (the Pulumi state, `live.syncedFrom` pivot, image digest) | `Cellar.store` → a Pulumi resource |
| **ReportModel `tagMap`** | within-run, attached to the model | **EPHEMERAL** — dies with the run | the graft (the model is serialized+rebuilt) |

**The user's framing that unlocked it:** *"la tagMap c'est notre cellier mais relatif au scénario, une
map"* — the tagMap IS a cellar, scoped to the scenario instead of cross-run. So the choice rule is
crisp: **a fact reread at a LATER run → the durable Cellar; a fact consumed WITHIN this run → the
tagMap; and you only make a fact cross AT ALL if the host cannot derive it itself.**

**The concrete case — `liveRoot`.** The runbook renders into `host.live.d` (a live mutation, § the
runbook goes to host.live.d, memory `preview-testbed-defects-2026-07-14`). The host needs that path
post-run. It is a WITHIN-RUN fact (consumed right after the graft, never reread later) → tagMap, NOT
the Cellar (routing a render path through a Pulumi resource would over-durabilise it). And it must
come FROM the scion, because the `.local.d/<cluster>/<node>` layout convention now lives ONLY in
incus-core (§3b, the whole topology is OSGi-side) — the host re-encoding it would re-open the
convention-duplication leak (which ALREADY exists latently at `BootstrapConfig` line ~73 for the
kubeconfig — do not add a second).

**FACTS verified on jGiven 2.0.3 (round-trip test, gson-serialized):**
- Tags ARE serialized with the ReportModel and the `value` survives Writer→rebuild. A `Tag` is keyed
  in `tagMap` by `type + "-" + value` at the ReportModel level; the scenario carries only `tagIds`
  (references). Free value via `new Tag(type, value)` / `tag.getValues()`.
- BUT `ScenarioGraft.graftUnder` (host-side, generic, shared by ALL domains) grafts ONLY
  `scenario.getScenarioCases().get(0).getSteps()` — it does NOT merge the scion's `tagMap` into the
  host root. So a scion tag is LOST at the graft today. To make "take it from the root" work, the
  graft must ALSO merge the scion `tagMap` into the host tree (a small, generic addition — one line
  over `scion.getTagMap()`), then the host reads `hostTree.getTagMap()` filtered by `type`.

**The SYMMETRY the user named (deferred, separate step):** the graft is one-way scion→host today. The
inverse — the host POSTS on the root what it "brings to the terrain", the scion reads it at sow — is
the natural pendant, same map other direction. BUT that host→scion sense competes with the
**Amendment** (which has discipline the tag lacks: neutral role, projected schema, host names no
field), NOT with the Cellar. So it is a bigger redesign; keep it separate, do not improvise it.

**Rule that holds (the durable acquis):** distinguish DURABLE from EPHEMERAL. Durable/cross-run →
Cellar. Ephemeral/within-run → tagMap (the per-scenario cellar). Cross only the underivable.

## Actually THREE channels, not two — and the tag took nothing from the other two (audit 2026-07-15)

The user asked to sweep every place a fact is `fetch`ed from the Cellar and brought to the soil as an
input, on the hypothesis that ephemera should now leave the durable Cellar. AUDIT RESULT: the
hypothesis is ALREADY true — nothing ephemeral transits the Cellar. The 5 Cellar uses are all
legit cross-run: doctor medical-record + intervention-ledger, incus/bbox harvests (→ Pulumi
resource), and `CellarStage.the_parcels_state_is_fetched` (= "the parcel's state, WHERE WE START
FROM" — cross-run resume state, `no scion reads the cellar` per its own javadoc). The INPUTS brought
to the soil (the amendments SOIL/WORKTREE/FACET) come from host config / in-world compute, NEVER from
the Cellar. So there was nothing to remove.

This clarifies WHY the assessment model stays useful (the user's linked question). There are THREE
channels, each answering a DIFFERENT question, zero overlap:
- **ASSESSMENT** (verdict / `VerificationResult`: apiReady, handoffReady, bootstrapStatus) — *"must
  we act, and what to materialise?"* The ONLY channel the host ACTS on (→ Pulumi). A DECISION.
- **CELLAR** (harvests, ledger, resume state) — *"where do we start from?"* DURABLE, cross-run.
- **tagMap** (liveRoot) — *"where/how do we render THIS run?"* EPHEMERAL, within-run, non-decisional.

The tag we added took nothing from assessment nor Cellar — it FILLED A HOLE (the within-run
non-decisional fact had no channel, hence the earlier temptation to force it into one of the other
two). The danger to avoid: routing a DECISION through a tag would short-circuit the assessment (the
"tag combats the model" instinct the user had). As long as a tag stays descriptive/within-run, the
assessment keeps its monopoly on the decision. Graved in seed-broker-spec § two cellars. See
[[seed-broker-host-adaptation]] [[preview-testbed-defects-2026-07-14]] [[jsync-for-host-live-reconcile]].

## The Cellar is TRANSACTIONAL, and tag+Cellar are COMPLEMENTARY (2026-07-15, designing §4 pièce 3)

Refining the above while designing reconcile's cellar writes, two things settled (graved in
seed-broker-spec § "The cellar is transactional" + the CELLAR gesture table gains `commit`):

**Transactional Cellar — store accumulates, commit persists, the run is the transaction.** `store` does
NOT hit the durable backend; it ACCUMULATES in a transient buffer, and the scenario calls an explicit
`commit(Parcel)` at the transaction boundary. Three pins: (1) ONE shared instance per run — the host
publishes `PulumiCellar` into the embedded Felix, the scion plays IN THE SAME framework (Gardening.open
= one OsgiConnection.embedded; the runbook handler plays in-place, NO fresh Felix), so scion `store` and
host `commit` touch the SAME service instance — the buffer lives there, never crosses a frontier (THIS
is why envelopes need NOT ride tags: the tag channel is for the ReportModel, a cross-loader serialized
object; the Cellar is a shared service, no boundary). (2) commit is EXPLICIT and RESERVED to the ROOT
PLANT — a scenario played via the broker doesn't know if it is the root-plant grow (the real
transaction) or a graft (a fragment); we persist only for the root plant, so commit can't be an implicit
end-of-run effect (that fires for every graft). A graft only `store`s; the root plant commits. Failure
before commit → buffer dropped, nothing persisted (atomicity the run owns). (3) store never persists;
`fetch` is cache read-through (transient, else durable remounted CLEAN); commit writes only the DIRTY
(this run's new stores), never re-pushing what it read (no churn). Answers the old freshness NOTE for
the write side: nothing durable until commit.

**tag + Cellar are COMPLEMENTARY, tag PROJECTS the entry.** The Cellar persists to the Pulumi backend
(invisible in the runbook); a tag is visible in the runbook (operator narration) but not durable. A
host-tree fact that must be BOTH seen and kept is posed as a tag AND committed as an entry — but the tag
is a PROJECTION of the entry (computed from it), NEVER a separately-captured fact (else two sources
diverge). One truth (the entry), two renderings — the "ONE model, two renderings" discipline. `liveRoot`
stays a PURE tag (a within-run render path, no durable entry behind it). This corrected my over-reach:
I had built envelopes-ride-tags machinery (drain, graftedValues-list) that the shared-instance insight
ELIMINATED — the user caught that the Cellar is host-published so always the same instance.
