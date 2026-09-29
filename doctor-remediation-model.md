---
name: doctor-remediation-model
description: "Doctor model design (2026-06-07): Remediator tier, next-visit loop closure, recruit-a-specialist gradient + consultation as a Referral/Prescription/ReferralReply round-trip (specialist sees siblings+longitudinal by ref). Enriched docs/architecture/doctor/runbook-doctor.adoc; not yet implemented."
metadata: 
  node_type: memory
  type: project
  originSessionId: 6d3faadb-1da7-486c-9310-99b6dd4c49b5
---

A 2026-06-07 brainstorm (trigger: the user noticed Claude's *subagent* feature mirrors the
doctor's AI-ready `Specialist` seam) extended the medical model with three LOCKED decisions,
written into `docs/architecture/doctor/runbook-doctor.adoc` (sections `[#the-remediator]`, `[#loop-closure]`,
`[#recruiting-a-specialist]`, anchor `[#ai-seam]`). DESIGN ONLY — no code yet. Builds on
[[runbook-doctor-state]]; the doctor core already exists (Generalist + DbusTcpSpecialist).

**1. Remediator tier (NEW, enters the model — user: "c'est important").** The doctor stays
PURE (reads/writes the record, never touches the live system). A distinct role — the
*remediator* — *administers* the treatment, confining ALL live-system risk to one tier. The
`Prescription.programRef` it already carries is the referral address: routes a prescription
to a remediator exactly as `Generalist` routes a symptom to a specialist (mirror image — the
routing key already exists, dormant). Remediator KINDS = real ops shapes: *nurse* (imperative
one-shot, e.g. RESTART_UNIT, today's only case), *pharmacist* (dispenses: apply manifest /
fill config input — idempotent desired-state change), *physiotherapist* (progressive/iterative:
retry-backoff, gradual rollout). Structural axis the plan must respect: **declarative
remediation → the Pulumi ENGINE ITSELF is the remediator** (operator changes desired state,
next `up` converges, no separate executor); **imperative remediation → needs an explicit
remediator** (no "desired restarted state" to converge to). `programRef` carries the gesture's
nature (declarative/imperative).

**2. Loop closure = the NEXT VISIT, on the operator's terms (user's call).** Never administer
mid-flight during a `pulumi up`. Loop closes BETWEEN visits via TWO VOICES: the *medical
record* (doctor's voice — prescription persists in stack state, layer 3) PROPOSES; the *stack
config* `Pulumi.<stack>.yaml` (operator's voice = the visit's admission context) DISPOSES; the
*next `up`* is the check-up that administers what the operator authorized AND re-observes.
Lands exactly on the locked "re-observe = the program re-running, not a provider Read". The
CLAUDE.md "propose, don't execute" line holds STRUCTURALLY with no amendment: doctor proposes
(record) / operator disposes (config) / engine executes (converges). Unattended CI ≠ autonomous
— it converges on version-controlled, reviewable config. Live-system risk becomes a question
of config content, not agent trust.

**3. Recruit-a-specialist (the meta move — user: "et si le généraliste ne sait pas… la
prescription est de recruter un nouveau spécialiste").** The Generalist's two dead ends — *no
route* for the symptom, and *routed but none prescribed* — stop being termini and become a
*recruitment prescription*. The two gaps recruit differently: no-route → a NEW specialty (new
domain); routed-but-empty → a DEEPER specialist in that domain. The recruiter is a
META-SPECIALIST whose domain is the clinic itself. **Honored via a gradient ad-hoc→codified
(chosen):** a generic Claude-backed `Specialist` is the universal fallback — treats the unknown
case ad hoc immediately AND emits a recruitment prescription; if the frontier symptom recurs,
the operator CODIFIES it into a deterministic `Specialist` (DbusTcpSpecialist = the codified
end; Claude-backed = the starting end). Routing an unknown symptom to a Specialist instantiated
for it IS a subagent dispatch (fresh focused agent, dossier as its only context). **This is the
concrete reason T3 (open roster + contributed routing) stops being speculative** — a `switch`
over a closed enum cannot recruit. **DOGFOODING (user: "on fait du dogfooding à notre propos"):**
"codify a recruited specialist" = the design loop itself — operator + Claude re-enter
brainstorm→design, triggered by a REAL remediation exercise (healing the live
`systemd-adapter degraded` on master, the dbus-tcp:12434 symptom in [[master-provisioning-state]]).
The runbook's recruitment prescriptions = a self-generated backlog of specialists to build.

**4. Consultation = a referral round-trip (2026-06-07 session-3 enrichment; DESIGN, not yet
implemented).** Today `diagnose(Symptom, Dossier) → Optional<Prescription>` is a one-shot with NO
context: the specialist sees only its symptom + dossier, never the sibling prescriptions written THIS
run nor the longitudinal record (verified in `Generalist.consult` — the accumulating `List<Prescription>`
is never passed back into later `diagnose` calls; the sole call site is `diagnose(symptom, dossier)`).
The user's model fixes this as the real medical circuit, THREE objects with clear recipients:
(a) **Referral** (REQUEST, generalist → a NAMED specialist): the patient + the symptom (the *why*) +
*references* (not copies) to the longitudinal patient record AND the sibling prescriptions already made
THIS run by the generalist to other specialists; (b) **Prescription** (specialist → PATIENT): the
treatment to administer (today's `Prescription`, unchanged role); (c) **ReferralReply** (RESPONSE,
specialist → generalist, doctor-to-doctor): explains what it found, and **references the Referral**
(request↔response linkage). So the shape becomes ~`diagnose(Referral) → ReferralReply` where the reply
CARRIES the Prescription (if any) + the explanation + a pointer to the request. Cheap: both refs
(longitudinal record + this-run log) are already in memory; the Referral only points. Wins: (1) the
addressing is TRACED end-to-end → resolves the patient↔specialist `authoredBy` gap the Drools prototype
flagged, for free; (2) a "nothing to offer" case is a ReferralReply WITHOUT a Prescription (keeps the
*why*, unlike today's `Optional.empty()` which drops it); (3) serves the planner inner loop — when a
downstream symptom is unmasked and re-consulted, the new Referral references the sibling prescriptions
already emitted, so the next specialist sees the run context (ordering matters: specialist N sees
siblings 1..N-1). This is the doctor model, NOT the what-if (see [[preview-whatif-topic]] for that).
Open at impl: Prescription contained-in vs separate-from the ReferralReply.

**5. The gateway consolidated = an agenda-owning generalist (2026-06-07 session-4, VALIDATED;
written to `docs/architecture/doctor/runbook-doctor.adoc` `[#consultation-flow]`).** Building on pt.4's referral round-trip, the
user fixed how the consultation is *scheduled*. NINE invariants: (1) three objects —
`Referral` (request, generalist→specialist) / `Prescription` (→patient) / `ReferralReply`
(response, doctor→doctor); (2) seam `diagnose(Referral) → ReferralReply`, the reply keeps the *why*
even with no prescription; (3) refs not copies (longitudinal record + this-run log, already in
memory); (4) **base case = TWO PHASES** (specialists diagnose independently → generalist
synthesizes+detects conflicts) — order only materializes if needed (graceful degradation); (5)
context is **RUN-WIDE** — the generalist already carries replies from *other* checkpoints, not just
the current one; (6) the generalist **may impose an order a priori** (it knows the diagnostic
topology); (7) a consulted specialist keeps **agency** — it can ② *defer* ("wait for domain X's
diagnosis, I depend on it") or ③ *refer* ("address the patient to domain Y too"); (8) **②/③ ALWAYS
go through the generalist** (user's call) — specialists don't know each other (decoupled), the
generalist is the SOLE coordinator, resolves the target via `treats()`, adds it to its agenda; (9)
`treats()` = practitioner is sole authority, `domain()` only seeds the default (the
[[runbook-doctor-state]] rules-engine/Drools clarification). The generalist-coordinator is
STRUCTURALLY an *agenda loop* (a worklist that grows as specialists defer/refer — forward-reasoning,
not a flat router; the 3rd time forward-chaining surfaced, alongside Drools-substrate and the
what-if planner — assumed as an explicit agenda, NOT a hidden engine). TWO GUARDS the plan must
honor: *determinism* (stable tie-break when several consultations are ready — candidate: checkpoint
topological order, then specialist registration order) and *termination* (visited set
`(patient,symptom,specialist)` at most once + deadlock detection). The defer/refer graph (runtime
diagnosis-dependency between specialists) is DISTINCT from the checkpoints' resource `dependsOn`
topology — do not conflate. Supersedes the old `firstLook`+hard-coded-`switch` flow (deleted from
spec). The planner inner loop ([[preview-whatif-topic]]) LOOPS on these round-trips: each
re-consultation on an unmasked downstream symptom is one Referral carrying the sibling prescriptions
— round-trip = the unit, planner = the loop over units.

**Two seams, not one (clarified in `[#ai-seam]`):** diagnosis seam = READ-ONLY (Specialist
correlates+prescribes, never acts — like a read-only subagent); remediation seam = where the
ACTION SURFACE lives (MCP is the candidate, still deliberately undesigned). The `programRef`
catalog IS the *therapeutic formulary* — the bounded set of authorized gestures an MCP surface
would expose as tools (never arbitrary shell). Also corrected a mis-cadrage: a Specialist is
NOT a "reduced" agent — it's a DEEPER lens (correlates what escaped the generalist, can even
correlate across patients/stacks), as complete a view as the generalist; statelessness holds
because the record is READ (passed in / edge-propagated per T1), never HELD.
