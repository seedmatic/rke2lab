---
name: dual-path-inline-until-r5
description: "DESIGN STEER (user, 2026-06-20, R4): the host/osgi dual-path forks (if running-as-bundle … else flat-classpath/ServiceLoader) stay INLINE if/else, NOT a Strategy abstraction — because the flat/ServiceLoader half is TEMPORARY and retires in R5. A Strategy(host,osgi) injected by context would be right only if both branches were permanent. Applies to every dual-path site: FloxRuntimeAssets scan, IncusResourceBootstrap.singleSpiProvider, EntryGatePolicyEnforcer.enforceManifestUpdateGate."
metadata:
  node_type: memory
  type: feedback
---

The user, reviewing the R4 flox OSGi-aware scan fix (`FloxRuntimeAssets` gained an
`if (FrameworkUtil.getBundle(...) != null) { bundle scan } else { jar/filesystem scan }`):

> "je te laisse faire, parce que l'autre branche (java class loader) va disparaître. mais sinon
> il faudrait avoir une strategy (host, osgi) et l'injecter en fonction du contexte."

**The steer:** keep the host/osgi fork as an INLINE `if/else`, do NOT lift it into a
Strategy(host, osgi) injected by context. **Why:** the flat-classloader / `ServiceLoader` branch is
TEMPORARY — it is the dual-path that R5 retires once every entrypoint boots under Felix. Building a
strategy abstraction around a branch that is scheduled to be deleted is speculative (cf. CLAUDE.md
"avoid speculative abstractions"). A Strategy would be the right shape ONLY if both branches were
permanent; they are not.

**How to apply:** keep the inline fork ONLY where a flat caller still exists; cut to the bundle path the
moment none does (the no-fallback discipline, [[migration-branch-no-fallback]]).

★ STATE AT END OF R4 (corrected 2026-06-20 — the original list below was overtaken by `d60b0ee8`): the
HOST seam sites are NO LONGER dual-path. `pulumi preview` proved the deployed exec-jar always embeds the
bundles, so the flat half there was DEAD, not deferred — both were cut to a single registry path:

- `IncusResourceBootstrap.singleSpiProvider` — now mono-path: `osgiRuntime.awaitService(type, 5000)`,
  fail-fast if null. NO ServiceLoader branch.
- `EntryGatePolicyEnforcer.enforceManifestUpdateGate` — same, registry only.
So the ONLY surviving `ServiceLoader.load` on our ports is in the **CLIs** (`manifests-cli` Main,
`netplan-cli` SynthesisCommand) — they have not been migrated to boot Felix yet (backlogged domino, see
[[osgi-runtime-r4-resume-state]]); plus 8 orphaned `META-INF/services` files kept alive only for them.
That CLI fork is the last real dual-path; it disappears when the CLIs boot under Felix (then delete the
SPI files). `OsgiRuntime`'s own `ServiceLoader.load(FrameworkFactory.class)` is NOT one of these — it is
the standard OSGi framework-discovery mechanism, permanent.

**The further cut the user made (2026-06-20):** `FloxRuntimeAssets` resource scan does NOT keep a
dual-path at all — it is now OSGi-only (`Bundle.findEntries`, no jar/filesystem fallback). Carto
proved it has ZERO framework-less callers: its only entry is `DefaultFloxRuntimeAssetService`, a
`@Component` always activated by SCR inside a bundle, and tests boot it under Felix too ("on sait déjà
faire run les tests en OSGi"). So the flat branch was DEAD NOW, not merely temporary — deleted
immediately (`copyTreeFromJar`/`copyTreeFromFilesystem`/`addEnvsFromJar`/`addEnvsFromFilesystem` gone).
The rule: keep the inline fork only where a flat caller still EXISTS; where none does, cut to OSGi-only
straight away rather than carrying dead code to R5.

See [[osgi-runtime-r4-boot-seam-state]] (R4) [[osgi-runtime-r3-consume-references-state]] (the dual-path
origin) [[osgi-runtime-migration-state]] (R5 retires ServiceLoader).
