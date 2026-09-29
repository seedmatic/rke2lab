---
name: workload-provision-live-validation-2026-09-14
description: Live cold-start of bioskop-mgmt proved C5 (config model) + C4a (bootstrap-inject) + the 2×2 self-adoption; two live bugs fixed; C4b greenfield spike findings.
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-15T15:18:50.268Z
---

2026-09-14: a full cold-start re-grow of **bioskop-mgmt** (instance dropped, `manifests/*` branches dropped, re-rendered fresh) LIVE-PROVED this session's work end-to-end, and surfaced+fixed two bugs.

**Proven live (mgmt cold-start):**
- **C5.1/C5.2** — config rendered per `(cluster×pool)` under `workloads/runtime/rke2-config/<cluster>/control-node/`, visible names → **Flux-applied as real ConfigMaps** in ns `rke2lab-<cluster>` (`kubectl get cm -n rke2lab-bioskop-mgmt` = the 7 fragments). The mgmt branch carries BOTH bioskop-mgmt AND bioskop-wrkld config + the 2×2 CRs (model B).
- **C5.3** — `install-rke2-config` filtered to the node's own cluster: journal `installed 7 fragment(s) for cluster bioskop-mgmt` — **7 not 14**, the co-located bioskop-wrkld config correctly excluded (the mandatory anti-contamination filter, proven).
- **C5.4** — on-node `node-ip` oneshot: node InternalIP = `10.80.0.10` (the vmnet0 reservation), NOT a cilium_host pod-cidr IP → the VIP tls-san cert is valid → kubectl works, `RemoteConnectionProbe=True`. `config.yaml.d` = 7 branch fragments + `30-node-labels`/`35-node-ip`/`40-provider-id` drop-ins.
- **2×2 self-adoption** — seed-incluster (new controller, delivered via FloxEnv from flox-catalogue) created ClusterAdoption/PoolAdoption mirrors; after the pause fix: **ClusterAdoption Adopted, EXISTS/REACHABLE=true, PoolAdoption Adopted, PRESENT=1**. C4a config-inject worked (pool passed the config gate = the 7 ConfigMaps read → RCP `files`).

**Two live bugs found + fixed:**
1. **SC2291 (install-rke2-config never built)** — the C5.3 two-line `echo` kept the Java text-block continuation's leading spaces → repeated spaces → shellcheck SC2291 → `writeShellApplication` FAILS the derivation → `nix run #install-rke2-config` errored on every node, rke2 config-less. Fix `048bd59ed`: single-line echo. **Lesson: writeShellApplication fails the build on ANY shellcheck finding, incl. info-level; a multi-line echo in a nix text-block collapses to repeated spaces.**
2. **Cluster-pause deadlock (adoption)** — my 2×2 `ClusterAdoptionReconciler` created the `Cluster` paused + unpaused only on existence. A paused Cluster propagates to its LXCMachines → CAPN's `IsPaused` SKIPS them → never reports `InstanceProvisioned` → existence stays false → never unpauses. Proven: capn-controller Running, adopt-shaped LXCMachine sat condition-less while `Cluster.spec.paused=true`; a manual `kubectl patch ... paused:false` → CAPN adopted instantly (InstanceProvisioned=True → the whole chain converged). Fix `ccad711fb` (seed-incluster): **create the Cluster UNPAUSED**; the rival-init guard is the RKE2ControlPlane's OWN paused annotation (un-paused by PoolAdoption), NOT the Cluster's. Ensure-unpause unconditionally so an already-paused Cluster self-heals.

