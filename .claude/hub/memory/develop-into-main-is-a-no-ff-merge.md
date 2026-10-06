---
name: develop-into-main-is-a-no-ff-merge
description: "Fleet convention (user, 2026-10-06): develop integrates into main by a NO-FF merge — one merge commit holding both branches, so main reads as integrations via --first-parent while the detail stays reachable. NOT a squash: squash makes main stop being an ancestor, and a later `git rebase main` on develop DROPS the branch's commits."
metadata:
  node_type: memory
  type: project
---

Decided 2026-10-06, after weighing the three shapes. The user's words: « le merge, un commit qui
tient les deux branches, c'est exactement ce que je veux finalement. chacun a sa place, et a chacun
de choisir quel chemin il prend en fonction de ce qu'il cherche. »

**Both directions, 2026-10-06:** « le merge, c'est quand on re-descend vers main, mais par contre
dans l'autre sens on rebase. »

```sh
# down — integrate
git switch main && git merge --no-ff develop
# up — re-sync (the merge commit lives only on main, so develop is behind by it)
git switch develop && git rebase main
```

| Direction | Gesture | What it preserves |
| --- | --- | --- |
| down (`develop` → `main`) | `merge --no-ff` | both readings: `--first-parent` = integrations, full log = detail |
| up (`main` → `develop`) | `rebase` | develop stays linear — no merge commits accumulate there |

The up-rebase is SAFE for the same reason the squash was not: everything already integrated stays
reachable from `main` through the merge commit's second parent, so the rebase only rewrites commits
not yet integrated.

★ It also handles a `main` that carries a commit `develop` lacks (measured on `flox-nri-plugin`: a
`VERSION` bump made directly on main). Order: rebase develop onto main FIRST — that picks the commit
up — then merge down.

⚠️ Cost, given several machines and parallel sessions on `develop`: the rebase rewrites the SHAs of
not-yet-integrated commits, so it needs `push --force-with-lease` and every other copy needs
`git reset --hard origin/develop`. Do it while alone on the branch.

| Shape | History reachable from main | Reading main | main stays an ancestor |
| --- | --- | --- | --- |
| fast-forward | yes | every commit | ✅ |
| **merge --no-ff** (chosen) | **yes**, via the 2nd parent | `--first-parent` = one commit per integration; full log = everything | ✅ |
| squash | **no** | one commit | ❌ |

**Why not squash — the trap, stated so it is not re-derived.** Reachability from `main` IS what
protects commits from `gc`. After a squash `main` is no longer an ancestor of `develop`, which makes
`git rebase main` on develop tempting; that rebase replays commits whose changes are already in the
squash, so each becomes empty, git DROPS them, and they end up referenced by no branch — reflog only
(90 days), then collected. "We keep develop for the full history" holds only until that rebase.

If a squash is ever wanted anyway: tag develop's tip first (a tag is a reference, so `gc` spares it),
and never rebase develop onto main.

⚠️ I first recommended fast-forward and was wrong about one premise the user corrected: a classic
merge does NOT keep history "out of main" — the merge commit makes it reachable. What `--no-ff`
actually buys is a clean FIRST-PARENT line, not absence.

**How to apply:** for every repo of the fleet, and keep `develop` as the working branch. See
[[branch-namespaces]].
