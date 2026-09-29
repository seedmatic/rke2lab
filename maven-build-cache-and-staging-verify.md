---
name: maven-build-cache-and-staging-verify
description: How to invoke the build correctly in rke2lab — skip (not disable) the build cache, use -Pall-worlds, and verify staging-extension changes with a full package (never generate-resources).
metadata:
  type: feedback
---

The canonical build invocation for rke2lab tests (user-confirmed):

```
./mvnw -Dtest=<Test> package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false 2>&1 | tee /tmp/maven.log
```

**Why:** the repo uses the `maven-build-cache-extension`. To get a fresh run, **SKIP** the cache with
`-Dmaven.build.cache.skipCache=true` — do NOT disable it with `-Dmaven.build.cache.enabled=false`.
- `skipCache=true` bypasses the restore-from-cache lookup for this run while keeping the extension
  ACTIVE (still computes checksums and saves). This is what you want for a clean verify.
- `enabled=false` turns the whole mechanism off; tried it once and it caused sibling jars to not
  materialize for dependency resolution (the staging extension reads dep jars from disk). Wrong tool.

**`-Pall-worlds`** is part of the canonical command — use it for test runs.

**Verifying `maven-embed-staging-ext` (the OSGi bundle-staging core extension) changes:** the
extension is a `MojoExecutionListener` that fills the empty `<artifactItems/>` of the
`stage-embedded-bundles` dependency:copy execution by reading each runtime dependency's JAR **from
disk**. Therefore:
- It needs sibling modules **packaged** (jars on disk). A `./mvnw -pl :seed-master -am generate-resources`
  run does NOT package siblings → `artifactItems` stays empty → `Either artifact or artifactItems is
  required`. That is a verification-harness artifact, NOT a real bug.
- Verify with a **full reactor `package`** from the repo root (every upstream module packaged before
  the exec module), scoping the test with `-Dtest=...`.

**Cache input-tracking gap fixed (2026-07-05).** The cache config `.mvn/maven-build-cache-config.xml`
did NOT track `bnd.bnd` as a build input: its `<glob>` listed `*.java,*.yaml,…` but not `*.bnd`, and
`bnd.bnd` lives at the **module root**, outside the `src/` `<include>`. Consequence: a **bnd-only edit**
(changing `Export-Package`/`Import-Package` — the OSGi manifest) did not invalidate the cache, so the
build replayed a **stale bundle manifest**. Symptom seen: an added `!org.checkerframework…` import
exclusion was ignored, the in-container bundle kept a mandatory checkerframework import and failed to
resolve. Fix: added `*.bnd` to the glob AND an explicit `<include>bnd.bnd</include>`. If a bnd-only
change seems to have no effect, suspect the cache first (or build with `skipCache=true` to confirm).

Related: [[cdk8s-carrier-flat-jar-pattern]] (the staging closure this extension computes).
