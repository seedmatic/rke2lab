---
name: gson-flat-vs-staged-regression
description: "pulumi preview dies at Pulumi.run — NoClassDefFoundError com.google.gson.Gson: gson is staged bundle-only, never flat, though seed-master declares it compile. Fix: staging closure must treat a directly-declared third-party dep as a realm library (staged + flat)."
metadata:
  node_type: memory
  type: project
---

## Symptom (2026-07-14, worktree feature/cluster-seed-scenario)

`pulumi preview` boots the seed-master exec-jar and it dies IMMEDIATELY at `Main.java:40`
(`Pulumi.run`), BEFORE any OSGi/Felix boot, BEFORE any of our I6 code:

```
Exception in thread "main" java.lang.NoClassDefFoundError: com/google/gson/Gson
    at com.pulumi.deployment.internal.DeploymentImpl$Config.parseConfigSecretKeys
    ...
    at io.seedmatic.rke2lab.controlplane.Main.main(Main.java:40)
```

`com.pulumi` needs `com.google.gson.Gson` on the FLAT host classpath at startup. The exec-jar
(`exec/seed-master/target~nxmatic/…-exec.jar`, profile all-worlds,nxmatic) has gson ONLY as
`META-INF/bundles/gson.jar` (a staged OSGi bundle), NO flat `com/google/gson/*.class`.

## Root cause (diagnosed on the code, confirmed empirically)

The staging extension (`maven-embed-staging-ext/staging-extension`) decides flat-vs-staged:
- `StagingClosure.compute` STAGES gson (pulled transitively — the bbox client the bbox-edge
  bundles uses gson internally) → gson ∈ `stagedGas()`.
- `shadeExcludeGas() = stagedGas() − realmLibraryGas` → the shade `<excludes>` (injected by
  `StagingExecutionStrategy.injectShadeExcludes`) drop gson from the FLAT uber-jar.
- gson is NOT a realm library: `StagingClosure.isRealmLibrary` keeps a third-party bundle flat
  ONLY when a DOMAIN/type=library bundle IMPORTS its package. bbox-edge does NOT `Import-Package:
  com.google.gson` (verified: absent from its MANIFEST — the bbox CLIENT uses gson, the edge does
  not import it; the edge bnd even says "the host is purified off io.seedmatic.bbox.*, one realm").
- So no signal keeps gson flat, and it is excluded from flat despite seed-master declaring it a
  DIRECT compile dependency (exec/seed-master/pom.xml:211). **The direct compile dep is overridden
  by the extension's dynamic shade-exclude.**

That is the answer to "why doesn't the shaded jar pick up the compile dep?": the empty
`<artifactSet><excludes/>` in seed-master's shade is FILLED by the extension at build time with
`shadeExcludeGas()`, which includes gson.

## The fix (agreed with the user — CORRECT the extension, root cause)

A third-party bundle DIRECTLY DECLARED by the exec-module, when it is also staged, must be treated
as a REALM LIBRARY (staged AND kept flat) — the direct declaration IS the developer's "I need it
host-flat" intent, the parallel of a `type=library` self-declaring its dual nature. Plan:
1. `ResolvedBundle` gains a `directlyDeclared` flag.
2. `resolveBundles` (StagingExecutionStrategy) reads the Aether dependency GRAPH
   (`DependencyResolutionResult.getDependencyGraph()`), marks the root's direct children.
3. `StagingClosure.isRealmLibrary` returns true for a staged third-party bundle that is
   directlyDeclared (in addition to the existing domain-import signal), guarded by the same
   boot-stack-already-exports exclusion (don't add a second in-framework exporter).
Extend `StagingClosureTest` with the gson case.

## RESOLVED (2026-07-14, commit eeebd0efe) — it WAS a regression, restore not a new rule

Regression confirmed by `git log`: `1c37ea7ac` (bbox-edge, AFTER the green squash 50150e56) both
declared gson a DIRECT compile dep of seed-master AND introduced the bbox client that pulls gson into
`stagedGas()`. At 50150e56 gson was neither declared nor staged, so it rode into the flat shade
normally. Once staged, `shadeExcludeGas = staged − realmLibrary` began dropping it from flat; the
domain-import realm-library trigger never fired (bbox-edge does not Import-Package com.google.gson).

Fix (the 3 planned changes, all landed): `ResolvedBundle.directlyDeclared` (grafted from the Aether
resolution graph's root children in `StagingExecutionStrategy.resolveBundles` via `asDirectlyDeclared()`
— a jar carries no directness signal); `StagingClosure.isRealmLibrary` treats a directly-declared
third-party bundle as a realm library, guarded by the SAME boot-stack exclusion as the import path
(the guard now disqualifies a bundle whose export a boot-stack bundle already serves in-framework,
regardless of which signal fired — slf4j must never get a second in-framework exporter). 2 new
StagingClosureTest cases (gson dual; slf4j boot-stack-guard trumps direct declaration).

Verified: `unzip -l …-exec.jar` shows BOTH `com/google/gson/Gson.class` (flat) AND
`META-INF/bundles/gson.jar` (staged); shade excludes dropped 32 (was including gson). Preview now
passes Main.java:40 (gson found) and reaches Main.java:43, dying only on a config-missing diagnostic
(incus.configDir/image.sharedFolder/worktree.dir absent from the dev-preview-cluster-seed stack) —
that is stack config, NOT packaging.

Not our I6 chantier — an upstream packaging blocker found while trying to run a preview to validate
I6a-c. I6 code (I2, I6a/b/c) is not even reached (die is at Pulumi.run, pre-boot). See
[[m2-snapshot-masking-is-critical]] [[maven-build-cache-and-staging-verify]].
