---
name: maven-build-cache-force-rebuild-flag
description: "To force a real recompile in rke2lab's Maven build-cache setup, use -Dmaven.build.cache.skipCache=true (rebuilds AND refreshes the cache entry), NOT -Dmaven.build.cache.enabled=false (bypasses without updating → cache left stale)"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 718e49f8-20da-47bc-97da-58c26b893740
  modified: 2026-09-07T18:25:18.007Z
---

rke2lab uses the Maven build-cache extension: a plain `./mvnw … package` can print `BUILD SUCCESS` while **restoring modules from cache** (`Found cached build, restoring … by checksum`) — i.e. WITHOUT recompiling your edits. So `BUILD SUCCESS` alone does not prove your source changes compiled.

To force a genuine recompile, use **`-Dmaven.build.cache.skipCache=true`**, NOT `-Dmaven.build.cache.enabled=false`.

**Why:** `enabled=false` bypasses the cache entirely — it does not read it, but also does **not update** it. The old (pre-edit) artifacts stay in the cache, so the next cache-hit build (or the user's warm-up) restores the STALE build. `skipCache=true` forces the rebuild **and rewrites** the cache entry (`Saved Build to local file …`), leaving the cache consistent with the new sources.

**Why:** guidance from the user (2026-09-07), after I used `enabled=false` to validate a fix and left the cache holding the pre-fix build.

**How to apply:** `flox activate -- ./mvnw -pl :<mod> -am package -DskipTests -Pclaude,all-worlds -Dmaven.build.cache.skipCache=true`.

Caveat observed the same day: the build-cache checksum for manifests-contract/manifests-core did NOT change after real source edits (same key `371af4ec…`/`f5057bff…` before and after) — so a normal cache-hit build silently restores stale artifacts for these modules. `skipCache=true` overwrites the entry, masking it. The coarse/stale checksum keying is a pre-existing build-config quirk — do NOT chase it. See [[workload-grow-foundations-resume]].
