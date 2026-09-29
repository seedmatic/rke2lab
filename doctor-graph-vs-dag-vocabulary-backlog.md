---
name: doctor-graph-vs-dag-vocabulary-backlog
description: Vocabulary drift to reconcile — the doctor's reasoning structure is called a "DAG" everywhere it is discussed (runbook DAG, shared DAG, the new DomainDag* types) but the one type named for it says "Graph" (DoctorGraph). Reconcile to doctor-DAG vocabulary, folding into Plan 2 (the Document migration) where the DomainDag* names land. Nuance: DoctorGraph is the PRODUCER of the DAG, not the DAG itself.
metadata:
  type: project
---

**Backlog (user, 2026-06-27): "doctor graph is a DAG — we already refer to a document DAG in the
codec / the new design. We should have doctor DAG instead of Graph. I mean a doctor DAG producer."**

The split, verified on the code:

- The doctor's structure is called a **DAG** wherever it is actually discussed: `ConsultationLog`
  and `ConsultationReport` javadoc ("the runbook **DAG**, layer 3"), `SystemdAdapterStage` ("joins
  the shared **DAG**"), `ClusterReadinessStage` ("the follow-the-chain **DAG** edge"), and — load
  bearing — the new world-gateway design's type names: `DomainDagMapper`, `DomainDagMultiplexor`,
  `DomainDagSource`, `DomainDagAdapter` (see [[world-gateway-document-design]]).
- But the one TYPE named for it uses **"Graph"**: `DoctorGraph`
  (`osgi/doctor/doctor-core/.../internal/DoctorGraph.java`), plus `RealGraphInjectionTest` and the
  "graph" prose in `DefaultHealthSystem` / `ExactRosterDoctor` / `HealthSystemTest`.
- OUT OF SCOPE: the unit-repo `ManifestsVisitOrder` "graph" / `CrossDomainRule` — that is the unit
  dependency-resolution graph, a different domain, not the doctor's DAG.

**The nuance (the user's "producer" clarification).** `DoctorGraph` is NOT literally the DAG — its
javadoc says it is "the single construction path for the doctor graph: admit the patient … employ
the Generalist over the roster + the run's DriftSpecialist," returning the `ConsultingService`. So it
is the actor APPARATUS (generalist + specialists + clinical access) that, through consultations,
PRODUCES the runbook DAG (the aggregated ConsultationReports / medical record). Therefore the
reconciliation is NOT a blind `DoctorGraph` → `DoctorDag` rename (that would conflate the apparatus
with its output). It is: recognise `DoctorGraph` as the **doctor DAG producer**, which lines up with
the new design where the doctor domain's `DomainDagMapper` emits the doctor's DAG as a `Document`.

**When to do it: fold into Plan 2, not Plan 1.** Plan 1 (`wip/plans/2026-06-27-realm-boundary-gate.md`)
is the `REALM_BOUNDARY` build gate — pure build infra, touches no doctor type. Plan 2 (the Document
migration, written from the gate's WARN worklist) is the pass that INTRODUCES the `DomainDag*`
vocabulary into the doctor domain — so reconciling the `DoctorGraph` → doctor-DAG-producer naming
belongs in that same change, against the same worklist the user noted ("we already have a gate with
warns emitted and a task for cleaning up — handle this new one at the same time"). Settle the exact
names when Plan 2 designs the doctor domain's `DomainDagMapper`: decide whether `DoctorGraph` becomes
e.g. `DoctorDagProducer` / stays the apparatus while the mapper carries the DAG-emission name.

See [[world-gateway-document-design]] (the DomainDag* vocabulary) [[realm-boundary-gate]] (Plan 1).
