---
name: boot-decomposition-state
description: "SHIPPED to design/pre-integration (squash merge 2026-06-23) — the boot-decomposition increment of osgi-boot-alignment. The durable knowledge lives in the versioned spec docs/architecture/osgi/osgi-boot-decomposition-spec.adoc; this note keeps only the ONE open follow-up: the ★ deferred proof obligation (a seam package resolves to the SAME Class across host-JCL and a bundle's BCL; a model package on a BCL is invisible to the host JCL)."
metadata:
  node_type: memory
  type: project
---

## Shipped — see the spec for the what/why

The boot decomposition (pure `BootPlanner`→`BootPlan` told apart from effectful
`FrameworkLauncher`→`BootedFramework`, the `BootPipeline` fluent grammar, `SeedRuntime`+`OsgiRuntime`
deleted, shared `boot-logging`, the pax/JCL `optional`-scope invariant, and the unified
`BundleIndex.closeOverImports` frame) is **SHIPPED to design/pre-integration (squash merge,
2026-06-23)**. The blow-by-blow (commits, dead ends, the JclExclusionParticipant spike) is git
history; the durable model is the versioned spec:

- **`docs/architecture/osgi/osgi-boot-decomposition-spec.adoc`** — the four phase-roles, the
  "runtime nature is data not control flow" table, the anti-patterns retired. The reference.
- Invariant: [[bundle-on-jcl-is-wrong-classpath]] (a BCL bundle on the flat JCL is a wrong classpath).
- Resolved the debt: [[osgi-runtime-decomposition-debt]].

## ★ OPEN — deferred proof obligation (the only thing left in this chantier's scope)

NOT yet proven: the CONSEQUENCE of the JCL/BCL split the planner decides per package. On a booted
framework, assert a `type=seam` (`-port`) package resolves to the SAME `Class` from the host JCL and
from a bundle's BCL (one shared copy), while a `type=model` package on a BCL is NOT visible to the
host JCL (two worlds, no leak). The real teeth behind the seam law
([[osgi-system-export-resolution-only]]); the pure plan tests cannot reach it — now that
`BootedFramework` exists it is schedulable. JCL/BCL defined in [[system-space-world-universe-glossary]].

See [[osgi-boot-alignment-state]] (parent) [[osgi-staging-extension-chantier]].
