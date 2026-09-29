---
name: atlas-first-reflex-and-levelling
description: WORKING PRINCIPLE (user, 2026-07-02) — when a problem arises, FIRST gesture = reopen the integration atlas, locate the problem on it, start there. The atlas is now levelled L0/L1. Not maintaining it since late June caused re-derivation of already-settled design.
metadata:
  type: feedback
---

**The rule (user, verbatim intent):** *"quand se pose un problème, le premier geste est de relire
l'atlas et trouver où se situe le problème pour commencer dans l'atlas."* Not in a spec, not in the
code — the atlas FIRST. It tells you which subsystem(s) the problem touches, which value thread it
sits on, and whether the answer is already recorded (a decision in one subsystem often silently
answers an open question in another).

**Why this matters — the cost, proven this session:** the atlas was not reopened/maintained since late
June. So the late-June federated unit-repo design (`federated-unitrepo-p2p-design.md`: 3 topologies
embedded↔subprocess↔pod, "one source multiple packagings", ContentStore, migration=clone) was OUT of
view — and we RE-DESIGNED the pipeline↔detached split on top of it without seeing it. We re-dug a hole
already dug.

**How to apply:** ranging/placement is not tidiness — it is what keeps a solved problem solved (user:
"le rangement est nécessaire pour ne pas retomber dans le trou et essayer de le combler"). Before any
new design/spec, reopen the atlas L0, find the value thread, descend to the L1 view, and only then
touch specs/code. Maintain it AT THE TIME (reopen at the start of every design activity), never in a
later reconciliation pass.

**The atlas is now levelled (C4-style zoom), split physically:**
- **L0** = `docs/architecture/integration-atlas.adoc` (~252 lines) — integration + transverse *value
  threads* + the two-spaces / two-vocabularies frame + the L1 index. START HERE. Keeps its filename →
  the 16 inbound links stay valid.
- **L1** = `docs/architecture/atlas/{doctor,config,runtime,unit-repo,manifests,host-pipeline}.adoc` —
  one file per subsystem, the container-zoom detail + monotone before/after figures. Descend here once
  L0 says where the problem lives. Relative links shifted one level (`../`).
- **L2** = the figures inside each L1 view.

**First value thread graved in L0** (`[[thread-manifestations]]`): *one unit-model, several
manifestations (bootstrap↔detached).* Answers "who plays Pulumi/stack/Patient/storage when detached" =
the intersection of pipeline-spec + post-bootstrap-ownership-plan + unit-repo (ContentStore, everything
is a unit) + doctor. Dissolves the contradiction "Patient = Pulumi stack" vs "detached OSGi knows
nothing of Pulumi": Patient/MedicalRecord are UNITS; the Pulumi stack is the ContentStore incarnation
of the BOOTSTRAP case only. Value: storage portability · self-hosting · one-source-many-packagings.

**RESUME DEBT — retrospective audit (user, 2026-07-02):** because the atlas lapsed since June, THIS
session's design/spec/realisation (the whole pipeline arc: `pipeline-spec.adoc`, RunMode, two gates,
Topic vocabulary) must be RE-READ against the atlas + the late-June memories to catch other blind
spots — not just the one we found. Suspect overlaps to check: `[[multiplexor-two-models-design]]`
(the world-boundary door — `awaitService` + Document seam is likely the SAME seam as the pipeline's
`RunMode→OSGi→runbook` joint), `[[unitrepo-design-unification-state]]`, `[[designer-runbook-state]]`.

See [[pipeline-jgiven-separation-design]] [[atlas-reconciliation-2026-07-01]]
[[multiplexor-two-models-design]] [[unitrepo-design-unification-state]].
