---
name: seedlog-logback-migration-backlog
description: "BACKLOG (host-space logging): unify seed-master logging on logback/slf4j. Today it is split — logback IS the slf4j backend (3 logback.xml: seed-master/manifests/netplan) but SeedLog uses java.util.logging (JUL) directly, AND io.grpc/com.pulumi log via JUL with NO jul-to-slf4j bridge installed, so logback never sees the gRPC engine noise. Surfaced 2026-06-17 while debruiting Pulumi-inline tests."
metadata:
  node_type: memory
  type: project
---

**The split (verified on the worktree 2026-06-17):**
- `slf4j-api` is a seed-master dep; three `logback.xml` exist (seed-master, manifests, netplan) → logback
  IS the prod slf4j backend.
- BUT `SeedLog` (`controlplane.SeedLog`, the shared seed logger) is built on `java.util.logging.Logger`
  (JUL), not slf4j.
- AND `io.grpc` / `com.pulumi` log via JUL directly, with NO `jul-to-slf4j` + `SLF4JBridgeHandler`
  installed → logback never receives those records; they exit via JUL's default ConsoleHandler. This is
  why the benign "ManagedChannel was garbage collected without being shut down" SEVERE traces appear raw
  in test output.

**Why this is host-space only:** the gRPC/Pulumi noise is host space (the channel to the engine, per
the integration atlas). It must NOT be routed to the OSGi `LogService` (pure/model space) — that would
conflate the two spaces (the same defect class as `Rke2labConfig → ConfigLoader`). Any logging
unification here stays in host space. See [[step2-decomposition-state]] (the two-spaces frame).

**Decided 2026-06-17:** debruit the deploying tests NOW with a host-space JUnit5 extension on the JUL
`io.grpc` logger (`GrpcChannelNoiseCapture`, swallow+expose, no assert — channel GC is
non-deterministic). The proper fix is deferred to this backlog item, NOT mixed into the test cleanup.

**The migration (when picked up):**
1. Install the JUL→slf4j bridge: add `jul-to-slf4j`, call `SLF4JBridgeHandler.removeHandlersForRootLogger()`
   + `.install()` at startup, set `-Djava.util.logging.config` accordingly. Then logback sees `io.grpc`.
2. Add a logback rule for `io.grpc` (e.g. level OFF/WARN in the test profile) → kills the noise globally,
   and `GrpcChannelNoiseCapture` can then be retired.
3. Migrate `SeedLog` off `java.util.logging.Logger` to slf4j so seed-master has ONE logging facade. Mind
   `SeedLog`'s Pulumi verbosity-aware level mapping (PulumiLogSink / LogEvent) — preserve that contract.

Scope note: this is a host-space chantier of its own, NOT part of the BootstrapConfig-relocate slice or
the @Tag("pulumi") test-infra cleanup. Pick it up standalone.
