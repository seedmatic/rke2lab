---
name: single-source-of-truth-before-logic
description: "feedback — before writing logic, find who already DEFINES the data you need and read it from there; if there's no API to reach it, provide one. Never transcribe/duplicate a computed fact."
metadata:
  type: feedback
---

**Before applying any logic inside the system, ask: do we already know the information we need, and
what DEFINES it (the single source of truth)? Read it from there. If no API exists to access it,
PROVIDE one** — don't transcribe, duplicate, or re-derive the fact by hand.

**Why:** a hand-copied value (version, package list, identifier) drifts from its owner the moment the
owner changes — and the failure surfaces late and opaque, far from the copy. Reading from the
definer is always in sync and fails fast. This extends the project's existing single-source-of-truth
discipline (ManifestDomainCatalog typed accessors, SystemdUnitCatalog, HostPathCatalog — all in
CLAUDE.md) from *identifiers* to *computed facts*.

**How to apply (worked example, R3 2026-06-19):** the SCR proof first hand-listed manifests-core's
imports as `systemPackages("…;version=2.22", …)` — a transcription of what bnd computes. User pushed:
the bnd-generated `Import-Package` MANIFEST header IS the source of truth; read it. There was no
testkit accessor, so we PROVIDED one — `FelixFrameworkExtension.exportImportsOf("manifests-core")`
reads the bundle's own manifest and mirrors its imports as system-bundle exports. 15 brittle lines →
one declarative call, never stale, missing artifact fails at `build()` by name (fail-fast) instead
of as a 5s resolver timeout.

**Smell to catch:** if you're about to type a literal that some build step / manifest / registry
already computes, stop — locate the owner and read it. Pairs with [[no-system-out-use-logger]] (use
the real facility, not an ad-hoc shortcut). See [[osgi-runtime-r3-consume-references-state]].
