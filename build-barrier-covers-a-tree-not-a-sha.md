---
name: build-barrier-covers-a-tree-not-a-sha
description: "A green build covers a TREE, not a commit SHA. After a rebase/restack, compare `git rev-parse <sha>^{tree}` before relaunching — identical trees mean the barrier still holds and the rebuild is an empty ritual"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 62e4b01e-08e6-453e-9c0f-6cfcc67c6820
  modified: 2026-10-03T06:32:14.889Z
---

A rebase changes a commit's SHA without necessarily changing its **tree**. So after a restack, "the
barrier no longer covers the HEAD" is only true if the *content* moved.

**The measurement, one command:**

```bash
git rev-parse <built-sha>^{tree}   # e.g. 0bd2c07025db
git rev-parse HEAD^{tree}          # identical => the green build still holds
```

Measured in rke2lab on 2026-10-03: a stack level went through two restacks
(`d004fedd5` → `0ad0be63d`) while its tree stayed `0bd2c07025db`. I was about to relaunch a
~1 min 20 build; the user asked *"un rebuild ?"* and the comparison showed it was an **empty
ritual**. `git diff --stat <old> <new>` was empty too, which is the same check stated differently.

**Why:** the barrier's object is what gets compiled and tested — the tree. Parentage is metadata.
Rebuilding on an identical tree proves nothing new and costs the only thing that is scarce.

**How to apply:** after any `gh stack rebase` / `git rebase`, compare the trees **before** deciding
to rebuild. Rebuild when they differ — which is the case as soon as a lower level merged real
content underneath.

⚠️ The converse trap, same family: a build whose log does not record **which SHA it ran on** cannot
be attributed afterwards. On a worktree shared with another session the HEAD can advance *during*
the build. Write `echo "HEAD=$(git rev-parse --short HEAD)" > "$LOG"` as the log's first line — I
twice believed I had built an ancestor when the tree had already moved on.

Same family as [[measure-the-derived-value-not-the-assumed-one]]: the question is what the value is
*about*, not whether something changed. See [[gh-stack-operating-rules]]
[[rke2lab-canonical-maven-invocation]]
