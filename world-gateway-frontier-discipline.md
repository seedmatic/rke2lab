---
name: world-gateway-frontier-discipline
description: "The world-gateway port is the host↔OSGi FRONTIER vocabulary — the most expensive contract in the system. Discipline (user, 2026-07-08): never add a term to it without first proving no existing word carries the need. Distinct from intra-domain ports (cluster-port etc.) which never cross the membrane."
metadata:
  type: feedback
---

> **★ NAMES UPDATED (verified 2026-09-06):** the frontier module `world-gateway` was RENAMED to **`seed-broker-port`** (`io.seedmatic.rke2lab.seed.broker.port`; no `world-gateway` dir remains) and the crossing envelope `Document` → **`SeedEnvelope`** (readiness is now `DefaultReadinessAuthority implements SeedHandler`, `handle(Cellar, SeedEnvelope)`). The DISCIPLINE below is unchanged — only the names moved. Applies across the `[[world-gateway-*]]` / `[[gateway-*]]` memory family (treat their `world-gateway`/`Document` as `seed-broker-port`/`SeedEnvelope`).

**The rule (user, 2026-07-08):** any addition of a term usable in `world-gateway`
(`io.seedmatic.rke2lab.world.gateway.port`) must be JUSTIFIED FIRST — prove that NO existing word carries
the need before adding one. Default = do NOT add; reuse.

**Why:** `world-gateway` is the vocabulary of the host↔OSGi FRONTIER — the words that cross the
membrane as `Document`s. It is the most expensive contract in the system: a word there is an
engagement on BOTH sides, and the `SCHEMA_CONCORD` gate projects these contracts build-time (adding a
word touches the gate — not free). The team suffered to stabilise this frontier for the doctor domain;
that pain is the lived proof.

**The distinction that prevents the mistake — TWO levels of "port", never conflate:**
- *world-gateway* = FRONTIER words that TRAVERSE the membrane: `Document`, `ReadinessCheckpoint`,
  `ReadinessVerdict`, `ObservationWire`, `SymptomKind`, `Action`, `Consultation`, `Patient`…
- *intra-domain ports* (`cluster-port`, `systemd-port`…) = how a domain talks to ITSELF / its edge:
  `ClusterReadinessContact`, `ClusterReadinessPhase`, `ControllerRef`. These NEVER cross the membrane —
  they are consumed inside the domain (a sub-scenario causing to its edge INSIDE Felix).

**Counter-intuitive corollary (verified 2026-07-08):** moving logic INTO OSGi (a domain sub-scenario
played in-container) RESHRINKS the frontier rather than growing it. Today the host imports
`cluster-port` in ~8 places (readiness lives host-side and leaks the intra-domain vocabulary). Pushing
the sub-scenario in-container makes `cluster-port` consumed from INSIDE the domain (legitimate) and the
host stops importing it — the frontier narrows to `world-gateway` alone. So "put it all in OSGi" is a
frontier tightening, not a widening.

**Worked example (the check that found nothing needed adding):** for cluster-readiness in-container,
the per-phase facts already ride `ReadinessCheckpoint.observations` (a `List<ObservationWire>`), and
the verdict already rides `ReadinessVerdict(Action, reason)` — flat by design. The host's
`VerificationResult` is a host-side PROJECTION of those existing words; it never crosses, never becomes
a frontier word. A "new result type with fine-grained booleans" was considered and REJECTED as
redundant. See [[bdd-pipeline-migration-plan]] and the whiteboard `.claude/claude-preview.adoc`
(§DISCIPLINE world-gateway).
