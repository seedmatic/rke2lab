---
name: commit-cadence-push-at-live-checkpoint
description: Commit as work completes; local-path flake refs let controller commits propagate without push
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-14T08:10:17.680Z
---

We **always commit** work as it completes (each repo, at the fils-de-l'eau granularity). Committing
on a topic branch is standing authorization — do NOT ask.

**Push is deferred** — but the reason is subtler than "wait for the live checkpoint". A controller
consumed by rke2lab as a **github-rev flake input** only propagates after push + `flake.lock` bump.
The better dev model (user's call, 2026-09-14): reference the controller flakes via a **local `path:`
input** (the worktrees are siblings on disk under `<repo>.d/…`), so a **commit in the controller repo
propagates immediately** to rke2lab with no push and no relock — the commit *is* the checkpoint.

**How to apply:** commit freely; for cross-repo (controller ↔ rke2lab) iteration relock the flake
input to a local `path:` — `nix flake lock --override-input <name> path:<abs-worktree>` — and **COMMIT
the resulting flake.lock** (user's call 2026-09-14: single-dev + deterministic `<repo>.d/` layout, so
the local-path lock IS the committed dev state; a controller commit then propagates to rke2lab with no
push). Caveat: the path is absolute/host-specific — at the live checkpoint, push the controller branch
+ relock to the pushed github rev + commit that. Push only for sharing/CI. Live-system ops (pulumi up,
kubectl apply) remain the user's to trigger. See [[flox-controller-build-deploy-state]].
