---
name: build-verification-gotchas
description: "rke2lab build has THREE stacked traps that make a green build lie about tests — .mvn forces -DskipTests, the maven build-cache replays past results, and the VSCode Java language server writes ECJ \"poison\" .class files. The reliable verify command + how to read the lies."
metadata: 
  node_type: memory
  type: project
  originSessionId: f1adbe47-6bb1-4ab9-9dba-db741efcee3a
---

To ACTUALLY run tests in rke2lab (not get a green build that silently skipped or replayed them), three traps stack — all three must be defeated at once:

1. **`.mvn/maven.config` forces `-DskipTests`** (bare, = true). A plain build prints `BUILD SUCCESS` with NO `Tests run:` line → tests were skipped, not passed. Override needs `-DskipTests=false` (CLAUDE.md says this) — but that alone is not enough because of trap 2.
2. **`maven-build-cache` (`.mvn/maven-build-cache-config.xml`) replays cached results** — a module shows SUCCESS without recompiling or re-running tests, so a stale/poison state is never re-evaluated. Defeat with `-Dmaven.build.cache.skipCache=true`.
3. **The VSCode Java language server (ECJ) writes "poison" `.class` files** into `target/` in the background — bytecode that throws `java.lang.Error: Unresolved compilation problems: … cannot be resolved` at RUNTIME (javac never emits this; only Eclipse's compiler does). Surefire then executes the poison class and the build FAILS on perfectly valid source. This is especially likely right after a `git mv`/rebase moves files under the IDE's feet. `javap -c -p <Test>.class | grep "Unresolved compilation"` confirms a poison class. Defeat with `clean` (forces javac to recompile from source, discarding the poison).

**THE RELIABLE VERIFY COMMAND** (all three defeated, always through flox for JDK 25):
```
flox activate -- ./mvnw clean package -pl :seed-master -am -Dmaven.build.cache.skipCache=true -DskipTests=false
```
`package` not `install` (never install to ~/.m2 — siblings resolve via the reactor, hence `-am`). Confirm tests truly ran by counting surefire reports (`find <module>/target -path '*surefire-reports*' -name '*.xml'`) and reading `Tests run: N, Failures: 0, Errors: 0` — never trust `BUILD SUCCESS` alone here.

**⚠ NEVER run `mvn install` (HARD RULE, CLAUDE.md) — and SUBAGENTS violate this if not told.** On 2026-06-10 a dispatched implementer ran the `install` goal, polluting `~/.m2/repository` with stale `*-SNAPSHOT` jars (parent/netplan/cdk8s-systemd/manifests/…) — exactly the stale-sibling-resolution hazard the reactor exists to avoid. The `.mvn/maven-build-cache-config.xml` even lists `maven-install-plugin:install` among cached goals, so a cached install re-pollutes silently. **When dispatching ANY build-running subagent, the prompt MUST explicitly say: use `-pl :seed-master -am package`, NEVER `install`; if a sibling won't resolve, REPORT it — do not "fix" it with install.** Cleanup if it happens: `find ~/.m2/repository/io/seedmatic/rke2lab -type d -name '*-SNAPSHOT' -prune -exec rm -rf {} +` (KEEP the released `bom/1.0.0`), then rebuild via the reactor and confirm `find … -name '*-SNAPSHOT*' | wc -l` == 0 (a clean `-am package` re-installs NOTHING — verified).

**Reading the lies:** `BUILD SUCCESS` + no `Tests run:` = skipped (trap 1/2). `BUILD FAILURE` with `Unresolved compilation problems` on code that clearly exists = IDE poison .class (trap 3), NOT a real break — `clean` fixes it. A build run WITHOUT `clean` after the IDE has touched `target/` is untrustworthy. Bedrock-compaction aside, this is the main reason a "green" claim here can be wrong — see [[runbook-doctor-state]].

**★ `-am` AND `skipCache` go TOGETHER — and a subagent that drops `-am` mis-diagnoses (2026-06-14, problem-oriented-provenance exec).** Two stacked points the user clarified:
1. **`-am` (also-make) is non-negotiable in the reactor**: a bare `-pl :seed-master` (no `-am`) resolves siblings from stale jars and fails with `package io.seedmatic.rke2lab.manifests does not exist` / `cannot find symbol: ManifestDomainCatalog` — even though the `manifests` module itself shows `BUILD SUCCESS`. This LOOKS like a broken worktree but is just the missing `-am`. A dispatched implementer hit exactly this, reported BLOCKED ("worktree cannot compile"), and was WRONG — the controller re-ran with `-am` and it compiled. **Lesson: when a subagent reports a build BLOCK, the controller re-verifies with the canonical `-am` + `skipCache` command before believing it; and reviewers should READ CODE, not re-run the build (a build they run risks a false BLOCKED).**
2. **`skipCache=true` is needed WITH `-am` whenever a depended-on module may have changed**, because the build-cache CHECKSUMS are unreliable — user's hypothesis: the **shadowed (shade-plugin) jar** produces an artifact whose hash doesn't track its real content, so the cache can replay a stale compilation of a dependent module. So the pairing is: `-am` puts the reactor in the loop, `skipCache=true` forces real recompilation instead of a checksum-keyed replay. (Proof the pairing is sound: with `skipCache=true` a genuine RED surfaced — a non-migrated test fixture — not a cache-masked false green.) **The cache/shadowed-jar "drift" itself is a SEPARATE problem the user has NOT yet investigated — parked for later, do not rabbit-hole on it; just always pass both flags.**