**C4b greenfield spike (bioskop-wrkld unpaused, no instances, provision code NOT written):**
- CAPN on an adopt-shaped absent pet → `InstanceProvisioned=False(InstanceDeleted)` (confirms absent, creates nothing). PoolAdoption → `Provisioning`.
- **CAPRKE2 ADOPTS the pre-created named Machines as its replicas — no rival random-named replica** (RCP REPLICAS=3 = our 3 named Machines master/peer1/peer2, PHASE=Provisioned, READY=False waiting on infra). → **the named-pets model works; C4b uncertainty #2 resolved.**
- So **C4b = for an absent pet, recreate its LXCMachine providerID-EMPTY** (CAPN then CREATES the instance) + a real bootstrap (the sentinel dataSecretName is empty) — WITHOUT deleting the Machine (avoid an RCP rival). Uncertainty #1 (delete-recreate) is narrower than feared: touch only the infra (LXCMachine), not the Machine.

**RESOLVED (2026-09-14, later) — the propagation "blocker" was NOT a nix fetcher-cache quirk, it was a double-pin design smell.** seed-incluster was pinned in TWO ungoverned places: rke2lab's own `seed-incluster` input AND the flox-catalogue's independent `seed-incluster.url`. The catalog's DIRECT input DID resolve `ccad711fb` fine (`b424c34c7` committed it); what kept showing `fc01196dc` was the TRANSITIVE `rke2lab/seed-incluster` node — correctly mirroring rke2lab's stale pin (`137d5dbca` still pinned fc01196dc), not a cache bug. Risk: the deployed binary (catalog@ccad711fb) would drift from the ClusterAdoption CRD + ClusterRole rke2lab renders (rke2lab@fc01196dc) = binary-vs-schema skew. Fix = make **rke2lab the single owner** (same rule as `ndh`): flox-catalogue `seed-incluster.follows = "rke2lab/seed-incluster"` (drops the `.url` + 3 sub-input follows), bump rke2lab (`137d5dbca→46c11ff38`, seed-incluster pin `fc01196dc→ccad711fb`, pushed), then catalog `nix flake update rke2lab` → one seed-incluster node @ccad711fb. Env re-lock (`nix run .#lock-envs -- cluster-api/seed-incluster`) → same derivation `7h3rz4…`. flox-catalogue commit `44cabfeb3` (NOT pushed). NB: `lock-envs` has NO fixpoint — the env `manifest.lock` `locked-url` pins a `narHash` of the WHOLE catalog tree (`path:../../..` self-ref), so each re-lock churns the `locked-url` even on a clean tree (derivation stays stable). Handled in the app (catalogue `648c00f7a`, NOT pushed): `nix run .#lock-envs` now diffs each freshly-locked file vs HEAD with `locked-url` stripped (jq `del(.packages[]."locked-url")`) — pure churn is reverted+unstaged, only a real derivation/outputs change (or a new untracked env) is committed via explicit pathspec (git+jq added to runtimeInputs). Idempotent: a no-op run leaves a clean tree, no commit. The underlying path:-self-ref churn is NOT eliminated, just made invisible to git history.

**Cross-worktree propagation app (rke2lab `097d09559`, NOT pushed): `nix run .#propagate-seed-incluster`.** Walks the whole seed-incluster bump across the 3 rke2lab worktrees — all branches of the SAME repo, discovered via `git worktree list` (no hard-coded paths): hop1 `git push origin seed-incluster`; hop2 rke2lab `nix flake update seed-incluster` + commit + push; hop3 flox-catalogue `nix flake update rke2lab` + `nix run .#lock-envs` + commit, STOPS before the catalog push (operator validates the artifact the cluster consumes). Chain is push-gated (github: input sees a rev only once pushed) → the 2 upstream hops auto-push by design; a guard aborts unless flox-catalogue's rke2lab input ref == current branch. Idempotent (skips a hop already at target rev). Built shellcheck-clean; the `#shellcheck disable=SC2016` covers the jq lockrev filter, and writeShellApplication fails the build on ANY shellcheck finding incl. info-level (e.g. SC1007 on `local x= y=`). **Lesson: a shared branch consumed by both rke2lab and the catalog must be pinned ONCE (rke2lab = SSOT, catalog `follows`); never re-pin with `.url`.**

