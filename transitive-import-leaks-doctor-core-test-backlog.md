---
name: transitive-import-leaks-doctor-core-test-backlog
description: Backlog (2026-06-28) — doctor-core-test imports io.seedmatic.rke2lab.doctor.records.* and doctor.spi.* but declares NEITHER doctor-records NOR doctor-spi directly (both reach it transitively via doctor-core, provided). Violates [[direct-dependency-for-every-import]]. Pre-dates Option B; fix in a hygiene pass.
metadata:
  type: project
---

While enforcing [[direct-dependency-for-every-import]] during world-gateway Option B, two
further transitive leaks in `osgi/doctor/doctor-core-test/pom.xml` were found but left for a
dedicated hygiene pass (out of Option B's blast radius — the actor tests used these long before
Option B):

- `GeneralistConsultDocumentTest` / the actor tests import `io.seedmatic.rke2lab.doctor.records.*`
  (Symptom, ConsultationReport, Expectation, MedicalRecord, Patient, …) — but the module declares
  no direct `doctor-records` dep; it arrives transitively through `doctor-core` (provided).
- They also import `io.seedmatic.rke2lab.doctor.spi.*` (Specialist, via FakeSpecialist) — no direct
  `doctor-spi` dep either; same transitive path.

Fix: add `doctor-records` and `doctor-spi` as DIRECT `provided` deps of doctor-core-test (the
fragment's src/main bytecode resolves against the doctor-core host at runtime, like the existing
`doctor-core`/`doctor-port`/`gateway-port`/jackson entries). Other `-test` fragments may have the
same pattern — sweep them all in the same pass for uniformity.

(Option B already added the missing DIRECT `gateway-port` + `jackson-core` + `jackson-databind`
to doctor-core-test, since those were squarely in its scope.)
