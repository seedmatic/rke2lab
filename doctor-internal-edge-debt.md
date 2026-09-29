---
name: doctor-internal-edge-debt
description: "INTEGRATED into design/pre-integration 2026-06-21 (squash 4e3e1427): Placement 2 closed doctor's INTERNAL edge AND its tests went full-OSGi. doctor-port is the TRUE membrane (value vocabulary + contracts + DoctorConsultingService); doctor-core holds 8 package-private actors and depends on the port; doctor symmetric with manifests/netplan, compiler-enforced. Tests run IN the OSGi container via -test fragments (doctor-port-test, doctor-core-test): the ReferralReplies Maven cycle dissolved, the 5 HOST-parked tests rehomed to doctor-port, doctor-testkit module deleted. THREE durable facts: readers are read-edges (→ port); package-private (not bnd Export-Package) IS the membrane mechanism; a flat-JVM test of bundle code is a world-incoherence the fragment fixes."
metadata:
  node_type: memory
  type: project
---

## What it was, why it mattered

The internal/external edge model (`docs/architecture/patterns/frontier-playability-model.adoc`,
[[system-space-world-universe-glossary]]) revealed that the doctor extraction (`ac2fae1b`) closed
doctor's EXTERNAL edge (`SnapshotSource`→pulumi) but left its INTERNAL edge MISSING: 40+ controlplane
files imported `doctor-core` impl types directly. doctor was the smoking gun proving the internal-edge
concept is PRESCRIPTIVE — its value is the leakage its absence allows. manifests/netplan were the clean
reference (consumers cross `-port` only).

**Placement 2** (the user's call, against Placement 1): make `doctor-port` the TRUE membrane — migrate
the value vocabulary INTO it, not just put a contract in core. Rationale: "if the records aren't in the
right place, repair NOW — otherwise never." The code must resemble the figure (re-entrance).

## INTEGRATED 2026-06-21 into design/pre-integration (squash 4e3e1427)

`DoctorConsultingService` (4 ops: consult / recordForCurrentPatient / cohortFinding /
reviewOpenProblems) is the internal edge in doctor-port. The membrane carries the value vocabulary +
contracts + the readers; doctor-core holds 8 package-private actors and depends on the port (acyclic).
Two public assembly seams return the contract without exposing actors: `Doctor.consultingService(...)`
(prod, prepends the Network+Cluster roster) and `ExactRosterDoctor.over(...)` (test). Zero actor
imported outside `io.seedmatic.rke2lab.doctor` (compiler-enforced). Tests went full-OSGi (`-test`
fragments `doctor-port-test` + `doctor-core-test`, first client of [[jgiven-osgi-testkit-shipped]]) —
the `ReferralReplies` Maven cycle dissolved, the 5 HOST-parked value-type tests rehomed to doctor-port,
the `doctor-testkit` module deleted. Build green across doctor-port/-port-test/-core/-core-test +
seed-master. NOT yet on `main` — main is the final destination, reached only when the whole is done.

**The DX payoff (the bonus):** the tests EXECUTE inside Felix but surface as individual CLICKABLE nodes
in VSCode Test Explorer — the `@TestFactory` harness maps each in-container result to a `DynamicTest`,
so one node per test, an isolated failure. Caveat (the nature of JUnit5 dynamic tests): they are NOT
statically discovered — you must FIRST run the `@TestFactory` class once to populate the panel, THEN the
individual nodes become clickable. After that warm-up you get the bare-JVM dev loop (click, navigate,
single-test re-run) while exercising the real OSGi wiring. Fidelity without losing the dev experience.

## Three facts proven on the code (the durable learnings)

1. **The readers are read-edges, not hidden actors.** `MedicalRecordReader`, `ConsultationReportReader`,
   `InterventionReader`, `ExpectationReader` take port types → return port types, touch NO actor, and
   have legitimate host production consumers (LiveMedicalRecordRegistry, MedicalRecordDump,
   InterventionLedgerSource read Pulumi / the file backend). They are the read-side twins of
   `SnapshotSource`/`InterventionLedgerWriter` → they MIGRATE to doctor-port and stay public. The
   hide-list dropped 12→8. *A read-edge belongs to the membrane.*

2. **package-private IS the membrane mechanism — NOT bnd Export-Package.** bnd narrowing governs OSGi
   resolution between bundles, not the Maven compile classpath. Dropping `public` from the 8 actors (all
   in one package) makes the Java COMPILER forbid cross-module import (compile + OSGi at once), while
   co-located doctor-core tests keep white-box. "Public interface, package-private impl." This is why
   sealing strictly required the model-tests to be relocated INTO doctor-core first. Reusable for every
   future internal edge.

3. **The ReferralReplies Maven cycle = a 2nd, independent motivation for the test-fragment model.**
   A shared test fixture that builds a port type must be its own module (`doctor-testkit`→doctor-port),
   but it's also used by doctor-port's own tests → Maven rejects `doctor-port ↔ doctor-testkit`
   (module-level, scope-blind). Root cause: our tests run in the bare-JVM flat classpath yet cover code
   that lives in classloader-isolated bundles. In OSGi this is NOT a cycle — a test FRAGMENT *is* its
   host (shared classloader), fixture visible with no reverse edge. RESOLVED: the fragment chantier
   landed (chantier B), the cycle is gone, the 5 HOST tests rehomed to doctor-port. No residual debt.

## The 8 sealed actors (durable list)

`Generalist`, `HealthSystem`, `ClinicalAccess`, `GrantPolicy`, `Grant`, `DriftSpecialist`,
`NetworkSpecialist`, `ClusterSpecialist` — all `final class`/`record`, package-private. (`Clinician`/
`ClinicianId` corrected mid-flight from STAY to MIGRATE: pure value/contract, not actors. `Grant` stays
core: a value record that never crosses the membrane.)

Handoff + measured 52-file test classification: branch `refactor/doctor-internal-edge`,
`docs/architecture/doctor/placement2-handoff.adoc`. See [[orchestration-purity-benefit]] (the OSGi
orchestration chantier the external `<target>-edge` extraction belongs to, next after integration).
