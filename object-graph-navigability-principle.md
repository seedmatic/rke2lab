---
name: object-graph-navigability-principle
description: "The single principle behind the user's design instincts — everything must be navigable in the object graph; a static is an orphan node (reachable by global name, unreachable by walking references). Explains the recurring herissement at static methods."
metadata:
  node_type: memory
  type: feedback
---

The user named (2026-06-25) the principle that DRIVES their implementation style and their recurring
*hérissement* at static methods: **everything must be navigable in the object graph.** From any
instance you should be able to reach everything that concerns it by walking references — up to the
factory that made it, down to what it composes, across to what it collaborates with.

**A static is an orphan node**: reachable by its global name, but UNREACHABLE by walking the graph.
It is a short-circuit that punches a hole in navigability. That is the why behind the
"prefer instances over helpers" rule — not style, but graph-completeness.

**Why this matters / how to apply** — this single invariant explains (and should predict) the user's
choices across the whole multiplexor/doctor design:
- DS over fragment — `@Reference List<T>` makes the contributor navigable from the consumer via the
  registry; a merged fragment is invisible.
- `.internal` sealed-but-navigable — hidden from OUTSIDE (OSGi export), but the in-bundle graph stays
  whole (Doctor → DoctorGraph → HealthSystem → …).
- path-addressing — even the host, which never holds a record, keeps a navigable ADDRESS (the YAML
  path) to the DAG node.
- factory-as-instance ([[multiplexor-two-models-design]] backlog) — a static factory is a hole; a
  factory-instance, referenced back from what it creates, makes the whole tree walk-up-able.
- records private, no host copy — one instance per node = one navigable identity, not two diverging
  copies.

A static factory (`X.of(...)`, `assemble(...)`) stays a LEGITIMATE exception (CLAUDE.md) — but even
it can be inverted to a factory-instance when graph-navigability is wanted. When reviewing or
implementing: a `static` that is not a pure util / factory / enum-conversion is a navigability hole —
flag it. See [[prefer-non-static-inner-keep-the-graph]] [[multiplexor-two-models-design]].
