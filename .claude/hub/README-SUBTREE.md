# claude-hub — shared git subtree

The hub owns this tree (the `claude-hub` repo's `.claude/`). Each consumer repo (e.g.
`rke2lab`) carries a **squashed subtree copy** at `<repo>/.claude/hub/`. The link is
**bidirectional**: a consumer can both receive hub changes (sync down) and originate hub
changes and publish them (sync up).

## Mental model (who owns what)

- **Source of truth** = the `claude-hub` repo's **origin** (`github.com/seedmatic/claude-hub`
  — moved from the `nxmatic` org; GitHub still redirects, so stale remotes keep working and
  hide the staleness until the redirect is withdrawn. Repointed everywhere 2026-09-29).
  Everything converges there.
- **`<repo>/.claude/hub/`** (the subtree, e.g. in `rke2lab.d/main`) is where you normally **edit**
  hub content during a work session. Edits here are published *up* to the hub origin via
  `subtree split` (Sync up).
- **`claude-hub.d/main`** is just *a checkout* of the hub repo. You **may** edit it directly — but
  then those edits **must be made available by an immediate `git push origin main`**. This is the
  mirror image of our case: a direct hub edit that is not pushed leaves every consumer pulling a
  split branch that is behind the real hub (exactly the drift that bites later). Treat "edited the
  hub directly" and "pushed the hub" as one atomic step.

So propagation runs **both** ways through the hub origin, and the unbreakable rule is *publish
immediately*:

- consumer edit → `subtree split`/push **up** → hub origin → other consumers pull **down**;
- direct hub edit → `push origin main` **immediately** → consumers pull **down**.

## ⚠️ Session discipline — sync at BOTH ends (do not skip)

Hub drift is silent and bites later. Bracket every work session that *might* touch hub content:

### At session start

1. Confirm `claude-hub.d/main` has **no unpushed commits** (`git -C <hub> status -sb`,
   `git -C <hub> rev-list --count origin/main..HEAD`). Unpushed hub commits = an error to fix
   (verify + `git -C <hub> push origin main`) **before** anything else — otherwise the split
   branch you pull from is behind the real hub.
2. **Sync down** into the consumer so you build on the current hub, not a stale squash
   (see *Sync down* below).

### At session end, before merging the consumer branch

1. **Sync up** any hub edits you made (see *Sync up* below), so the hub origin carries them.
2. Then finish/merge the consumer branch as usual.

Keep `--squash` consistent in **both** directions, always.

## Procedure → the `hub-subtree-sync` skill

The step-by-step — sync-down, sync-up, conflict resolution, and the
`could not rev-parse split hash` recovery — lives in the **`hub-subtree-sync` skill**
(it auto-loads when you sync the hub, or invoke `/hub-subtree-sync`). The invariants it
enforces:

- **Sync DOWN first**, at session start, *before* touching the subtree. Building on the
  current hub is what **AVOIDS the merge conflicts** a late sync-up otherwise hits (learned
  the hard way 2026-08-15 — skipping it forced a full down-then-up reconciliation).
- **Always `--ignore-joins`** on `subtree split` (split-only — `subtree pull` rejects it);
  **never `--rejoin`** (after a `--squash` pull it fails with "refusing to merge unrelated
  histories").
- **Never delete the split branches.** `subtree pull` runs an internal split that walks every
  `Squashed … from A..B` marker and needs each recorded base SHA reachable; deleting them GC's
  the bases → `could not rev-parse split hash` (the trap we hit, escaped by recovering `d29f295`
  from bioskop). Keep them + any `refs/recovered/*`.
- Keep **`--squash`** in both directions.
- The only **outward** step is `git -C <hub> push origin main`; everything else is local.

## Editing rules (so the flow stays clean)

- **Edit hub content only in a consumer subtree** (`<repo>/.claude/hub/…`), never directly in
  `claude-hub.d/main`. The standalone hub checkout is pull-only.
- **Project-specific memory** lives in the consumer's **own** `.claude/memory/` (e.g.
  `rke2lab.d/main/.claude/memory/`), referenced cross-repo as `[[rke2lab:name]]`. **Hub
  (cross-cutting) memory** lives in `.claude/hub/memory/`, referenced as `[[name]]` or
  `[[hub:name]]`. Put a fact in the layer that matches its scope; don't duplicate it across both.
- One worktree per conversation, under `<repo>.d/<namespace>/<branch>` (NOT `.claude/worktrees/`).
  See the hub memory note `external-worktree-operating-model-state` for the full operating model,
  including worktree cleanup at merge time.
