---
name: world-gateway-lost-open-extensibility-debt
description: "Open per-domain extensibility (a DS roster of contributors, not a central enum) is a FUTURE evolution the distributed peer-to-peer mode requires — surfaces in two places: the world-gateway egress (multiplexor) and the config InfraDomain enum. Not a defect; a p2p-driven need."
metadata:
  node_type: memory
  type: project
---

**Framing (user, 2026-07-05): this is a FUTURE evolution required by the distributed peer-to-peer mode
— NOT a defect to repair.** While the system is centralized bootstrap (seed-master orchestrates), the
closed forms below are fine. The moment the system becomes a mesh of unitrepo PEERS that extend their
capabilities dynamically (contribute a domain as a bundle, per new need — the unitrepo thesis, see
[[federated-unitrepo-p2p-design]] [[fragment-contribution-mediation-model]]), OPEN per-domain
extensibility becomes REQUIRED. It is the p2p mode that makes it necessary, not a present-day lack.
Surfaced during the 2026-07-05 atlas doc cleanup (user: "what did we lose by dropping the multiplexor?"
then "in a dynamic unitrepo context we extend the system to new domains per new needs, no?").

**The recurring shape (one pattern, two instances).** Both are the SAME move: an OPEN
contributor-roster collected by DS (`@Reference(cardinality=MULTIPLE) List<…>`, like `List<Specialist>`)
was, under delivery pressure, shrunk to a CLOSED central point. Extending then means editing a compiled
center instead of publishing a bundle — which a p2p peer cannot do dynamically.

1. *World-gateway egress — the "multiplexor" / per-domain roster.* The design: each domain publishes a
   `DomainDagMapper` `@Component`; a roster aggregates them and assembles the egress DAG. Shipped
   instead: records-as-contract with a CLOSED central `Coordinate` enum
   (`osgi/foundation/world-gateway/.../port/Coordinate.java`) + host-side `OutputBuilder`/`toOutputMap`
   egress; no `@Reference List<…>` roster exists (verified 0 occurrences; the multiplexor never had
   `.java` in any commit). Records-as-contract GAINED a typed contract-of-form (`@DocumentContract` →
   JSON Schema → SCHEMA_CONCORD gate) the multiplexor lacked. The end state wants BOTH axes.
   *Frontier correction:* inside OSGi, records are natural; `Document` is the currency of the FRONTIER
   only. So the roster + mappers assemble the DAG ON RECORDS OSGi-side; the record→`Document`
   transposition sits at ONE place — the seam edge. The multiplexor is NOT dead, its frontier just
   recedes to the edge. Design preserved in world-gateway-spec §"Planned evolution — the per-domain
   roster"; its own memory is [[multiplexor-two-models-design]].

2. *Config — the `InfraDomain` enum.* config-restructuring-spec designs an `InfraDomainRegistrar`
   `<<interface>>` ("a new provisioning concern = one new registrar, no edit to a central monolith").
   Shipped instead: `InfraDomain` (a CLOSED enum, `exec/seed-master/.../config/InfraDomain.java`, 6
   values). Nuance (verified 2026-07-05): the polymorphic enum KEEPS the per-domain self-load
   (`abstract InfraConfigFragment contribute(ConfigLoader)`, one body per value) and the decoupled
   assembly (`values()` iterates, no hand-list) — it loses ONLY the OPEN axis (a new domain edits the
   central enum, cannot be contributed as a bundle). For 6 fixed structural infra domains that is a sound
   idiomatic choice TODAY; it becomes the friction point exactly when a peer must contribute an infra
   domain dynamically (the p2p mode).

**Timing:** the world-gateway roster is the nearer one — a PRIORITY chantier right after the
behavior-driven pipelines land (cluster, the 2nd contributing domain, arrives with that migration, so
N=2 and the slot coincide). The config registrar rides the same p2p wave; both are "open the
contributor set" evolutions, done WHEN we get there (do NOT invent the exact new shape now — preserve
the existing designs + the direction). Both docs keep their open design as FUTURE, framed "required by
the distributed p2p mode".

**Trigger criterion (user, 2026-07-08).** The 2026-07-08 two-worlds recomposition brainstorm re-raised
this (a domain sub-scenario played in-container). Decision: do NOT open the multiplexor by anticipation.
EXTEND the closed `Domain` enum (one line) IF AND ONLY IF a new domain must appear IN the gateway (i.e.
publish its own doc-type/coordinate). The mere multiplication of OSGi domains does NOT trigger it — in
centralized bootstrap, adding an enum member is cheap and sound. The real trigger stays the p2p mode
(a peer publishes a domain as a bundle, editing-a-center-impossible). Verified corollary: cluster-
readiness in-container surfaces `Domain.DOCTOR` + `Coordinate.READINESS_*` (it CONSULTS the doctor,
publishes no "cluster" doc-type) → no new domain → the current chantier does not exercise this debt.
See [[world-gateway-frontier-discipline]] [[cluster-seed-execution-state]].

See [[federated-unitrepo-p2p-design]] [[fragment-contribution-mediation-model]] [[multiplexor-two-models-design]]
[[osgi-frontier-underpopulated-chantier]] [[atlas-before-after-shift-at-merge]].
