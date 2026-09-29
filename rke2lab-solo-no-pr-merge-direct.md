---
name: rke2lab-solo-no-pr-merge-direct
description: "rke2lab is a solo project (just the user + Claude). No GitHub PRs — the work is already described in the documentation/commits. Integrate finished branches by direct merge to origin/main (fast-forward after rebase), then remove the worktree + delete the branch."
metadata:
  node_type: memory
  type: feedback
---

On rke2lab the user works alone with Claude — there is no second reviewer, and the work is already
documented in the commits + the `docs/` / `wip/` artifacts. So **do not open GitHub PRs** for rke2lab
work.

**How to apply:** when a feature/refactor branch is finished and green, integrate by DIRECT MERGE to
`origin/main` — rebase the branch onto current `origin/main`, `git merge --ff-only`, push, then remove
the external worktree and `git branch -d` (per [[hub:external-worktree-operating-model-state]] cleanup
recipe). When the question "merge / PR / keep" comes up, default to merge for rke2lab. (This is a
per-project preference; other repos in the fleet may differ — don't generalise without checking.)
Contrast: the harness's `finishing-a-development-branch` skill offers PR as an option; for rke2lab,
skip it.
