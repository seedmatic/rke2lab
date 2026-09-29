---
name: dbus-systemd-probe-poll-backlog
description: "BACKLOG (host-side, exec/seed-master): the systemd-readiness gates POLL the dbus-on-TCP probe in a while(deadline){ probe(); sleep(interval); } loop, and DbusSystemdProbe.probe() RE-OPENS a fresh D-Bus connection every tick. systemd exposes D-Bus SIGNALS (JobRemoved, PropertiesChanged, Reloading) → a persistent connection + signal subscription would replace reconnect-and-poll with a push listener — the same poll→listener fix applied to the OSGi testkit in R1. Noted 2026-06-18 on the user's rule: poll/sleep is never good. NOT on the runtime-migration critical path; host-side, no -Plive."
metadata:
  node_type: memory
  type: project
---

## The smell (user: "jamais bon de faire du poll/sleep")

Found while sweeping for the R1 poll-vs-listener anti-pattern across the bundles. The OSGi bundles are
CLEAN (the only ServiceTracker in the repo is the one R1 added to the testkit; [[osgi-runtime-r1-scr-state]]).
But host-side in `exec/seed-master` the systemd-readiness gates poll:

- `controlplane/systemd/SeedSystemdAdapterEndpointGate` — `while (nanoClock < deadline) { probe();
  sleeper.accept(retryInterval); }` with phase-stepped intervals (EARLY 15s → MID 8s → LATE 3s →
  FINAL 2s) + a second loop waiting for `incus exec` reachability (2s). The `Thread.sleep` is wrapped
  as an injected `Consumer<Duration> sleeper` (testable), but it is still a busy-wait.
- `controlplane/resources/SeedNodeBootstrapWatcher` and `controlplane/readiness/
  ClusterBootstrapReadinessVerifier` — same `sleep(Duration)` poll shape.
- `controlplane/readiness/DbusSystemdProbe.probe(config)` is ONE-SHOT: it OPENS a fresh
  `DBusConnection` over tcp, reads target/cloud-init/jobs/failed-units state, and CLOSES it — every
  single tick. So the cost is connect-read-disconnect × N, not just N reads.

## Why it is the same anti-pattern (and the fix)

R1 killed a `for (50 × 10ms)` poll of the OSGi service registry by using `ServiceTracker.waitForService`
— the framework NOTIFIES on registration. systemd is the symmetric case: D-Bus is an event bus, and
`org.freedesktop.systemd1.Manager` emits **signals** — `JobRemoved` (a queued job finished),
`UnitNew`/`UnitRemoved`, `PropertiesChanged` (per-unit ActiveState transitions), `Reloading`. The
idiomatic shape:

1. Open the `DBusConnection` ONCE (persistent), `Manager.Subscribe()` so systemd emits signals.
2. Register signal handlers (the `dbus-java` lib already in use supports `addSigHandler`) for the
   readiness predicate (mandatoryTarget active + nJobs==0 + nFailedUnits==0 + cloud-init success).
3. Block on a latch/future the handler completes; keep a deadline as a SAFETY timeout, not the primary
   wait. Re-evaluate the snapshot on each relevant signal instead of reconnecting on a timer.

This removes both the busy-wait AND the per-tick reconnect. The `Observation`/`SystemdStatusSnapshot`
output contract stays identical (it is still a snapshot read on the predicate edge).

## Sequencing / scope

- HOST-side (`exec/seed-master`), NOT an OSGi bundle and NOT on the OSGi runtime-migration critical
  path (R1–R6, [[osgi-runtime-migration-state]]). Independent chantier.
- Touches the master-provisioning path → the rewrite proves under `-Plive` (real systemd-adapter over
  dbus-on-TCP), and the gate already injects its collaborators (clock/sleeper/probe/exec) so a
  signal-driven variant is unit-testable with a fake signal source first.
- The three `Thread.sleep` callers share the `sleeper` injection seam; if one gate moves to signals the
  others can follow the same shape (uniformity).

See [[osgi-runtime-r1-scr-state]] (the poll→listener fix this mirrors), [[master-provisioning-state]]
(the live systemd-readiness contract), [[osgi-logging-and-cli-debt]] (sibling host/bundle idiom debt).
