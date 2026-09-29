---
name: osgi-runtime-decomposition-debt
description: "RESOLVED (2026-06-23, boot-decomposition increment of osgi-boot-alignment) — the debt the user flagged 2026-06-20 (R4 WI-C): OsgiRuntime touffu, boot() fusing pax-logging + felix.scr + model bundles + embedded-vs-classpath install + Import-Package mirror + start-levels + manifest parsing. RESOLUTION: OsgiRuntime DELETED, decomposed into pure BootPlanner→BootPlan (decision: exports, closure, seam-guard, start-levels) + effectful FrameworkLauncher→BootedFramework (act) + BootPipeline grammar; the file-vs-embedded branch became the BundleLocation sealed type + BundleIndex factory (nature is data, not control flow). Exactly the export-deriver / bundle-source-abstraction / start-level-installer split the user hinted at. See [[boot-decomposition-state]]."
metadata:
  node_type: memory
  type: project
---

User (2026-06-20, reviewing WI-C boot()): "c'est bien mieux, on comprend mieux le role de chacun, on
verra plus tard si on peut encore mieux decouper en delegant a des composants, parce que la osgi
runtime commence vraiment a etre touffu."

**State:** `OsgiRuntime` (osgi/runtime) is one class doing a lot — `boot()` orchestrates:
- pax-logging install (slf4j single-exporter guard) + StaticLogbackContext config
- felix.scr install + ServiceComponentRuntime await
- model-bundle install
- TWO bundle sources: classpath-located `Path` jars (reactor/test) AND embedded
  `META-INF/bundles/<name>.jar` streamed into Felix's cache (deployed exec-jar)
- Import-Package → system.packages.extra mirror (manifest read from a file OR a jar stream)
- OSGi start-levels (logging=1, felix runtime=2, model bundles=3) + STARTED latch

**RESOLVED (2026-06-23)** — the boot-decomposition increment delivered exactly the split hinted at:
- bundle-source abstraction (file vs embedded) → `BundleLocation` sealed type (`OnClasspath`/`Staged`)
  + `BundleIndex.ofClasspath()`/`ofStagedBundles()` factories; boot() no longer branches on the two —
  the only switch left is the install mechanism (two arms of one sealed switch in `FrameworkLauncher`).
- export-deriver (the mirror logic) → `BootPlanner.deriveSystemExports` + the seam guard, pure, in
  `boot-discovery`, returning an inspectable `BootPlan`.
- start-level installer → folded into `FrameworkLauncher` (the effectful act), driven by the plan.
`OsgiRuntime` + `SeedRuntime` are DELETED (guard grep green). Spec:
`docs/architecture/osgi/osgi-boot-decomposition-spec.adoc`.

See [[boot-decomposition-state]] [[osgi-runtime-r4-resume-state]] [[r4-resolver-service-ification]].
