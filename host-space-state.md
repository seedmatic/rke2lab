---
name: host-space-state
description: "Step 3 of the new module layout — SHIPPED to design/target-module-layout 2026-06-18 (worktree refactor/host-space, off @2a18e644). MOVED the 3 host-only modules under host/ (incus->host/pulumi-generated-sdks/incus, pulumi-automation-ext[-testkit]) + re-parented to host-parent + pulled their depMgmt out of build-parent into host-parent. seed-master STAYS at root (spans both spaces). shade/exec STAY global in build-parent (netplan+manifests use them too — original cartography was wrong). Each parent regains its role; build-parent purely global."
metadata:
  node_type: memory
  type: project
---

## Scope (user-decided 2026-06-18)

User: "enrichir la zone host, ça nous permettra de clean le build-parent. chacun son rôle, et on y
verra plus clair dans tous ces sub-folders." So this step fills `host/` AND cleans `build-parent`.

## What SHIPPED (2 commits, both build-verified green; squash-merged to design 2026-06-18)

1. **MOVED 3 modules** (NOT 4) under `host/`, re-parented build-parent→host-parent, aggregators updated:
   - `sdks/incus` → `host/pulumi-generated-sdks/incus` (flattened; dir name carries the Pulumi-codegen
     provenance — versioned-as-source, not authored here). groupId stays `io.seedmatic.rke2lab.sdks`.
   - `pulumi-automation-ext`, `pulumi-automation-ext-testkit` → `host/` (flat).
   - Tagged the 4 pulumi-automation-ext tests `@Tag("host")` (were untagged → an angle-blind spot:
     `-Phost`/`-Posgi` use groups=host|osgi, so untagged ∉ both; default run still ran them). 20 green.
2. **PULLED host depMgmt** (incus / pulumi-automation-ext[-testkit]) build-parent → host-parent.
   seed-master, at root, can't inherit host-parent → pinned its testkit test dep explicitly (incus +
   pulumi-automation-ext were already explicit). build-parent now purely global.

## TWO corrections to the original cartography (verified in code — the spec was WRONG)

- **seed-master STAYS at root, is NOT a host module.** User's call: it is the composition runtime —
  loads the host space via the JVM class loader AND boots the OSGi space under Felix → straddles both.
  parent=build-parent. (So flake.nix:257 / Pulumi.yaml:10 `seed-master/target/…` paths are UNCHANGED.)
- **shade + exec STAY global in build-parent.** Original memory claimed "only seed-master uses shade/
  exec" — FALSE: `netplan` and `osgi/manifests` also shade exec jars (NetplanCli / manifests.Main).
  So the pluginManagement (version pins) is genuinely global → stays. The per-module shade/exec
  `<configuration>` already lives in each consumer, never was in build-parent. Nothing plugin-side moved.
- `host/pulumi-generated-sdks/` flatten decided (not `host/sdks/`): one occupant, groupId is the SDK
  marker, aligns relativePath. incus shade <configuration> = N/A (incus has none; only seed-master's
  shade survived its OWN pom, never moved).

## DEBT left for a later step (runtime config, NOT touched here)

- `flake.nix:258` still points `manifests/target/…` but manifests moved under `osgi/` in step 2 —
  stale path inherited from step 2, out of this slice's scope (nix = runtime-gated config).

## Method (held to — same as steps 1-2)

- MOVE by `git mv`. Sequence in-branch: commit the MOVE first (build green) THEN pull config into
  host-parent (build-verify). Inter-module deps resolve by GAV (proven step 2), so the move touches
  only aggregator wiring + each moved pom's parent relativePath/name.
- Build-verify FULL `flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true
  -DskipTests=false` ([[build-verification-gotchas]]; partial -pl gives false failures). Count surefire.
- Every module keeps/gets its own `<description>` ([[every-module-has-a-description]]).
- GNU sed in flox rejects `-i ''` — use `perl -0pi -e` for in-place pom edits.

## Merge (per [[merge-from-target-worktree]] — CORRECTED note)

Squash merge into `design/target-module-layout`, run FROM this design-owning worktree — **Claude does
the merge + teardown itself, no permission ask** (the constraint is LOCATION = target worktree, not
who executes). Integration-status line INSIDE the merge commit, hash-free; commit `.claude/memory/`
before teardown. Act broadly without asking per [[standing-autonomy-except-runtime-config]] — only the
runtime boundary (pulumi/kubectl/incus/nix + harness settings) is gated.

## State / next
- **DONE + SHIPPED 2026-06-18**: branch `refactor/host-space` (off @2a18e644), 2 green commits,
  squash-merged into `design/target-module-layout`. Worktree torn down.
- NEXT layout step (own branch, DECIDED 2026-06-18) = **the pure leaves into the OSGi space, GROUPED
  BY TECHNICAL DOMAIN** — `osgi/systemd/{systemd-contract, cdk8s-systemd}` + `osgi/netplan/`. Verified
  facts that settled it:
  - All three are PURE (0 pulumi/grpc). cdk8s-systemd exports `io.seedmatic.rke2lab.cdk8s.systemd`
    (Constructs: SystemdChart/Service/Unit/Target/DropIn extends `software.constructs.Construct`);
    `osgi/manifests` IMPORTS + instantiates them → it is a **required library bundle**, NOT a fragment.
    (Fragment would be cdk8s-systemd naming manifests as Fragment-Host with no host-side import — the
    opposite of what the code shows. The prod fragment exemplar `SystemdDropIn` is a CLASS inside
    cdk8s-systemd modelling a systemd-runtime drop-in — a different layer, do not conflate.)
  - Consumers are NOT exclusive, so do NOT prefix by consumer (rejected `manifests-cdk8s-systemd`):
    netplan ← manifests AND seed-master; systemd-contract ← seed-master only (NOT manifests);
    cdk8s-systemd ← manifests only. Encoding a consumer in a name lies as soon as a 2nd appears
    ([[step2-decomposition-state]] single-source-of-truth). GROUP BY DOMAIN instead — the FS says the
    role via the domain (systemd = contract + cdk8s integration; netplan = network).
  - OPEN for that branch: does `osgi/systemd/` get an aggregator pom (like `osgi/osgi-bench/`) or just
    a dir grouping? Decide on the spot. These become bnd library bundles (Export-Package) like manifests.
- LATER steps: the `unitrepo-pulumi` ACL/mediation seam (host-side, faces both); bdd-core/bdd-ledger
  split (oracle-validated). See [[step2-decomposition-state]] [[hub:docrepo-dag-state]] for the roster.

See [[layout-skeleton-state]] (step 1, the host-parent hook), [[osgi-space-bundles-state]] (step 2,
the GAV-not-relativePath fact + bundle pattern), [[bnd-annotations-spike-state]],
[[merge-from-target-worktree]], [[standing-autonomy-except-runtime-config]], [[build-verification-gotchas]].
