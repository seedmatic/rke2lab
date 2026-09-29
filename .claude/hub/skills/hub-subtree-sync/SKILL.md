---
name: hub-subtree-sync
description: >-
  Use when syncing the shared claude-hub git subtree (the `.claude/hub/` tree) —
  publishing this repo's hub edits up to claude-hub, or pulling the hub's latest
  down into this repo. Triggers on "sync the hub", "push hub changes", "publish
  .claude/hub", "pull hub updates", a session that edited `.claude/hub/…` reaching
  its end, or the subtree error `could not rev-parse split hash <sha>`. Encodes the
  hardened procedure: `--ignore-joins` always, `--squash` both ways, NEVER
  `--rejoin`, the missing-base-object recovery, the conflict policy, and the single
  outward `push origin main`.
---

# Sync the claude-hub subtree

`.claude/hub/` is a squashed git subtree of the `claude-hub` repo, shared across
consumer repos (rke2lab, …). The link is **bidirectional**. `README-SUBTREE.md`
(in `.claude/hub/`) is the authoritative reference for the mental model + editing
rules; this skill is the activatable procedure.

## When to run

Bracket every session that touches hub content:
- **At session start, BEFORE touching the subtree — sync DOWN first.** This is the
  main lesson: building on the current hub is what **avoids merge conflicts**. Skip it
  and your later sync-up collides with everything the hub advanced in the meantime.
- **At session end, before merging the consumer branch** — sync **up** so the hub
  origin carries your edits.

If you skipped the start-sync and only discover it at the end, you MUST still do the
down first (a naive up would regress the hub — dropping notes it has and you lack) —
and you'll pay for it in conflicts you could have avoided by syncing down up front.

## The hard rules

- **Always `--ignore-joins` on `subtree split`** (it recomputes from scratch, so the
  split side never depends on old base SHAs). **`--ignore-joins` is split-only** — it
  is rejected by `subtree pull`.
- **NEVER `--rejoin`.** After a `--squash` pull it fails with `refusing to merge
  unrelated histories`.
- Keep `--squash` in **both** directions.
- **NEVER delete the split branches** (see *Cleanup*) — the pull needs their base SHAs.

## Topology — DIFFERS PER HOST, measure it

Origin is `github.com/seedmatic/claude-hub` everywhere (moved from `nxmatic`; GitHub
redirects, so a stale remote keeps working and hides itself — all remotes repointed
2026-09-29).

**nikopol** — bare + worktrees, so only the final push is outward:

- Hub checkout `<hub>` = `/Volumes/git-worktree-store/nxmatic/claude-hub.d/main`,
  a worktree of the bare `/Volumes/git-bare-store/nxmatic/claude-hub.git`
  (directory names keep `nxmatic/`; that is a path, not the URL).
- Add a `claude-hub` remote in this repo pointing at that **bare** (local, fast):
  `git remote add claude-hub /Volumes/git-bare-store/nxmatic/claude-hub.git`
  (idempotent — skip if present). Only the final `push origin main` touches GitHub.

**This host (measured 2026-09-29)** — no bare, so TWO steps are outward:

- Hub checkout `<hub>` = `/private/var/lib/git/nxmatic/claude-hub.d/main`, a plain clone
  (`git rev-parse --git-common-dir` → `.git`, not a worktree of a bare).
- The `claude-hub` remote here points at **GitHub**, not a local bare — so
  `git push claude-hub split/...` is ALREADY an outward publish, on top of the final
  `push origin main`. The skill's "only one outward step" holds only with a local bare.

Derive it, don't assume: `git -C <hub> rev-parse --git-common-dir` and
`git remote get-url claude-hub` answer both questions in one breath.

## Sync DOWN (claude-hub → this repo)

