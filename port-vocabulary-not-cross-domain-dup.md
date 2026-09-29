---
name: port-vocabulary-not-cross-domain-dup
description: "Analysis (user Q 2026-07-08 'le vocabulaire des ports est vaste — un vocab général multi-domaine éliminerait-il du spécifique?'): NO cross-domain vocabulary to extract. The frontier's size is ONE domain's load (doctor/readiness = 12 of world-gateway's 18), not inter-domain duplication. The generic multi-domain core the user sought ALREADY EXISTS and is minimal (Document/Coordinate/Domain/WireEnum = 6). The real (accepted) duplication is the Wire↔records TWINS, not between domains."
metadata:
  type: reference
---

**The question (user, 2026-07-08):** the port vocabulary feels vast; could a GENERAL multi-domain
vocabulary eliminate specific terms and cut duplication? Instructed from measured facts, the answer is
no — but the felt vastness is real and has a precise, different cause.

**Measured (2026-07-08):** exported types per port — manifests-port 25, world-gateway 18, doctor-port
7, netplan-port 7, systemd/incus 5, cluster/bbox 4, auth 2.

**world-gateway (the frontier, 18) splits into TWO families:**

- *Generic transport (6):* `Document`, `DocumentContract`, `Coordinate`, `Domain`, `WireEnum`,
  `WorldGatewayCatalog`. Truly multi-domain, already factored, already minimal. **This IS the general
  vocabulary the user was looking for — it exists.** Nothing to extract; it's done.
- *Domain-loaded (12):* `Checkpoint`, `SymptomKind`, `Consultation`, `Patient`, `Action`,
  `ObservationWire`, `InterventionWire`, `VisitWire`, `ReadinessCheckpoint`, `ReadinessVerdict`,
  `ReadinessAuthority`, `InterventionRequest`. ALL doctor/readiness — grep confirms NO consumer outside
  doctor/cluster/readiness/seed. So the frontier is big because ONE domain pushed 12 words onto it, NOT
  because domains duplicate each other.

**The small domain ports do NOT duplicate each other.** `ClusterReadinessContact`,
`IncusInstanceContact`, `SystemdRuntimeProbe` are authentically distinct intra-domain contracts —
different things, no common vocabulary to lift. (manifests-port at 25 is an intrinsically rich domain —
27 manifest units — not duplication.)

**The real duplication is NOT cross-domain — it's the Wire↔records TWINS.** Each doctor concept exists
TWICE: rich in the bundle (`doctor.records.Observation`/`Intervention`/`Visit`) and flattened at the
seam (`ObservationWire`/`InterventionWire`/`VisitWire`). That is the "same object, two natures" of
[[gateway-crossing-three-natures]], seen from the type census. It is PARTLY intentional (the seam MUST
flatten — a `JsonNode` payload once leaked jackson and caused a LinkageError,
[[document-seam-cannot-expose-jackson-jsonnode]]), so it is accepted debt, not a bug.

**Verdict (user, 2026-07-08): note the analysis, refactor NOTHING.** The generic core is already
isolated and minimal; there is no cross-domain generalization to extract; the Wire twins are the seam's
flattening price. Two chantiers were NAMED but declined for now (open them on a green base, not
speculatively): (a) reduce the 3 Wire twins to one form; (b) shrink the doctor's 12-word frontier load
by keeping some bundle-internal and traveling them as opaque payload. See
[[world-gateway-frontier-discipline]] [[gateway-crossing-three-natures]].
