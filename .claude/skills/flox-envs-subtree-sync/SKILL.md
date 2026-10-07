---
name: flox-envs-subtree-sync
description: >-
  Use when syncing the flox environment tree vendored at `.flox-envs.d/` — pulling
  fleet's latest envs down into this repo, or republishing fleet's `flox-subtree`
  branch after editing the envs. Triggers on "sync the flox envs", "update
  .flox-envs.d", "pull the flox subtree", "republish flox-subtree", a seat that
  fails to activate with "manifest and lockfile are out of sync", or an env that is
  missing from `.flox-envs.d` but present in fleet. Encodes the direction of truth
  (fleet's working copy is the source, the branch is a derived split), the
  mandatory re-lock after every pull, and the two-step verification.
---

# Sync the vendored flox env tree

`.flox-envs.d/` is a squashed git subtree of **fleet**'s `flox-subtree` branch. The
seat's `[include]` entries point into it (`./.flox-envs.d/<env>`), so the envs travel
with the checkout instead of being reached through a path outside it.

## The direction of truth — get this right or you destroy envs

```
fleet  develop:flox            ← the LIVING copy. Edits land here.
         │  git subtree split  of origin/develop:flox
         ▼
fleet  origin/flox-subtree  ← a DERIVED split. Never edit it directly.
         │  git subtree pull
         ▼
rke2lab .flox-envs.d/       ← the vendored copy. Never edit it directly.
```

Edits go in `fleet/flox/<env>/`, **never** in `.flox-envs.d/` and **never** on the
`flox-subtree` branch. A local edit under `.flox-envs.d/` is lost at the next pull.

⛔ **Why this is stated so loudly:** a subtree without a named procedure freezes. Edits pile up
in the working copy, the derived branch stops moving, and pulling it back over the living copy
becomes **destructive** — measured once, it would have dropped 8 envs, 3 of them included by this
seat. That is what this file is for.

## Pull the envs down (the common case)

Run from this repo's worktree root:

```bash
git remote add fleet https://github.com/seedmatic/fleet.git   # once per clone
git subtree pull --prefix=.flox-envs.d fleet flox-subtree --squash
nix run .#lock-flox-envs                                      # MANDATORY, see below
```

### The re-lock is not optional

fleet ignores `*/.flox/env/manifest.lock`, so **the locks never travel**. A lock is
what makes an env includable, so freshly vendored envs make the seat refuse to
activate:

```
✘ ERROR: failed to fetch environment './.flox-envs.d/asciidoc':
  cannot include environment since its manifest and lockfile are out of sync
```

`nix run .#lock-flox-envs` walks the seat's include graph (and each env's own
`../<env>` includes, dependency-first) and locks whatever has no lock. The locks stay
gitignored on purpose: fleet publishes manifests, each checkout realises its own lock.
The error above, after a pull, always means this step was skipped.

## Republish the branch (only after editing envs in fleet)

Publish what has **landed** on `develop` — never what a checkout happens to hold. Split the
remote trunk, not `HEAD`, so a worktree carrying unmerged commits cannot publish unreviewed work:

```bash
git -C <fleet> fetch --prune origin       # a FULL fetch: with explicit refspecs, origin/develop was left stale
split="$(git -C <fleet> subtree split --prefix=flox origin/develop)"
lease="$(git -C <fleet> rev-parse --verify --quiet origin/flox-subtree || true)"
git -C <fleet> push origin "$split:refs/heads/flox-subtree" --force-with-lease="flox-subtree:$lease"
```

Read the push's whole output — never through `tail`: a first attempt failed showing only « failed to
push some refs », its cause cut away, and passed when re-run with the same split and lease.

The push is forced because the branch is derived: a re-split rewrites it and cannot
fast-forward. The lease is what still refuses to clobber a push that arrived since the fetch.

⚠️ Then check that the result is what you meant — the first republication (2026-10-02) had
**disjoint** histories, the branch having frozen and been reconciled by an unrelated local
commit, so the force was total. The check is **tree-hash equality**:

```bash
git -C <fleet> fetch --prune origin
[ "$(git -C <fleet> rev-parse origin/flox-subtree^{tree})" \
= "$(git -C <fleet> rev-parse origin/develop:flox)" ] && echo identical
```

★ It used to be `diff <(git ls-tree --name-only …) <(…)`, and that was **worthless for
content**: without `-r` it compares only top-level entries — measured 2026-10-07, **40 names
against 81 real files** — and names alone never show a changed file. It passed while a failed
publish had left the branch without the edit it was supposed to carry. A tree hash proves
structure and content in one comparison.

Then pull it down here.

## Verify — and know why the obvious check is worthless alone

```bash
flox activate -- true          # must exit 0
flox include upgrade           # must report "No included environments have changes"
```

★ **The first proves nothing by itself.** ndh passes `flox activate -- true` today with
**four dead absolute include paths**, because its lock froze the composition and masks
them entirely. The gate is the second command: a pending include is the only signal
that a path stopped resolving. Freezing is a property of *includes*, not of paths —
nothing ever re-resolves one until you ask.

## Measured gotchas, so nobody re-derives them

- **`common` is not an include.** Thirteen envs mention it, and it looks alarming, but
  in `jdk`/`pulumi`/`shell` it is a **commented-out** template line
  (`#     { dir = "../common" }`) and in `asciidoc` it is the `[profile] common` script
  key — a different thing entirely. There is no `../common` to resolve. Don't chase it.
- **Six envs ship without a lock** (`darwin`, `dns-tools`, `docker`, `editor`,
  `gnumake`, `spacelift`) because nobody has activated them. No consumer seat includes
  them, so `lock-flox-envs` never touches them.
- **fleet's internal includes are all relative** (`../keyhole`, `../xdg`, …), which is
  what makes the tree relocatable at all. If one ever becomes absolute, vendoring
  breaks and the lock will hide it — see the verification note above.
- **A fresh worktree has no lock for `flake-registry`**, and `flox activate -- true` still
  passes — only `flox include upgrade` fails, on « manifest and lockfile are out of sync ». Run
  `nix run .#lock-flox-envs` first. The gate is `flox include upgrade`, not activation.
- **`flox activate -d <env>` used as a check MIGRATES the manifest** to the newer schema
  (`schema-version = "1.14.0"`, double quotes). Undo it with `git checkout -- <env>` before
  committing, or the check becomes a change.
- **Never check `flox-subtree` out inside `fleet.d/develop`.** It carries the envs at its
  ROOT, with no `flox/` directory, so the checkout would remove `fleet/flox` and break
  every consumer reaching it. Use a dedicated worktree.
