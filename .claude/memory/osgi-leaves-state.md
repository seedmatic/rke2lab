---
name: osgi-leaves-state
description: "Step 4 of the new module layout (worktree refactor/osgi-leaves off design/target-module-layout @1941fb5e): make osgi/ a HOMOGENEOUS LIST OF DOMAINS. MOVE the remaining top-level pure leaves in (osgi/systemd/{systemd-contract,cdk8s-systemd} + osgi/netplan/) AND — scope widened on user's call — regroup the ALREADY-moved flat modules by domain too (osgi/unitrepo/{unitrepo-core,unitrepo-handler-api}; manifests structured as a domain too). bnd-ify the new leaves as library bundles. After this, no pure module sits flat under osgi/ — every one lives in its domain dir. Born 2026-06-18."
metadata:
  node_type: memory
  type: project
---

## Scope (user-decided 2026-06-18 — WIDENED to uniform domain grouping)

**The rule (user-refined): a domain-DIR when there ARE (or will be) MULTIPLE modules; otherwise the
module stays FLAT under `osgi/`.** Not dogmatic ("everything is a domain") — pragmatic. Coherence
serves coder comfort, it does not fight it: an enveloping dir around a lone module is needless friction.
Criterion = module count (present or known-coming), NOT "tidiness for its own sake". One sub-branch:

