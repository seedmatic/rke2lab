---
name: gh-stack-operating-rules
description: "gh stack: half its commands are absent from --help (rebase, push, merge, navigation); modify is an interactive TUI no agent can drive; submit is SAFE for PR bodies but renumbers the stack; merging ONE intermediate PR is refused by gh pr merge AND the REST endpoint"
metadata:
  node_type: memory
  type: reference
  originSessionId: 62e4b01e-08e6-453e-9c0f-6cfcc67c6820
  modified: 2026-10-03T06:31:57.651Z
---

Measured on 2026-10-02/03 against `gh stack` v0.1.1 in rke2lab, over a live three-level stack.

## `--help` is incomplete — do not conclude a command is missing

`gh stack --help` lists `add`, `checkout`, `init`, `modify`, `unstack`, `view`, `submit`, `sync`
plus the navigation verbs. It does **not** list `rebase`, `push` or `merge`, **which all exist**. I
searched for `rebase` in vain and told the user it was unavailable; the user simply ran it.

| need | command |
| --- | --- |
| move between levels | `gh stack {trunk,bottom,down,up,top,switch}` — use these, not `git switch` |
| restack every level after a lower one moved | **`gh stack rebase`** (bottom-up, offers to enable `git rerere`) then **`gh stack push`** |
| create/refresh the PRs and fix their bases | `gh stack submit` |
| restructure (drop / fold / insert / reorder / rename) | `gh stack modify` — ⚠️ TUI |
| merge | `gh stack merge` — ⚠️ see below |

★ `gh stack rebase` produces **the same SHA** as a hand-rolled `git rebase <lower>`: both paths
converge, but only the first restacks *every* level and keeps the PRs in step.

## ⚠️ `gh stack modify` is an interactive TUI — unusable by an agent

It opens a terminal UI applied with `Ctrl+S`. Any stack restructuring is therefore an **operator**
gesture, not an agent one. ★ And a change is only applied **after** `Ctrl+S`: a half-done session
leaves the stack as it was, so measure the result rather than trusting the intent.

## ✅ `gh stack submit` does NOT overwrite PR bodies — but it renumbers the stack

Measured with a before/after backup of four PRs: **every title and body stayed byte-identical**;
only the targeted base changed. So it is safe to run on PRs whose descriptions carry reasoning.

★ It is also **the only** way to fix the base of a PR inside a stack — `gh pr edit <n> --base …`
fails with *"Cannot change the base branch because the pull request is part of a stack"*.
⚠️ It "clears" the stack and recreates it under a **new number** (#11 became #14). So a stack number
quoted in a note goes stale; cite the **PRs**, which do not move.

★★ Why this matters: without that base fix, a PR inserted above a new level keeps its old base and
its diff **doubles** — one PR showed **112 files / +3790** instead of **9 / +821**. A doubled diff
makes review useless **without breaking anything**, so nobody reports it.

## ⚠️ Merging ONE intermediate PR is refused by both obvious routes

| route | result |
| --- | --- |
| `gh pr merge <n>` | ⛔ *"must be merged using the asynchronous merge REST API"* (GraphQL) |
| `gh api -X PUT …/pulls/<n>/merge` | ⛔ 403 *"Merging stacked PRs via this endpoint is not supported"* |
| `gh stack merge [<n>]` | ✅ works, **but** merges *"all members of the stack up to and including your chosen pull request … in a single, all-or-nothing operation"* — so it **drags the floor along** |

⚠️ `gh stack merge` has **no `--dry-run`** and no way to bound the descent. **In a non-interactive
terminal — so for any agent — "the whole stack is merged without prompting".** Treat it as
irreversible and total.

★ What did work, measured: the **GitHub web UI** offered the merge action for an intermediate PR
after a `gh stack init --base <lower>`, and the merge landed in the lower branch without touching
the trunk. The mechanism was not pinned down; the UI is the route that worked.
★ A PR **outside** any stack merges normally (one targeting an orphan branch merged with no trouble).

## The stack's state lives on GitHub, not locally

Only `gh-stack.remote origin` sits in the git config — no local state file. `gh stack` **derives**
the stack from the open PRs. Consequence: as long as a `lower -> trunk` PR exists, that branch stays
a **level** of the stack. Dropping it locally works until the next `init`, which reconstructs it from
the PRs.
⚠️ And dropping a level can **rebase the levels above onto the trunk**, taking the dropped branch's
content out from under them. Nothing is lost — the branch still holds it — but the topology changes
silently. Check with `git merge-base --is-ancestor`, and note that such a check against a **deleted**
branch fails for the wrong reason.

See [[gitflow-realign-pr-per-increment]] [[build-barrier-covers-a-tree-not-a-sha]]
