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

★ The durable rule that follows: **memory is branch content, so it merges with the
branch.** Per-worktree memory directories (what the `worktree` skill configures) do
not prevent forking — they *guarantee* it. The failure is never the fork; it is
leaving it unmerged, and auditing across it.

See [[workspace-is-not-a-cache]] [[measure-the-derived-value-not-the-assumed-one]].
