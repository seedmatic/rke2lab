---
name: hub-subtree-sync
description: >-
  Use when syncing the shared claude-hub git subtree (the `.claude/hub/` tree) —
  publishing this repo's hub edits up to claude-hub, or pulling the hub's latest
  down into this repo. Triggers on "sync the hub", "push hub changes", "publish
  .claude/hub", "pull hub updates", a session that edited `.claude/hub/…` reaching
  its end, or the subtree error `could not rev-parse split hash <sha>`. Encodes the
  hardened procedure: `--ignore-joins` always, `--squash` both ways, NEVER
  `--rejoin`, the missing-base-object recovery, the conflict policy, and publishing
  through the hub's own `develop` → `main` merge-down.
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

## Notation

| Placeholder | What it names |
|---|---|
| `<repo>` | the **consumer** checkout you are syncing (where `.claude/hub/` lives) |
| `<bare>` | the hub's bare repository, when the host has one — the fast, local transfer point |
| `<hub-develop>` | the hub checkout on **`develop`** — where an up-sync lands and where hub edits are made |
| `<hub-main>` | the hub checkout on **`main`** — read-only reference; you split a down-sync from it and merge into it |

⛔ Do not collapse `<hub-develop>` and `<hub-main>` into one `<hub>`. One variable for two
branches is how a conflict-resolution instruction comes to name the wrong side — which
fails silently, by keeping the wrong content rather than erroring.

## Topology — DERIVE it, never recite it

Origin is `github.com/seedmatic/claude-hub` everywhere (moved from `nxmatic`; GitHub
redirects, so a stale remote keeps working and hides itself — all remotes repointed
2026-09-29).

⛔ **This section used to carry one stanza per host with hard-coded paths, and both had
rotted by 2026-10-07** — they named an org directory and a clone layout that no longer
existed, which is worse than saying nothing: it sends you to work on a topology that is
gone. Two commands answer everything, so run them instead of trusting any path written
below:

```bash
git -C <hub-main> rev-parse --git-common-dir    # ends in `.git` inside the checkout → plain clone
                                           # a path under a *-store → worktree of a bare
git remote get-url claude-hub              # a local path → two outward steps; an https url → three
```

**What the two answers decide**, and it is the only thing that varies:

| `claude-hub` remote points at | Outward steps |
|---|---|
| a **local bare** | **two** — `push origin develop` then `push origin main`; the split push stays local |
| **GitHub** | **three** — `git push claude-hub split/...` is already a publish, on top of those two |

⚠️ **Two, not one** — publishing goes through the hub's own `develop` → `main` merge-down
(see *Sync UP*), so **both** refs are pushed. Pushing only the merge-down would leave the
hub's `develop` silently diverging from its origin, which is the exact rot this skill
exists to prevent. Older revisions of this file claimed "one outward step"; that was true
when the up-sync landed straight on `main`, and the arithmetic moved with the flow.

So when a local bare exists, point the consumer's remote at it — local, fast, and it keeps
the split transfer out of the outward count:

```bash
git remote set-url claude-hub <bare>    # or `remote add` if absent; idempotent either way
```

ⓘ **Measured on this host 2026-10-07**, as an example of the derivation and not as a fact
to reuse: the hub checkout is a **worktree of a bare** (`--git-common-dir` →
`/Volumes/git-bare-store/seedmatic/claude-hub.git`), under `seedmatic/` rather than the
`nxmatic/` the old text named — the étage-0 bare/worktree migration moved it. The
consumer's `claude-hub` remote was repointed at that bare the same day, so a sync here is
two outward steps — `develop` then the merge-down on `main`.

Derive it, don't assume: `git -C <hub-main> rev-parse --git-common-dir` and
`git remote get-url claude-hub` answer both questions in one breath.

## Sync DOWN (claude-hub → this repo)

Split from **`<hub-main>`** — the published state is what a consumer should build on; the
hub's `develop` may carry work not yet merged down.

```bash
git -C <hub-main> subtree split --prefix=.claude --branch=split/claude-hub/dot-claude --ignore-joins
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
git -C <hub-develop> subtree pull --prefix=.claude <bare> split/<repo>/dot-claude --squash
```
⚠️ **Pull into the hub's `develop`, not into `main`.** The hub follows the same
convention as every other repo here — work lands on `develop`, and `main` only ever
receives a merge-down. So `<hub-develop>` is the target of the pull and `<hub-main>`
stays a read-only reference you merge into. (The hub had no local `develop` until
2026-10-07; create it as its own worktree, `git worktree add <path> develop`.)

On the hub side, "ours" = the hub branch you pulled into, "theirs" = your up-branch
(the reconciled superset from the down-sync) → resolve conflicts **`--theirs`**. Then
**verify the content is identical** before publishing:
```bash
diff -rq <hub-develop>/.claude <repo>/.claude/hub | grep -v 'README-SUBTREE\|\.git'   # expect no output
```
Then publish — **two refs, in this order** (confirm with the user first; it goes to GitHub):
```bash
git -C <hub-develop> push origin develop
git -C <hub-main> merge --no-ff develop && git -C <hub-main> push origin main
```
`--no-ff` and never `--squash` on the way down: reachability from `main` is what keeps
pinned revisions safe from `gc`.

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
  the pull in a throwaway worktree: `git -C <hub-main> worktree add --detach /tmp/hub-sync main`.

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

- Prefer editing hub content in a consumer subtree (`<repo>/.claude/hub/…`), so the
  edit travels with the session that needed it. If you edit the hub **directly**, do it
  in its **`develop`** worktree — never in the `main` checkout, which stays a read-only
  reference like every other repo's `main` — and publish **immediately**: "edited" and
  "published" are one step, and publishing means `develop` pushed *and* merged down.
- Hub (cross-cutting) memory lives in `.claude/hub/memory/` (`[[name]]` / `[[hub:name]]`);
  project-specific memory in the consumer's own `.claude/memory/` (`[[<repo>:name]]`).