**C4b CODED (2026-09-15, seed-incluster `0938170e1`, build+vet+gofmt green, NOT pushed).** The greenfield
provision-shape arm shipped in `pooladoption_controller.go`: `clusterExistence` reads
`ClusterAdoption.status.existence` → `greenfield = existenceDecided && !exists`; in greenfield `provisionPet`
flips each pet via `ensureProvisionShape` (per-object idempotent: absent→create / terminating→wait /
adopt-shape→delete / provision→leave, keyed off each object's OWN shape) → providerID-EMPTY LXCMachine +
Machine `bootstrap.configRef`→per-pet `RKE2Config`=copy of the LIVE RCP `{agentConfig,files,preRKE2Commands}`
(`liveRCPConfigSpec`, so CAPRKE2 won't roll). `materialized` needs BOTH LXCMachine AND Machine provision-shape;
RCP held paused (`flipping>0`→early requeue) until all pets stable, then unpaused (no random-named rival
mid-flip). JOIN (absent but cluster exists via another pool) = HOLD (`NodeMissing`→Adopting). **Two defects I
caught while coding:** (1) the plan's `Owns(&Machine{})`/`Owns(&LXCMachine{})` are DEAD wiring — those objects'
controller-ownerRef is the RCP/Machine, NOT the PoolAdoption, so `Owns` never fires; replaced by `Watches`
mapped by the cluster-name label (the honest presence trigger). (2) gating "flipping" only on the LXCMachine
would let CAPRKE2 scale a rival while the Machine was still adopt-shape → extended to require the Machine too.
ClusterAdoption gained `Owns(Cluster)/Owns(LXCCluster)` (recreate/rebirth F5). RBAC regen: rke2configs create,
delete machines/lxcmachines, get clusteradoptions. Day-0 converges in 2 reconcile rounds via the two-controller
watch feedback (probe adopt-shape → existence=false → flip). **Pending:** live re-grow of bioskop-wrkld to
validate greenfield end-to-end (relock rke2lab on the new seed-incluster rev + push first — USER); bioskop-wrkld
still unpaused in Provisioning limbo until then; C7 (`vmnet-<role>` NIC); the reflector loop (deferred until
scaling). See [[capi-cluster-seeding-mirror-design]].

**★ PIVOT 2026-09-15 — C4b greenfield-arm REVERTED after a live re-grow proved the model WRONG.** Pushed
the whole chain (seed-incluster `0938170e1`, rke2lab flake.lock `c326ffd9c`, flox-catalogue `ad7739a44`
follows+env re-lock — the FloxEnv derivation genuinely bumped `7h3rz4l…`→`fa5hbrqw…`, flox-controller
auto-restarted the pod for the lock), the C4b controller went live on bioskop-wrkld (greenfield) → and it
FOUGHT CAPRKE2: seed-incluster deleting/re-creating Machines to flip adopt→provision races the RCP (which
OWNS the Machines + maintains `replicas=3`) and the core Machine deletion cascade (`WaitingForInfrastructure
Deletion` deadlocks when we re-create the LXCMachine it's waiting to see gone) → create/delete churn. **User's
corrected model: seed-incluster ONLY OBSERVES; CAPI/CAPN/CAPRKE2 own Machine create+delete.** (1) **Greenfield
= `PoolReflection` ABSENT** → create Cluster+RCP(replicas=N)+template+config UNPAUSED, create NO Machines →
CAPRKE2 provisions its own (random names OK) → CAPN launches → the **reflector observes** the emergent roster
→ writes the first `PoolReflection` (git). (2) **Adopt = `PoolReflection` PRESENT** → pre-create the named
Machines from that persisted roster → CAPRKE2 adopts surviving instances by name. (3) **The adopt/greenfield
SWITCH = `PoolReflection` presence, NOT the CAPN liveness probe** — the graved `<<existence>>` axis (CAPN
adopt-shape probe as decider) is dropped/revised. (4) **Build order INVERTED: the reflector is FIRST** (it
produces the reflection the decision reads; the plan had it deferred). Reverted seed-incluster `10e4dc920`
(pushed); live: seed-incluster deploy scaled to 0 + bioskop-wrkld frozen `Cluster.paused=true` (a
`InstanceProvisioningFailed` on -master may have left an Incus residue to GC). NEXT = build the reflector
(`PoolReflection` CRD + observe→git loop + App-token) then the presence switch then greenfield=RCP-replicas-only.
Design graved in `.claude/workload-provision-model-plan.md` (PIVOT header). See [[capi-cluster-seeding-mirror-design]].

Commits (none pushed except seed-incluster `ccad711fb`): see [[workload-grow-foundations-resume]] and the plan `.claude/workload-provision-model-plan.md`.

**DESIGN GRAVED (2026-09-14 cont., specs/atlas before compaction) — the REFLECTOR / unconditional-determinism turn.** A long design dialogue converged a new capability and it is now graved (DOC-only; code deferred, C4b first). The model: adopt-first is *conditionally* deterministic (git reflects INTENT; drifts under scaling); the **cluster→git reflector** runs the missing direction (observe → commit observed roster to the managing branch) → `git == reality` → *unconditional* determinism. Key decisions: (1) carrier = a **dedicated typed CR `PoolReflection`** (per pool, `cluster.seedmatic.io`) — NOT a ConfigMap (the untyped odd-one-out); completes a role TRIAD `PoolIntention` (desired) · `PoolAdoption` (observed EPHEMERAL/etcd) · `PoolReflection` (observed DURABLE/git). (2) **Two-axis, annex has ZERO command semantics**: Intention presence (via `workloadTargets`) = manage-or-not; CAPN liveness probe = adopt-or-greenfield; `PoolReflection` only supplies the *names to probe*, never decides (never greenfield from its absence). (3) **ownerRef↔annotation translation**: etcd = UID ownerRef, git = name-based `cluster.seedmatic.io/owner-ref` annotation; controller re-stamps the ownerRef each reconcile (Velero remap pattern). (4) **precedence by presence**: `roster = PoolReflection ELSE Intention.spec.nodes` (day-0 seed → steady-state observed). (5) **no git merge**: reflector owns only the `PoolReflection` file, render owns the rest → disjoint, `image-automation-controller` loop (App-token, retry, NEVER force-push, stage ONLY that file). (6) **boundary STANDALONE=canonical (self can't reflect its own cold-start) / IN_CLUSTER=reflected** (maps onto the enclosure axis). (7) **recreate gestures**: `delete clusters.cluster.x-k8s.io` → CAPI teardown + our `ensure`-from-Intention resurrect → greenfield (add `Owns(&Cluster{})`); the Intention (workloadTargets) is the real decommission toggle; deleting `PoolReflection` = no-op. (8) worktree = **ephemeral/in-memory go-git** (PVC only if clones prove costly); **NO git-sops import** (reflector writes a non-secret roster; go-git filter-blindness is a safe identity round-trip if you stage ONLY `PoolReflection` + stay go-git both ends). (9) annex-semantics fork (reset-baseline vs restore-scaled, lean **restore-scaled**) deferred to scaling. Term "reflector" KEPT (qualified "cluster→git reflector" in the atlas to avoid the pipeline `ShapeReflector`/`SplitReflector` clash). GRAVED in `docs/architecture/cluster-api/cluster-seeding-controller.adoc` (§reflector F1/F3/F4, §recreate F5, §greenfield-arm + JOIN→HOLD note) + NEW atlas L1 `docs/architecture/atlas/cluster-seeding.adoc` (Diagram W avant→après, registered in `integration-atlas.adoc` + README). NEXT (code, dedicated session): C4b greenfield-arm first (probe existence → provision-shape: providerID-empty + Machine configRef + `RKE2Config`=copy of live RCP + RCP paused-until-stable, gate on `ClusterAdoption.status.existence`, add the `Owns`); reflector loop is scaffolding INERT at fixed pets. All doc edits + plan on `feature/nixos-node-substrate`, uncommitted.
