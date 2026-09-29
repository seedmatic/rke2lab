---
name: prefer-non-static-inner-keep-the-graph
description: "Design preference (user, 2026-06-20): default to NON-static inner classes to keep the object graph navigable to the owner; use static ONLY to deliberately cut the graph"
metadata:
  node_type: memory
  type: feedback
---

**Default to non-static inner classes; reach for `static` only to deliberately cut the object graph.**

A non-static inner class keeps an implicit reference to its owner (`Outer.this`), so the object graph
stays navigable from the inner instance back to its owner. The user prefers NOT to cut that link by
default: keeping it makes the code more flexible and ready to support evolutions (the inner can reach
the owner's state later without a refactor). `static` is the exception — used only when you want to
EXPLICITLY sever the graph (true isolation, no need for the owner).

**Why:** this is the inverse of the reflex "make it static if it doesn't use the outer instance". The
user optimizes for future flexibility over present minimalism: an un-cut graph leaves the door open.

**How to apply:**
- New inner class → leave it non-static unless you have a reason to isolate it.
- Reading code: a `static` nested class signals an intentional cut — respect it, don't "un-static".
- Tension with CLAUDE.md "local classes vs inner" (which says static-when-no-outer-access): the user's
  preference wins for inner classes that belong to the owner's graph. Top-level/instance-passing is
  still right when a unit only needs its explicit params (e.g. `IncusIdentityMaterialAssembler` takes
  only `BootstrapConfig` — top-level + passed-in, testable in isolation, NOT in any owner's graph).

This composes with the instance-passing discipline: pass instances explicitly AND keep the graph
navigable — both serve dependency clarity + evolvability.

See [[synth-context-channel-rule]] [[null-arg-is-a-rule-violation]].
