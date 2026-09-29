---
name: systemd-gate-poll-to-listener
description: "User idea (2026-07-09): the seed-node systemd bootstrap gate polls runtimeStatus.snapshot() every 2s waiting for rke2lab.target to converge — systemd can do better with a LISTENER (push on target convergence) instead of a poll. A systemd-domain increment, to do AFTER the verifier fiber 2 isolated the gate."
metadata:
  type: project
---

**User (2026-07-09):** "systemd peut faire mieux, on peut remplacer le poll par un listener je crois."

**Context.** The seed-node bootstrap gate — `SeedNodeBootstrapWatcher.waitForBootstrapPreconditions`
(seed-master `controlplane/resources/`) — currently POLLS
`SeedSystemdAdapterRuntimeStatusSnapshot.snapshot(config)` every `GATE_RETRY_INTERVAL` (2s) in a
deadline loop, waiting for `status=ok && runtimePrecheckReady` (i.e. `rke2lab.target` converged, no
pending jobs / failed units). systemd/dbus can PUSH this instead: subscribe to job/unit state changes
(the dbus systemd manager emits `JobRemoved` / `PropertiesChanged` on the target) and wake on
convergence rather than sampling. Lower latency, no fixed 2s granularity, no busy wait.

**Why it is a separate increment (not part of the verifier fibers).** Fiber 2 (2026-07-09) ISOLATED
this gate out of the cluster verifier: it moved from `ClusterBootstrapReadinessVerifier
.checkKubeconfigPublished` (where it was phase-0, fused) up into `LiveClusterReadinessProbe
.kubeconfigPublished` (host, in front of the kubeconfig poll). So the gate is now a clean, isolated
host seam — the right shape to swap poll→listener without touching cluster reasoning. Doing it before
fiber 2 would have meant reworking fused code twice.

**Where the listener would live.** The dbus systemd edge (`osgi/domains/systemd/dbus-systemd-edge`,
`SystemdRuntimeProbe` is the current point-in-time contact) — add a subscribe/await-convergence
capability there, and `SeedNodeBootstrapWatcher` (or its successor) consumes it instead of looping on
snapshot(). Belongs to the systemd domain's own migration, alongside the broader domain triptych.

**How to apply.** Pick this up as a systemd-domain increment, NOT inside strate-1c verifier fibers.
Prereq (done): the gate is isolated in LiveClusterReadinessProbe. See
[[foundations-before-domain-migration]] (the systemd domain is one of the strate-2 domains).