```bash
git -C <hub> subtree split --prefix=.claude --branch=split/claude-hub/dot-claude --ignore-joins
git fetch claude-hub split/claude-hub/dot-claude
git subtree pull --prefix=.claude/hub claude-hub split/claude-hub/dot-claude --squash
```
Resolve conflicts: **hub-canonical** for files you did NOT touch this session;
**keep your edits** for files you deliberately changed (your session work supersedes
the hub's older version on the same topic). Commit the merge.

## Sync UP (this repo → claude-hub)

```bash
git subtree split --prefix=.claude/hub --branch=split/<repo>/dot-claude --ignore-joins
git push claude-hub split/<repo>/dot-claude
git -C <hub> subtree pull --prefix=.claude <bare> split/<repo>/dot-claude --squash
```
On the hub side, "ours" = hub main, "theirs" = your up-branch (the reconciled
superset from the down-sync) → resolve conflicts **`--theirs`**. Then **verify the
content is identical** before publishing:
```bash
diff -rq <hub>/.claude <repo>/.claude/hub | grep -v 'README-SUBTREE\|\.git'   # expect no output
```
Then the **one outward step** (confirm with the user first — it publishes to GitHub):
```bash
git -C <hub> push origin main
```

## If `could not rev-parse split hash <sha>`

A `--squash` commit references a base subtree SHA that was GC'd (its ephemeral
split branch was deleted on a repo synced before the `--ignore-joins` rule).
`--ignore-joins` avoids needing it; if a plain op already failed, recover the object
from another clone that still has it (e.g. `bioskop`):
```bash
# on the clone that has it (git show <sha> works there):
git branch recover-<sha> <sha>              # an unadvertised/dangling SHA can't be fetched directly
# from here, into the bare:
git -C <bare> fetch ssh://<user>@<host>/<path-to-that-clone> refs/heads/recover-<sha>:refs/recovered/<sha>
```
Keep the `refs/recovered/<sha>` ref so GC can't drop it again. (Done 2026-08-15:
recovered `d29f295` from bioskop to unblock a hub sync-up.)

★ **Better: push the recovered object to ORIGIN, not into a local ref.** A local
`refs/recovered/*` fixes one machine; the next clone hits the same wall, because the hub's
own history references a base its own remote cannot serve ("not our ref"). Publishing the
object makes every clone whole:

```bash
git -C <clone-that-has-it> push origin "<sha>:refs/heads/recovered/<short>"
```

Done 2026-09-29 for `ca44dc2` (referenced by `ad35be0 Squashed '.claude/' changes from
37de71f..ca44dc2`): absent from every repo on this host, found in nikopol's bare, pushed to
origin as `refs/heads/recovered/ca44dc2`. The failing `subtree pull` then succeeded in
place. ⚠️ **Never delete that branch** — it is load-bearing for every future pull.

⚠️ Two traps met on the way, both mine:
- `"$sha:refs/heads/..."` in **zsh** parses `:r` as the *root* history modifier and eats the
  `r`, producing `...efs/heads/...` and `src refspec does not match any`. Brace it:
  `"${sha}:refs/heads/..."`.
- `subtree pull` refuses outright with `working tree has modifications. Cannot add.` — an
  UNTRACKED file is enough. If the hub checkout is dirty and you must not disturb it, run
  the pull in a throwaway worktree: `git -C <hub> worktree add --detach /tmp/hub-sync main`.

## Cleanup — do NOT delete the split branches

**Keep every split branch** (up and down) plus any `refs/recovered/*`. `subtree pull`
runs an internal split of the local history to find its merge base, walking each
`Squashed … from A..B` marker — it needs every recorded base SHA (A/B) reachable.
Deleting the "ephemeral" split branch after a transfer (as older docs said) GC's
those bases, so the next pull dies with `could not rev-parse split hash <sha>`.
That is exactly the trap we hit — recovering `d29f295` from bioskop to escape it.
The branches are tiny; keep them. (`--ignore-joins` rescues the *split* side, but the
*pull* can't take it, so the bases must persist.)

## Editing rules (so the flow stays clean)

- Edit hub content only in a consumer subtree (`<repo>/.claude/hub/…`), never
  directly in `claude-hub.d/main` (pull-only). If you must edit the hub directly,
  `git -C <hub> push origin main` immediately — "edited" and "pushed" are one step.
- Hub (cross-cutting) memory lives in `.claude/hub/memory/` (`[[name]]` / `[[hub:name]]`);
  project-specific memory in the consumer's own `.claude/memory/` (`[[<repo>:name]]`).