1. **MOVE the new pure leaves** `git mv`:
   - `systemd-contract` + `cdk8s-systemd` → `osgi/systemd/` — **domain dir: 2 modules** (contract +
     CDK8s integration).
   - `netplan` → `osgi/netplan` **FLAT (no domain dir)** — ONE module, no split foreseen → an
     `osgi/netplan/` wrapper would be needless overhead. Simpler at use. (User: "netplan en a pas
     besoin donc toplevel c'est plus simple à l'usage.")
2. **Regroup the existing flat modules that ARE multi-module domains** (the inconsistency the user caught):
   - `osgi/unitrepo-core` + `osgi/unitrepo-handler-api` → `osgi/unitrepo/{…}` — **domain dir: 2 modules
     now, `unitrepo-pulumi` (the ACL) coming** → justified. Both share pkg `io.seedmatic.rke2lab.unitrepo.*`.
   - `osgi/manifests` → `osgi/manifests/{…}` **domain dir — NOT because tidiness, but because a
     re-découpe is ALREADY KNOWN to be coming** (model/synthesis split, [[step2-decomposition-state]]).
     That foreknowledge is what justifies the envelope now (vs netplan which has none). (User:
     "manifests c'était naturel, on sait déjà qu'il va être re-découpé.")
3. **bnd-ify the new leaves as library bundles** (parent=`osgi/bundle-parent`, bare bnd ref,
   `Export-Package`), same pattern as `osgi/manifests` ([[osgi-space-bundles-state]]). Already-bnd
   modules (unitrepo, manifests) keep their bnd setup; only their DIR moves. Each keeps its own
   `<description>` ([[every-module-has-a-description]]).
4. Update aggregator wiring: root `<modules>` drops the moved-in leaves; `osgi/pom.xml` lists the
   domain dirs (`unitrepo`, `systemd`, `manifests`) + the flat modules (`netplan`) + `osgi-bench`.

Target osgi/ shape: `osgi/{bundle-parent, unitrepo/, systemd/, manifests/, netplan, osgi-bench/}` —
multi-module domains get a dir; the lone `netplan` stays flat. Mixed by design, by the count rule.

## Why grouped-by-domain, not by-consumer (settled, do not re-litigate)

Verified in code on design HEAD:
- All 3 are PURE (0 com.pulumi/io.grpc). `cdk8s-systemd` exports `io.seedmatic.rke2lab.cdk8s.systemd`
  (Constructs extending `software.constructs.Construct`); `osgi/manifests` IMPORTS + instantiates them
  → `cdk8s-systemd` is a **required library bundle, NOT a fragment** (a fragment would name manifests as
  Fragment-Host with no host-side import — the opposite of the actual edge direction). The prod fragment
  exemplar `SystemdDropIn` is a CLASS inside cdk8s-systemd (a systemd-runtime drop-in) — a different
  layer, do not conflate bundle-fragment with that.
- Consumers are NOT exclusive, so prefixing by consumer was REJECTED (`manifests-cdk8s-systemd` lies):
  `netplan` ← osgi/manifests AND seed-master; `systemd-contract` ← seed-master only (NOT manifests!);
  `cdk8s-systemd` ← osgi/manifests only. Encoding a consumer in a name breaks as soon as a 2nd appears
  ([[step2-decomposition-state]] single-source-of-truth). Group by DOMAIN: the FS says role via domain.

## ★ CRITICAL side-effect of moving netplan: the rke2lab flake.nix references its PATH

`netplan` is the source of truth for the network blueprint that **nix-darwin-home consumes** (verified
2026-06-18). rke2lab's own `flake.nix` builds netplan's shade `-exec` jar and runs it:
- line ~111 `mvn -f netplan/pom.xml` (builds the module by PATH);
- line ~117 `cp netplan/target/netplan-*-exec.jar $out/share/java/rke2lab-netplan.jar`;
- line ~133 `java -jar …rke2lab-netplan.jar yamlExport > blueprint.yaml` → parsed to
  `networkBlueprintData` → exposed as `lib.networkBlueprint` → **nix-darwin-home reads it** (flake.nix
  line ~10 says so; the `catalog.netplan` attrs in nix-darwin-home are the consuming end).
So the netplan shade `-exec` jar is LOAD-BEARING, not a forgotten inheritance (the user's suspicion was
checked and disproved — `NetplanCli` has 3 real commands: synthesis / yamlExport / jsonSchemaExport).
**When `git mv netplan → osgi/netplan`, UPDATE flake.nix lines ~111 and ~117 to the new path**
(`osgi/netplan/pom.xml`, `osgi/netplan/target/…`). Build-verify includes a `nix` eval of
`lib.networkBlueprint` if feasible, else at least confirm the flake paths match the new location. Same
caution for any other module the flake builds by path (e.g. seedMasterJar at line ~205).

## OPEN questions to settle IN the branch (verify, don't assume)

- Does `osgi/systemd/` get an aggregator pom (packaging=pom listing the 2, like `osgi/osgi-bench/`) or
  is it just a directory grouping with both modules listed directly in `osgi/pom.xml`? Decide on the
  spot — an aggregator is cleaner if the group grows; a bare dir is lighter for 2 modules.
- `netplan` alone under `osgi/netplan/` — a single module in its own domain dir is fine (room to grow),
  but confirm it doesn't need an aggregator (it doesn't — it's one module).
- bnd `Export-Package`: read each module's public packages. `cdk8s-systemd` exports
  `io.seedmatic.rke2lab.cdk8s.systemd`; `systemd-contract` and `netplan` — read their src to find the
  public package(s). Keep glue private if any.
- relativePath depth: moving down one or two levels changes `<parent>` relativePath to build-parent /
  bundle-parent — fix per moved pom. Deps resolve by GAV (proven steps 2-3), so only aggregator wiring
  + moved poms' parent/name change.

## Method (held to — same as steps 1-3)

- MOVE by `git mv`. Sequence: commit the MOVE first (build green) THEN bnd-ify (build-verify each).
- Build-verify FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` ([[build-verification-gotchas]]; partial -pl gives false failures). Count surefire.
- Bundle pattern: parent=bundle-parent + bare bnd ref → emits Bundle-SymbolicName; verify on the jar
  manifest. Each bundle's own `<description>` → its own Bundle-Description (per
  [[every-module-has-a-description]]; bundle-parent's description must not leak).
- GNU sed in flox rejects `-i ''` — use `perl -0pi -e` for in-place pom edits.

## ★ CLOSE / MERGE — hand off, do NOT merge from THIS session

Per the CORRECTED [[merge-from-target-worktree]]: THIS session lives in the sub-branch worktree, so it
must NOT run the squash-merge or the teardown — **a session cannot saw off the worktree it sits on**
(the host-space dérive). When the work is done: finish + commit EVERYTHING (code AND `.claude/memory/`)
+ build-green + verify (`git log design/target-module-layout..HEAD`; `git status` clean), THEN STOP and
HAND OFF to the design-owning session for `git merge --squash` + commit + worktree/branch/workspace
teardown. Act broadly without asking otherwise — only the runtime boundary is gated
([[standing-autonomy-except-runtime-config]]).

## State / next
- Branch `refactor/osgi-leaves`, base `design/target-module-layout` (HEAD 1941fb5e). sops re-smudged.
- ★ DONE (2026-06-18). Two commits on the sub-branch, ready for the design session's squash-merge:
  1. `step 4 MOVE — organize osgi/ by domain` (c38951aa): git mv only, build green.
  2. bnd-ify the new leaves (this commit). Both build-verified FULL `-Posgi` skipCache
     skipTests=false → 26 modules SUCCESS, **15 surefire tests, 0 skipped, 0 failures**.
- RESOLVED open questions:
  - Domain dirs (`systemd`, `unitrepo`, `manifests`) GET an **aggregator pom** (packaging=pom,
    parent=build-parent) — uniform with `osgi/osgi-bench`, scales as the group grows.
  - `netplan` stays **FLAT** at `osgi/netplan` (parent now bundle-parent, depth `../bundle-parent`).
  - `manifests` envelope: inner module keeps artifactId `manifests` at `osgi/manifests/manifests/`;
    the wrapper aggregator has artifactId `manifests-domain` (artifactIds must stay unique) but
    `<name>osgi/manifests`. Inner pom parent path → `../../bundle-parent`.
  - Export-Package: `systemd-contract`→`…systemdcontract.api`; `cdk8s-systemd`→`…cdk8s.systemd`
    (emits `Import-Package: software.constructs` → CONFIRMS required-bundle-not-fragment);
    `netplan`→`…netplan` + `…netplan.api`. Each `bnd.bnd` = SymbolicName + Export + `-noimportjava`,
    ZERO Java annotations (library-bundle role, exemplar = osgi/manifests). User asked post-move
    whether annotations were needed; answer NO for these three — annotations are the source of truth
    only for capability PROVIDE/REQUIRE (handler-api, osgi-bench config/schema), not plain exporters.
  - relativePath: systemd modules 2-deep → `../../bundle-parent`; netplan 1-deep → `../bundle-parent`.
- ★ NEXT for the design session: `git merge --squash refactor/osgi-leaves` onto
  design/target-module-layout (run FROM the design worktree, NOT here), commit with the status line in
  the merge message, then teardown this worktree+branch+workspace.
- After this, the pure/host sort is COMPLETE. NEXT layout steps (own branches): the `unitrepo-pulumi`
  ACL/mediation seam (host-side, faces both spaces); the bdd-core/bdd-ledger split inside seed-master
  (oracle-validated). See [[step2-decomposition-state]] [[hub:docrepo-dag-state]] for the roster.

See [[host-space-state]] (step 3, the decision to group by domain + the fragment-vs-bundle finding),
[[osgi-space-bundles-state]] (the bundle pattern + GAV-not-relativePath), [[layout-skeleton-state]],
[[merge-from-target-worktree]], [[standing-autonomy-except-runtime-config]], [[build-verification-gotchas]].
