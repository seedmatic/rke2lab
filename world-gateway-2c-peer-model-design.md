---
name: world-gateway-2c-peer-model-design
description: 2C design CONVERGED (2026-06-30, brainstorm on feature/cluster-edge, NOT yet spec'd/built). The PEER MODEL — host and OSGi are two peers at the same level joined ONLY by opaque Documents + shared checkpoint identity; neither reaches into the other's logic. Drives REALM_BOUNDARY worklist 38→0. Branch roadmap (4 increments before merge): layout-first (gateway→world-gateway, spec EXISTS) → 2C → flip WARN→ERROR → remote-validation capstone (own spec TBD). Whiteboard figures in .claude/claude-preview.adoc. See [[world-gateway-2a-execution-state]] [[checkpoint-identity-to-seam-backlog]] [[osgi-aggregator-layout-spec-state]] [[options-always-as-c4-diagrams]].
metadata:
  type: project
---

## The load-bearing insight (the user's, refined through real-case tracing)

Both worlds stay PEERS at the same level: the host knows the STACK and is opaque to OSGi; OSGi knows
the DOCTOR and is opaque to the host. The seam carries ONLY opaque Documents + shared checkpoint
identity. The relationship is BIDIRECTIONAL (each publishes Document-typed ports the other calls),
each KEEPING its responsibilities — which is exactly what makes the later remote-OSGi (RSA) evolution
trivial: two peers that speak only Documents are already distributable; RSA just swaps in-process for
network, the contract unchanged. This is the literal meaning of world-**gateway**: a door between
peers, NOT a host driving a service.

## The key mechanism: the *Reader classes mix TWO layers — split them

Today's flat *Reader (in doctor-port, type=seam) conflate:
- **Layer 1 — stack-structural**: "given a snapshot, give me output named K" (SnapshotView.outputsNamed,
  version, outputsByKey). This is STACK knowledge → STAYS host.
- **Layer 2 — doctor-form**: "given the consultationReport sub-tree, rebuild ConsultationReport". This
  is DOCTOR knowledge — and it is the SAME shape OSGi itself wrote at 2B egress (toOutputMap) → STAYS OSGi.

Between the two layers crosses only a checkpoint-keyed `List<Document>` (opaque). Neither stack nor
doctor transpires. THE READERS ARE THE LEAK (doctor-port is flat yet houses them referencing
bundle-only doctor.records) — they migrate to doctor-core (layer 2); SnapshotView/SnapshotEntry/
SnapshotSource become host-internal in pulumi-edge (layer 1).

## The 3 host-provided ports flip record→Document (the bidirectional seam)

| port | today (record) | target (peer, opaque) |
| read | MedicalRecordRegistry.recordFor → MedicalRecord | DocumentJournal.historyOf(checkpoint) → List<Document> |
| write | InterventionLedgerWriter.append(Intervention) | append(Document) |
| review | recordForCurrentPatient + reviewOpenProblems(record,ledger) | reviewDrift(checkpoint) : void |

OSGi PULLS history via historyOf(checkpoint) — it knows checkpoint IDENTITY (already shared, legit) but
NOT the stack; the host answers in opaque blobs. MedicalRecord/InterventionLedger/Intervention stay
100% bundle-only.

## Two real cases traced (the proof, not assertion)

- **DriftReview (consult/review)**: today 3 records cross + the return is DISCARDED (reviewOpenProblems
  is a side-effect — the drift specialist persists itself). MedicalRecord round-trip is pure waste (OSGi
  already has access.record()). Collapses to `reviewDrift(checkpoint):void` — no input record, no return.
  REVEALED a 5th leak: InterventionLedgerWriter.append(Intervention) leaks a record OSGi→host → append(Document).
- **MedicalRecordDump (egress pure)**: today reconstructs each report to ConsultationReport then
  re-serializes via toOutputMap() — a Map→record→Map ROUND-TRIP of pure waste; the stored shape IS the
  toOutputMap shape, so YAML is byte-identical without reconstruction. DECISION: dump stays HOST-PURE —
  reads timeline (layer 1) + transcodes its own opaque consultationReport blobs JSON→YAML, ZERO OSGi.
  MedicalRecord/Visit is really a STACK-TEMPORAL structure (patient=stack identity → visits=timeline
  versions → reports=opaque blobs); only the LEAF content is medical and stays opaque.

ASYMMETRY between the two CLI tools (refines the "CLI = record logic OSGi-side, IO host" decision):
MedicalRecordDump (egress) = host-pure, no OSGi. RecordInterventionCommand (ingress) = NEEDS OSGi to
canonicalize raw CLI facts into a valid intervention Document (host doesn't know the doctor schema).

## Settled decisions (do NOT re-litigate)

- Reconstruction moves OSGi-side; host delivers raw (PEER MODEL, push/pull Documents).
- Patient is NOT medical — it's the (org,project,stack) STACK IDENTITY, host-built, mis-filed in
  doctor.records. Like Checkpoint, it goes to the seam as an identity. SnapshotView/SnapshotEntry are
  also mis-filed neutrals but go HOST-INTERNAL (not seam) — the peer model keeps stack vocab host-side.
- Decompose by zone (like 2B), worklist shrinks visibly per zone.
- Flip WARN→ERROR is a dedicated micro-commit, last (the merge point).
- 2C is written on the NEW layout names (world-gateway/WorldGatewayCatalog) — layout increment lands FIRST.

## Zone decomposition (proposed, pending final user OK)

- zone-1 · identities to seam (Checkpoint + Patient → world-gateway). ~38→~30. mechanical.
- zone-2 · consult residue I-1 (probes→Document, scenarios assert on Document fields; Observation leaves
  host — toOutputMap form + SymptomKind already exist). ~30→~22. mechanical.
- zone-3 · the peer node (3 ports record→Document; *Reader→doctor-core; SnapshotView/Entry/Source→
  host-internal; MedicalRecordRegistry impl→doctor-core @Component; DriftReview→trigger). ~22→~4. ARCHITECTURAL.
- zone-4 · CLI + isolat (RecordInterventionCommand ingress via OSGi; MedicalRecordDump host-pure;
  ClusterSchemaRef stops referencing doctor.records.SchemaRef). ~4→0. mechanical.
- zone-5 · the flip (remove @GovernedBy(REALM_BOUNDARY,WARN) → ERROR). 0, sealed. trivial.

## Why this matters

The REALM_BOUNDARY gate proves separation STATICALLY (no flat class references a bundle type). The
remote-validation capstone (increment 4, own spec) proves it DYNAMICALLY (host+OSGi in separate
processes gateway only Documents, same result as embedded). Static + dynamic = merge in confidence.
The design-of-record had this as YAGNI ("Runtime validation is designed, but YAGNI until remote"); the
user wants to reach that *until remote* to VALIDATE the model, not to productionize RSA.
