---
name: exec-aggregator-state
description: "Step 5 of the new module layout (worktree refactor/exec-aggregator off design/target-module-layout @c3cfb58c): create a new TOP-LEVEL aggregator exec/ grouping the executables BY NATURE (north-adapters / driving entrypoints). Extract netplan-cli OUT of osgi/netplan (which becomes pure netplan-core), and move seed-master from root into exec/. Born 2026-06-18."
metadata:
  node_type: memory
  type: project
---

## The idea (matured over a long design exploration 2026-06-18)

`exec/` is a THIRD top-level space beside `osgi/` and `host/`, grouping modules by their NATURE:
**executable entrypoints that DRIVE the app** (= the spec's north-adapters / driving side). An exec-jar
is not "host" in the pulumi/infra sense (netplan-cli touches zero Pulumi), so grouping it under host/
would conflate the purity axis with the nature axis. exec/ is a new, deliberate axis — DOCUMENT it in
the target-layout spec when this lands (purity osgi/host + direction north/south + **nature exec**).

Both executables move in (user chose "les deux"):
- **netplan-cli** — EXTRACTED from osgi/netplan (see split below).
- **seed-master** — MOVED from repo root → `exec/seed-master` (it finally leaves "root faute de mieux";
  it is the composition-root north-adapter, deeper than netplan-cli but same nature).

Mental model confirmed with the user: netplan-cli is built the SAME way as seed-master (a plain `main()`
driving a pure core via its `api/` port — NOT an OSGi application; no framework; the northbound is
already the core's `api/` package, nothing new to model).

## The netplan core/cli split (verified on design HEAD c3cfb58c)

`osgi/netplan` has 11 classes in pkg `io.seedmatic.rke2lab.netplan` + subpkg `api/`. Split:
- **STAYS in osgi/netplan (becomes pure netplan-CORE, a library bundle):** `Cidr`, `MacAddress`,
  `ClusterNetworkBlueprint`, `DefaultNetplanSynthesisService`, and the `api/` package
  (`NetplanSynthesisService`/`Request`/`Result`, `Net2PlanEndpoint`) = the NORTH port it offers.
  Remove the shade plugin, exec-maven-plugin, mainClass, and the CLI-only deps (logback-classic,
  jackson-dataformat-yaml) from its pom — the core bundle goes back to pure Export-Package, no exec.
- **MOVES to exec/netplan-cli:** `NetplanCli`, `SynthesisCommand`, `BlueprintExportCommand` + the shade
  config (classifier `exec`, mainClass `io.seedmatic.rke2lab.netplan.NetplanCli`, logback.xml transformer)
  + the CLI-only deps. netplan-cli DEPENDS ON osgi/netplan (the core) and produces the `-exec` jar.
  NB: the CLI classes are in pkg `io.seedmatic.rke2lab.netplan` today — decide whether they move to a
  `…netplan.cli` package (cleaner export boundary for the core) or keep the package and just relocate
  the module (bnd Export-Package on the core already only exports what it should — verify no split-pkg).

## ★ CRITICAL: flake.nix builds these by PATH — update when they move

flake.nix (already fixed once in step 4 for the osgi/ paths) builds the exec jars by path:
- line ~111/117: `osgi/netplan/pom.xml` + `osgi/netplan/target/netplan-*-exec.jar` → **the exec jar now
  comes from `exec/netplan-cli`** (the core no longer shades). Update `-f` to `exec/netplan-cli/pom.xml`
  and the cp to `exec/netplan-cli/target/netplan-cli-*-exec.jar` (mind the artifactId/finalName change).
- line ~257: `seed-master/target/seed-master-*-exec.jar` → **`exec/seed-master/target/…`**.
- Also check: **Pulumi.yaml / Pulumi.dev.yaml** (repo root) may reference seed-master's main or runtime
  path; root scripts; any `mvn -pl :seed-master` (selector by artifactId is fine, path refs are not).
A real `nix build` of `lib.networkBlueprint` is RUNTIME (user runs it); at least confirm the flake paths
match the new locations and the Maven build is green.

## Method (held to — same as steps 1-4)

- MOVE by `git mv` for whole files; for the netplan split, `git mv` the 3 CLI classes into the new
  module's tree. Sequence: create exec/ + exec/netplan-cli + move seed-master, commit MOVE first (build
  green), THEN strip the core's exec config + wire netplan-cli's shade, build-verify each.
- Deps resolve by GAV (proven steps 2-4) → moving seed-master touches only aggregator wiring + its
  parent relativePath + its `<name>`; selectors `-pl :seed-master` keep working.
- exec/ aggregator pom (packaging=pom) listing seed-master + netplan-cli, wired into root `<modules>`
  (which DROPS seed-master). Does exec/ need a parent (exec-parent) for shared shade/exec config? NO —
  shade/exec stay GLOBAL in build-parent (proven step 3: netplan+manifests shade too; pluginMgmt is
  global, only per-module `<configuration>` is local). exec/ is an aggregator, not a parent. Do NOT
  speculatively add exec-parent (same discipline as not adding seed-parent — [[host-space-state]]).
- Build-verify FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` ([[build-verification-gotchas]]); count surefire. Each module keeps its own
  `<description>` ([[every-module-has-a-description]]).
- GNU sed in flox rejects `-i ''` — use `perl -0pi -e` for in-place pom edits.

## ★ CLOSE / MERGE — hand off, do NOT merge from THIS session

Per [[merge-from-target-worktree]]: THIS session lives in the sub-branch worktree → it must NOT run the
squash-merge or teardown (**a session cannot saw off the worktree it sits on** — the host-space dérive).
When done: finish + commit EVERYTHING (code AND `.claude/memory/`) + build-green + verify
(`git log design/target-module-layout..HEAD`; `git status` clean), THEN STOP and HAND OFF to the
design-owning session for merge+teardown. Act broadly without asking otherwise — only the runtime
boundary is gated ([[standing-autonomy-except-runtime-config]]); when you DO ask, lead with a
(Recommended) option ([[ask-question-mark-recommended]]).

## ★ DONE — built green, committed, AWAITING HAND-OFF (do not merge from here)

Branch `refactor/exec-aggregator`, base `design/target-module-layout` (was HEAD c3cfb58c). 3 commits,
each FULL `-Posgi` green (29 modules, 15 tests 0-skipped), `git status` clean:
- **5a `b293aa2d`** — created `exec/` aggregator (wired into root `<modules>`, which DROPS seed-master);
  split netplan core/cli (3 CLI classes → `exec/netplan-cli`, pkg `…netplan.cli`; core reverts to pure
  `ipaddress`-only library bundle, no shade); MOVED `seed-master` root → `exec/seed-master`. flake.nix
  netplan exec-jar now built via `-pl :netplan-cli -am` (sibling core dep needs the reactor, not `-f`),
  plus the seed-master path; Pulumi.yaml binary + .vscode/launch.json cwd. **Also fixed a PRE-EXISTING
  bug:**
  the ServiceLoader registration file was misnamed `io.seedmatic.rk2lab.*` (missing the `e`) → `synthesis`
  never resolved a provider; only `yamlExport` (the only path the flake runs) was exercised, so latent.
- **5b `1905fe3b`** — user grew scope: extract `exec/manifests-cli` too (symmetric). The manifests bundle
  wore THREE hats (library bundle + `manifests-d` asset zip + exec jar) — extract ONLY the exec hat:
  `Main.java` (→ pkg `…manifests.cli`) + logback → `exec/manifests-cli`; core drops `logback-classic` +
  dead logback excludes + shade/exec but KEEPS the assembly plugin (the `manifests-d` zip seed-master
  unpacks stays in core) + bnd. Only `logback` was CLI-only — jackson-yaml/slf4j stay in core (used by
  `ManifestYaml`, 5 slf4j users). flake.nix manifests.jar ← `exec/manifests-cli/target`.
- **5c `dbe29c05`** — user asked to share the shade config. The 3 exec jars had byte-identical shade
  FLAGS + `<filters>` block → lifted into **build-parent**'s managed shade execution (NOT an exec-parent:
  build-parent already sits above all 3, shade stays global per [[host-space-state]]; an exec-parent
  couldn't even have covered manifests before 5b). Each module keeps ONLY its `<transformers>` (mainClass
  differs; 2 CLIs add a logback IncludeResourceTransformer; seed-master adds `Add-Opens`). KEY: parent
  config (flags+filters) and child config (transformers) are DISJOINT elements → union cleanly, no Maven
  list-merge ambiguity, via the implicit `default` execution id. Verified per jar (Main-Class correct;
  Add-Opens seed-master only; logback in 2 CLIs only; ZERO sig/module-info = inherited filter applied).

## ★ NEXT for the hand-off (design) session

- Run the squash-merge of `refactor/exec-aggregator` → `design/target-module-layout` + teardown, per
  [[merge-from-target-worktree]] (THIS sub-branch session must not saw off its own worktree).
- DOCUMENT the new axis in the target-layout spec: purity (osgi/host) + direction (north/south) +
  **nature (exec)**. exec/ = driving entrypoints / north-adapters, grouped by nature not purity.
- **PRE-EXISTING flake regression to fix (out of step-5 scope, flag it):** `flake.nix:37`
  `flox-runtime.url = "path:./manifests/src/…/runtime/flox"` is STALE since step 2 moved manifests to
  `osgi/manifests/manifests/` — that input path no longer resolves from repo root.
- After this: pure/host/exec sort complete. LATER (own branches): `unitrepo-pulumi` ACL/mediation seam
  (host-side, 3rd member of osgi/unitrepo's domain); bdd-core/bdd-ledger split inside seed-master
  (oracle-validated). See [[step2-decomposition-state]] [[hub:docrepo-dag-state]] for the roster.

See [[osgi-leaves-state]] (step 4, the domain-grouping rule + netplan landed flat), [[host-space-state]]
(seed-master-stays-at-root WAS a step-3 call, now reversed: it gets a real home in exec/),
[[osgi-space-bundles-state]] (bundle pattern + GAV-not-relativePath), [[merge-from-target-worktree]],
[[standing-autonomy-except-runtime-config]], [[ask-question-mark-recommended]], [[build-verification-gotchas]].
