---
name: running-session-keeps-its-resolved-memory-path
description: A session resolves autoMemoryDirectory ONCE at start; changing settings.local.json mid-session does not move where it writes — measured, and it is what forked the memory tree.
metadata:
  type: feedback
---

**A running session keeps the `autoMemoryDirectory` it resolved at start.** Editing
`.claude/settings.local.json` while the session is live does NOT redirect its memory
writes. Measured 2026-09-29 on a session spanning 2026-09-27 20:49 → 2026-09-29 18:xx:

- the setting was corrected to this worktree's own `.claude/memory` at **09-27 22:44**,
- yet memory files were still being written into `rke2lab.d/main/.claude/memory` at
  **09-28 12:48** (mtimes), i.e. ~14 h after the change.

**Why:** the path arrives in the session's system prompt, injected at start. The
settings file is read then, not per write.

**How to apply:**

1. After changing `autoMemoryDirectory`, the fix takes effect for the NEXT session.
   For the current one, write memory with an explicit absolute path into the intended
   directory rather than trusting the configured one.
2. Before writing memory in a long session, check whether the directory named in the
   system prompt is still the one you want — on a worktree created or re-pointed
   mid-session, it will not be.
3. ⚠️ Do not infer "not written by this session" from "not modified today". A session
   can span days; this one ran ~45 h. Compare mtimes against the session's real span
   (first/last timestamp in its `.claude/projects/<slug>/<id>.jsonl`), not against
   today's date. I made exactly that wrong inference and the user corrected it.

**The damage it caused, and the shape of the fix:** two memory trees diverged from one
commit — 248 files in the branch worktree, 325 in the `main` checkout, 175 identical.
Neither was complete, and a dead-`[[link]]` audit run over one of them reported **7
false deaths** (see `MEMORY.md`'s Known debt, corrected there). The reconciliation was a
git **3-way merge**, not a per-file winner: neither side was uniformly ahead — `main`
carried the migrations (`hub:` prefixes, renames, the MEMORY.md restructure), the
branch carried newer content. Git auto-merged all but 2 files.

★ The durable rule that followed: per-worktree memory directories do not prevent
forking — they *guarantee* it. Treating memory as branch content that "merges at merge"
is the reasoning that produced the fork, because the merge is the step nobody performs.

## Resolved 2026-09-29 — memory is an ORPHAN BRANCH, not branch content

The knowledge base moved OUT of the code branches onto an orphan `memory` branch with
its own worktree at `<repo>.d/memory` (341 files). No code branch carries
`.claude/memory/` any more, so there is one working copy and one line of history:
forking becomes impossible rather than merely discouraged.

**Why a branch and not a second repo** (the first proposal, and also
`MEMORY-STRUCTURE-SPEC.md` step 1's `claude-memory`): rke2lab already uses orphan
branches as independent artifacts — `flox-catalogue` (109 commits) and `seed-incluster`
(46), neither sharing an ancestor with `main`. A branch gives the same isolation with no
new repo, no subtree machinery and no cross-repo sync. The user proposed it; it is
strictly better than what I suggested.

**The two guards that make it hold**, both in `.claude/bin/`:

- `memory-commit.sh` (**SessionEnd**) — commits + pushes the memory worktree. Needed
  because the session that writes there is never the one that commits from there; that
  gap is what left 32 files dirty for 11 days.
- `memory-guard.sh` (**SessionStart**) — shouts if `autoMemoryDirectory` is unset or
  points anywhere but the memory worktree, or if that worktree is dirty. At START,
  because a running session cannot be redirected (above). Both locate the worktree by
  **asking git which one is on the `memory` branch**, never by assuming a path.

See [[workspace-is-not-a-cache]] [[measure-the-derived-value-not-the-assumed-one]].
