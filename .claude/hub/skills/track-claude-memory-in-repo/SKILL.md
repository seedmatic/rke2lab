---
name: track-claude-memory-in-repo
description: Use when the user wants Claude's memory persisted/version-controlled in git so it survives window reloads, compaction, or machine changes. Points Claude's auto-memory — via the native autoMemoryDirectory setting (absolute path in .claude/settings.local.json) — at the worktree of a dedicated ORPHAN `memory` branch, NOT at a code branch's .claude/memory/ (that forks; measured). Triggers on phrasing like "track memory in git", "persist memory in the repo", "version-control my memory", "I lost my memory on reload", or wanting the same memory setup applied to another repository.
tools: Bash, Read, Edit, Write, AskUserQuestion
---

# Track Claude memory in a git repo

Claude's auto-memory (the `MEMORY.md` index + topic files) defaults to
`~/.claude/projects/<repo-slug>/memory/` — outside git, lost on reload, and
**repo-wide** (all worktrees of a repo share one dir, keyed off the git
repository, not the worktree). This skill relocates it into git.

## Where it goes: an ORPHAN `memory` BRANCH, not a code branch

⚠️ **This skill used to say "the repo's tracked `<repo>/.claude/memory/`, so git
version-controls it and it rides along at merge". Measured 2026-09-29: it does not ride,
it FORKS.** Memory authored inside a branching repo had split into two trees from one
commit — 248 files in one worktree against 325 in another, 175 identical — and a
dead-`[[link]]` audit run over one of them recorded **7 live facts as dead**. "Rides along
at merge" fails because the merge is the step nobody performs; per-worktree or per-branch
memory does not prevent forking, it guarantees it.

So the target is a branch that has no code and no merges: an **orphan** branch (typically
`memory`) with its own worktree, e.g. `<repo>.d/memory`. One branch, one working copy, one
line of history — forking becomes impossible rather than discouraged.

Preferred over a dedicated memory REPO (the obvious alternative) when the repo already uses
orphan branches as independent artifacts — rke2lab has `flox-catalogue` (109 commits) and
`seed-incluster` (46), neither sharing an ancestor with `main`. Same isolation, no new repo,
no subtree machinery, no cross-repo sync. Use a separate repo instead when the memory must
be shared across several repos — that is what `claude-hub` is for, and its subtree copies
are measurably 0-divergent precisely because there is one source.

Two consequences the setup must handle, or the model leaks anyway:

- **Something must COMMIT the memory worktree**, since the session writing there is not the
  one committing from there — that gap left 32 files dirty in a checkout for 11 days.
- **The wiring must be checked at session START**: a running session cannot be redirected,
  its memory path is resolved once into the system prompt (measured — a setting corrected at
  09-27 22:44 was still being ignored 14 h later).

In the external-worktree family both are hooks: `memory-commit.sh` (SessionEnd) and
`memory-guard.sh` (SessionStart).

## Mechanism: the native `autoMemoryDirectory` setting

`autoMemoryDirectory` (Claude Code settings) redirects **both reading and
writing** of auto-memory to a directory you name. It accepts an **absolute path
or a `~/`-prefixed path** (no relative paths) and is honored from
`.claude/settings.json` or `.claude/settings.local.json` under the same
workspace-trust rule as hooks. Point it at the repo's tracked memory dir and
Claude writes straight there — **no symlink, no slug computation, no home-dir
bridge.** Verified working on darwin (2026-08-14).

Because the path must be absolute, it is host- and worktree-specific → it lives
in the gitignored, per-checkout **`.claude/settings.local.json`** (the same file
that carries the Bedrock env), not the committed `settings.json`. In the
external-worktree model the `worktree` skill writes this line at worktree
creation, so a fresh checkout is wired automatically; this skill is the manual
once-per-repo setup for repos that don't use that flow.

## Scope: memory/ ONLY — never the transcripts

Session transcripts (`*.jsonl`, `tool-results/`) live under
`~/.claude/projects/<slug>/` (or the `CLAUDE_CONFIG_DIR` equivalent) and are NOT
memory — often tens of MB churning every session. `autoMemoryDirectory` moves
**only** the memory dir; transcripts stay where they are (surface them in the
Dock sidebar with `link-sessions.sh` if needed — a separate concern). Track only
`<repo>/.claude/memory/`.

## Checklist

Create a TodoWrite item per step and do them in order.

