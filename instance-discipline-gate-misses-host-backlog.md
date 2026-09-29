---
name: instance-discipline-gate-misses-host-backlog
description: The INSTANCE_DISCIPLINE gate only reads osgi/ bundles (the staging extension scans embed-capability jars), so it is BLIND to host space (exec/seed-master). It reported "2 statics left" while seed-master had ~25 more FluentTopicRunner.runDuring call-sites. Gate coverage needs extending or documenting.
metadata:
  type: project
---

**Backlog (raised 2026-06-26):** the staging-extension gates (RECORD_PURITY / SPEC_COVERAGE /
INSTANCE_DISCIPLINE) read the `osgi/` bundles' bytecode via ASM — the jars that carry the
`io.seedmatic.rke2lab.embed` capability, scanned by the build-time Maven extension. They do **not** see
`exec/seed-master` (host space) nor anything outside the embedded OSGi topology. So the
INSTANCE_DISCIPLINE summary ("0 error, N warn") counts ONLY osgi/ statics.

**The trap it sprung (2026-06-26):** migrating `FluentTopicRunner.runDuring` static→instance, the gate
said "instance-discipline: 0 error, 2 warn" (FluentTopicRunner + ExactRosterDoctor). But the static had
~25 call-sites — most in `exec/seed-master` (IncusResourceBootstrap, TargetChecksumPipeline,
ApplicationPipeline, BootstrapPipeline), invisible to the gate. Only the *compiler* caught them (the
signature change broke them). The gate gave a false "almost done" — the [[verify-state-before-labeling]]
failure mode, mechanised.

**The fix (when scheduled):** either (a) extend the gate's reach to host modules (run the ASM scan over
exec/ + host/ jars too, not just embed-capability bundles), or (b) explicitly DOCUMENT in the gate
summary that the count is osgi/-only, so the number is never read as "total statics in the codebase".
Option (a) is the real one — a static helper is a design smell wherever it lives, not only in a bundle.
Note host space is not OSGi, so the *other* two gates (record-purity, spec-coverage) may not transfer
cleanly; INSTANCE_DISCIPLINE does (it is about object-graph navigability, [[object-graph-navigability-principle]],
universal). Sibling of [[dependency-analyze-gate-backlog]]. See [[build-gates-over-review-reminders]]
[[refactor-statics-on-touch]] [[spec-coverage-gate-state]].
