---
name: merge-from-target-worktree
description: "Integrate a finished sub-branch with a SQUASH merge (one commit on the target). Claude DOES the merge + teardown itself (no permission hand-off), BUT only from the SESSION sitting in the TARGET worktree — NOT the sub-branch's own session, which cannot saw off the worktree/branch it is sitting on. The sub-branch session finishes + commits everything + verifies, then HANDS OFF to the target session for merge+teardown. Status line goes INSIDE the merge commit (amend, hash-free). Dérives caught by the user 2026-06-18: merged from the wrong worktree; assumed ff not squash; status in a trailing commit not amended; over-hardened into 'the merge is the human's' (wrong — Claude does it); and — the host-space dérive — the sub-branch session merged AND destroyed its own worktree/workspace/branch out from under itself."
metadata:
  node_type: memory
  type: feedback
---

When a feature/refactor sub-branch is done and ready to fold into its parent (here:
`refactor/layout-skeleton` → `design/target-module-layout`), two things are fixed:

**1. It is a SQUASH merge, not a fast-forward.** We want ONE commit on the target branch per integrated
sub-branch, regardless of how many commits the sub-branch accumulated (here: the refactor + a memory
note = 2 → collapse to 1). So the integrating gesture is `git merge --squash <sub-branch>` then a single
`git commit`, NOT `--ff-only`. The sub-branch's individual commits stay as working history on the
sub-branch; only the squashed summary lands on the target. Pairs with [[rke2lab-solo-no-pr-merge-direct]]
(the *what* — direct merge, no PR) — this is the *how*.

**2. The merge + teardown belong to the SESSION sitting in the TARGET worktree — NOT the sub-branch's
own session. This is the rule about WHICH SESSION, and it is a physical constraint, not a preference.**
Claude DOES the merge itself (no permission hand-off to the user — see the over-correction warning
below); but the session that may do it is the one whose cwd/workspace is the TARGET worktree
(`design/…`), NOT the session living in the sub-branch's worktree. **A session cannot saw off the
branch it is sitting on:** the sub-branch session that runs `git merge --squash` + `git worktree remove`
+ `git branch -D` is deleting its own worktree, workspace and branch out from under itself — exactly
what went wrong 2026-06-18 on `refactor/host-space` (it finished the work AND destroyed its own seat).
`cd`-ing from the sub-branch session into the target worktree to run the merge there does NOT satisfy
the rule — "from the target worktree" means the SESSION that owns it, not a directory you stepped into.

Why: under the external-worktree model one workspace = one VSCode window = one session steering one
branch (see [[sops-worktree-smudge-noise]], hub `external-worktree-operating-model-state`). The
integration of a sub-branch is the target session's act because (a) advancing the target HEAD from the
sub-branch session mutates a branch the target session sits on, and (b) the teardown removes the very
worktree the sub-branch session is running in.

**How to apply — split by session:**
- *Sub-branch session* (the one that did the work): finish + commit EVERYTHING (code AND memory) +
  build-green + verify (`git log <target>..<sub>` shows what will squash; `git status` clean). Then
  STOP. Announce it is ready to integrate and HAND OFF to the target session — do NOT merge, do NOT
  teardown, do NOT `cd` to the target worktree to do it.
- *Target session* (the one sitting in `design/…`, e.g. this one): `git merge --squash <sub>` →
  resolve any conflict → update the status line → `git commit` → `git worktree remove <sub>` →
  `git branch -D <sub>` → delete the `.code-workspace`.

(Do NOT over-correct this into "the merge is the human's" — that was a wrong hardening that
re-introduced permission friction; the merge is Claude's to perform, just from the RIGHT session.
Genuine permission hand-off is only the runtime boundary in [[standing-autonomy-except-runtime-config]].)

**3. The integration-status line belongs INSIDE the merge commit — amend, don't append.** A memory
index line that flips a chantier from "awaiting merge" to "shipped/merged" is part of the integration,
so it rides the SAME commit as the squash, added via `git commit --amend` while that commit is still
unpushed — NOT a separate trailing "docs(memory): mark X merged" commit. Reason (the lesson from the
bnd-annotations spike, now generalized): a commit cannot truthfully declare its own merge, and a
follow-up commit just to set status is noise. **Corollary — never cite the merge commit's own hash in
that line**, because the amend changes the hash → the reference goes stale instantly (same
self-reference trap). Write the status hash-free (`SHIPPED to <target> (squash merge <date>)`); the
git history already carries the hash. (User, 2026-06-18: "tu devrais modifier le statut avant de merge,
ce serait encore plus propre.")

**Corollary — commit everything BEFORE close/teardown.** The working tree is ephemeral; only what is
committed onto the sub-branch survives to be squashed. Anything worth keeping (code AND session memory:
state files, MEMORY.md index lines, feedback notes) must be committed to the sub-branch first — memory is
NOT exempt because it lives under `.claude/memory/`, it rides the same branch into the squash. Check
`git status` is clean as the last gesture before handing off. (User reminders 2026-06-18: "si on close,
on doit toujours tout committer ce qu'on veut sur la branche" and "on veut un seul commit au merge".)

**4. Garden the memory AT EACH MERGE — prune the *how*, keep the *what/why* (user, 2026-06-20).** Merge
is the moment the target is reached, so it is also the moment to throw away the scaffolding — the SAME
discipline as [[migration-branch-no-fallback]], applied to memory. Before (or as part of) the squash,
the sub-branch session should collapse its process narrative: a resume-state becomes a one-line "shipped
@ `<branch>`, see the durable notes" pointer (or is deleted), spent ★ history markers / "WI-X done" logs
go, and the durable knowledge (invariants, rules, settled decisions + rationale) is kept, condensed.
**Why this is a per-merge step, not a someday task:** the user's lesson (2026-06-20) is that R4 let 14+
dense, cross-linked notes accumulate precisely because the pruning was deferred — "idéalement on aurait
dû le faire à chaque merge". Deferring memory-gardening rots the same way deferring a `mvn install`
cleanup does: the backlog compounds into a big dedicated pass ([[memory-synthesis-prune-the-how]]) that a
per-merge habit would have prevented. So: distinguish durable-knowledge notes (keep) from
process-narrative notes (prune) every time, and never let a shipped resume-state survive a merge
un-collapsed. (git history already holds the blow-by-blow; memory should hold what the system IS.)