1. **Confirm visibility (BLOCKING for public repos).** `git remote -v`; if
   `origin` is public, use AskUserQuestion to confirm the user accepts that
   memory notes (provisioning state, working-style prefs, design decisions)
   become publicly visible. Do not proceed on a public repo without it.

2. **Create the orphan `memory` branch, seeded from whatever memory exists.** Use
   plumbing so no working tree is touched and nothing can be lost:
   ```bash
   tree=$(git rev-parse HEAD:.claude/memory)          # or: build one from the old default dir
   commit=$(git commit-tree "$tree" -m "memory: the knowledge base becomes a branch")
   git branch memory "$commit"
   git merge-base HEAD memory && echo "NOT orphan — investigate" || echo "orphan ✓"
   ```
   If memory already exists in **two** places, reconcile with a **3-way merge**, not by
   picking a per-file winner — neither side is reliably ahead (one may carry migrations
   while the other carries newer content).

3. **Add its worktree and PUSH the branch before deleting anything.**
   ```bash
   git worktree add <repo>.d/memory memory
   git push -u origin memory        # durable before any removal
   ```

4. **Set `autoMemoryDirectory`** to that worktree's **absolute** path in
   `.claude/settings.local.json` (create the file or merge the key into it):
   ```json
   { "autoMemoryDirectory": "<abs>/<repo>.d/memory" }
   ```
   Derive it, don't hardcode. `settings.local.json` is gitignored — the setting is not
   committed; the memory **content** is what git tracks, on its own branch.

4b. **Remove `.claude/memory/` from the code branches, and ignore the path.** This is the
   step that makes forking impossible rather than merely discouraged — skip it and every
   branch keeps a tracked copy that diverges mechanically. ⚠️ **Anchor the ignore rule**
   (`/memory/`, not `memory/`): unanchored it also swallows a nested hub memory dir. The
   same unanchored-pattern trap once hid a real memory file behind a `checkpoint-*.md`
   rule for 10 days.

5. **Reload + verify the write path.** ⚠️ The setting is resolved **once, at session
   start** — a running session keeps the old path no matter what the file says, so the
   window MUST be reloaded before this proves anything. Then ask Claude to remember a
   throwaway marker and confirm `git -C <repo>.d/memory status --short` shows it (and that
   no `memory/` dir appears at the old `projects/<slug>/memory` default, nor in the code
   worktree). Revert the marker once confirmed.

6. **Wire the two hooks**, or the model leaks back: a SessionEnd hook that commits+pushes
   the memory worktree, and a SessionStart hook that shouts when `autoMemoryDirectory` is
   unset / points elsewhere / the worktree is dirty. Both should ASK GIT which worktree is
   on the `memory` branch (`git worktree list --porcelain`) rather than assume a path, and
   compare **resolved** paths so a symlinked spelling is not a false mismatch.

## Anti-patterns

- ❌ Symlinking `~/.claude/projects/<slug>/memory` → repo (the old hack:
   slug-fragile, reader-dependent, broke on non-main worktrees). `autoMemoryDirectory`
   replaces it — an absolute path is reader- and slug-independent.
- ❌ A relative or `${workspaceFolder}` path in `autoMemoryDirectory` — only
   absolute or `~/` are accepted.
- ❌ Putting `autoMemoryDirectory` in the committed `settings.json` — the path is
   host-specific; it belongs in per-checkout `settings.local.json`.
- ❌ Tracking `projects/<slug>/` — drags in churning transcripts.
- ❌ Pushing to a public repo without the step-1 visibility confirmation.
- ❌ **Memory on a code branch** — whether per-worktree or centralised on `main`. It forks
  mechanically, because every branch carries a tracked copy; the merge that is supposed to
  heal it does not happen. Measured: two trees, 7 facts wrongly recorded as dead.
- ❌ **Deleting the code branches' `.claude/memory/` before the `memory` branch is pushed.**
  Push first; the removal is only safe once the content is durable on the remote.
- ❌ **Trusting a mid-session setting change.** The path is resolved once at session start;
  verify at START, and for the current session write with an explicit absolute path instead.

## Applying to a new repository

Once-per-repo: run the checklist. In the external-worktree family only step 4 is automatic —
the `worktree` skill writes `autoMemoryDirectory` into each new checkout's
`settings.local.json` at creation, pointing at the repo's **`memory` worktree**. The branch,
its worktree and the two hooks are set up once per repo, not per checkout.
