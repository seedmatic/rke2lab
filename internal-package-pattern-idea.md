---
name: internal-package-pattern-idea
description: "IDEA / future chantier (user's call, 2026-06-22): recognise a framework-internal package by its NAME (*.internal) so 'internal, never system-exported' is distinguished from a seam structurally, not implicitly. Surfaced closing the DS-API gate, where org.osgi.service.component.runtime is felix.scr-internal yet had to be told apart from a host-shared seam by hand-reasoning."
metadata:
  node_type: memory
  type: project
---

**IDEA, raised by the user 2026-06-22 while closing the DS-API gate ([[osgi-staging-extension-chantier]]).**

The gate forced a distinction we currently make IMPLICITLY: a package is either
- a **seam** — shared host↔OSGi, system-exported (the `-port` packages), or
- **framework-internal** — wired bundle-to-bundle, NEVER system-exported (the DS-API runtime
  `org.osgi.service.component.runtime`, which the flat host must NOT see).

Today nothing in a package's NAME says which it is; the runtime infers it (a package lands in
`system.packages.extra` only if a model/edge bundle imports it). That works but is implicit —
the old `SCR_API_PACKAGES` shim mis-classified the DS-API runtime as host-shared exactly because
the distinction wasn't structural.

**The user's proposal:** adopt the `.internal` package-name convention (Eclipse/OSGi idiom) so
"internal, never exported" is legible from the name — `…​.internal` is never a seam, never
system-exported, no special-case reasoning. A consumer (the seam guard, the staging closure, a
future export-derivation) reads the name instead of inferring intent.

**Why it is its own chantier, not a quick add:** "on va avoir du travail" — applying `.internal`
means auditing/renaming packages across osgi/ modules and re-checking every export-derivation and
seam-guard against the new convention. Decided to NOTE it here and tackle separately.

Related: [[osgi-system-export-resolution-only]] (the invariant this would make structural),
[[system-space-world-universe-glossary]] (where the seam/internal vocabulary lives),
[[osgi-staging-extension-chantier]] (where it surfaced).
