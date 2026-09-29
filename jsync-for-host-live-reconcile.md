---
name: jsync-for-host-live-reconcile
description: "I6 host.live reconcile — jsync ACTS (sync), java-diff-utils OBSERVES (the two deltas); design settled 2026-07-14"
metadata:
  node_type: memory
  type: project
  originSessionId: 2f937488-ea11-441b-b7a7-f56cb85ed71a
---

I6 (the GROW: reconcile `host.staging.N` → the physical `host.live`) is DESIGNED (gravé 2026-07-14 in
`docs/architecture/osgi/host-cellar-realisation-spec.adoc` § *The two deltas* + atlas Diagram V +
`host-slot-management.adoc`). Two bricks, matched to two roles:

- **OBSERVE — `java-diff-utils` (`io.github.java-diff-utils:java-diff-utils:4.17`).** Computes TWO
  deltas as `ReportModel`s (dogfooding I5), BEFORE any write, both pivoting on `staging.{live.syncedFrom}`
  (the staging the live currently mirrors — NOT arithmetic N-1: interleaved previews produce stagings the
  live never adopted). `change` = `staging.N ↔ syncedFrom` (the run's INTENDED change; computed ALWAYS,
  preview + live; travels in `host.staging.N`). `drift` = `host.live ↔ syncedFrom` (the live's out-of-band
  deviation; LIVE ONLY; is `host.drift.N`). Tree-walk (`Files.walk` → size/mtime/hash) gives per-file
  ADDED/REMOVED/MODIFIED; java-diff-utils only diffs INSIDE a text file. **Already an OSGi bundle** (BSN
  `io.github.java-diff-utils`).
- **ACT — `jsync` (`com.fizzed:jsync-engine:1.5.0`).** After deltas + (live) open gate, `setDelete(true)`
  syncs `staging.N → host.live`. Diff-based → R1 (mount dirs survive `--delete`) — PROVE with a test.
  **No BSN (nu manifest)** — but needs none: the GROW is HOST-side (Shape C, `com.pulumi`, seed-master),
  a flat classpath. So BOTH libs are direct HOST deps, **NO `*-wrap`, no OSGi bundle** (the victools/BSN
  closure trap of [[seed-broker-shape-drags-victools]] is an in-container concern, moot on the host).

RESOLVED (was the open risk in the old note): **`--backup-dir` is NOT needed.** `main`'s `gc` (evicted
bytes) becomes `host.drift.N`, a REPORT, not a byte backup. Rollback = re-sync a still-present immutable
staging, never an inverse patch, never the evicted bytes. The drift is the ONE delta no staging captures
(the live can drift: instance/corruption/tampering), which is exactly why it must be OBSERVED before the
blind `--delete` overwrites it.

Two I6 invariants: (a) `HostSlotSelector`'s `(max+1)%3` must SKIP the pinned `live.syncedFrom` slot (the
rotation protects the pivot tree — FS face of R1); (b) the promotion envelope `{host.live → SN,
host.drift.{DN} → prior syncedFrom}` is appended ONLY post-sync-success.
