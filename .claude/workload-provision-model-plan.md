# Workload provision model — converged design + worklist

> ## ⏸ SESSION RESUME (2026-09-14, fresh 1M session)
> **DONE + committed (NOT pushed — push at the live checkpoint):**
> - seed-incluster `717ef7da0` (branch `seed-incluster`): C1 (2×2 CRD types) + C2 (4 reconcilers,
>   strict symmetry) + C2-bis producer (`make rbac`, flake `seed-incluster-rbac`). go build/vet green.
> - flox-controller `99075e3` (branch `develop`): RBAC single-source (`make rbac` + flake export).
> - rke2lab `3ebc0cf34` + `18ed69a83` (docs) + `350025374` (branch `feature/nixos-node-substrate`):
>   C3 (render 2×2, mgmt+workload+renderer), C2-bis consumer (both controllers, `/rbac/<controller>/`),
>   docs rename → 2×2. `manifests-core package` BUILD SUCCESS with staging ON.
> - **flake.lock is relocked to local `path:` inputs** for seed-incluster + flox-controller (COMMITTED
>   in `350025374`) — a controller commit propagates to rke2lab with no push (dev loop). AT THE LIVE
>   CHECKPOINT: push all 3 branches + relock to the pushed github revs + commit + run a live synthesis /
>   re-grow to fully validate (compile + staging proven; the synthesis RUN is not yet exercised).
> - Build gotcha: use `package -Dmaven.build.cache.skipCache=true` (a bare `compile` fails sibling
>   resolution; `-am package` builds the reactor jars). Staging needs nix (no `-Dflox.crd-staging.skip`).
>
> **NEXT (user's order was: rename→local-path→generalize→C6+C5; first three DONE):** C6 (quick) then
> C5 (big), then C4, C7 — see the worklist below. Scope still LOCKED to `bioskop-wrkld` × `control-node`.
> Standing: commit freely on topic branches (don't ask); push/relock/pulumi/kubectl = USER; French convo.

Converged over the 2026-09-13 design session (bioskop-wrkld greenfield). Grave the
specs/atlas first (this file is the worklist, not the spec), then adapt the codebase.

## Vocabulary (locked)

- **cluster** — `<host>-<cluster-role>`, e.g. `bioskop-wrkld`. Its branch = `manifests/<cluster>`.
- **cluster-role** — `mgmt` / `wrkld` (`ClusterRole`, manifests-contract) — the CLUSTER's role
  (which manifest domains it renders). Distinct from a node pool.
- **pool** — `control-node`, `gpu-node`, `storage-node`, … — a GROUP of pets sharing ONE profile +
  ONE template. (Was loosely called "role" earlier; "pool" is the standard node-pool term and
  avoids clashing with cluster-role and CAPI's control-plane/worker.)
- **pet / node** — `<cluster>-master`, `-peer1`, `-gpu1` — a named member of a pool.

Profile (branch) is **per pool**; template (CAPI) is **per pool**; the roster
(`ClusterIntention.spec.nodes`) is `{name, pool}` per pet. CAPI treatment DERIVES from the pool:
pool `control-node` ⇒ control-plane (`RKE2ControlPlane`, etcd — NOT a MachineDeployment); other
pools ⇒ worker.

## Immediate scope

**Grave the GENERAL model** (role = profile class, extensible to `gpu-node`/`storage-node`, workers,
specializations). **Code ONLY `bioskop-wrkld` × `control-node`** — master + peer1 + peer2, zero
workers. So: NodeSpec carries `role` (the profile class) but the only role exercised now is
`control-node`; worker profiles/templates + `NodeSpec` specialization machinery (item F) stay
grave-only / future. `install-rke2-config` picks by role, but only the `control-node` profile is
rendered for `bioskop-wrkld` today.

## The model (converged)

> ⚠️ The CONFIG bullets below (4, 6, 7, 8) were written EARLY and were SUPERSEDED by later
> iterations. The **authoritative config model** is: config = VISIBLE k8s resources in the mgmt
> cluster (Flux-applied, no `LOCAL_CONFIG`); delivery split by management relationship — **self
> (mgmt/root) = git-fetch `install-rke2-config`**, **managed (workload) = bootstrap-inject
> (seed-incluster reads the k8s config + injects into the CAPRKE2 `RKE2Config`)**; cleanup =
> Flux-prune + ownerRef-GC. See C4/C5 below + the whiteboard's "★ MODÈLE CONFIG — CONVERGÉ".

1. **SSOT = `ClusterNetworkBlueprint` (netplan).** All cluster addressing derives from the
   `clusterName`, deterministically. Nothing recomputes it.
2. **`ClusterIntention` = the CAPI-intent PROJECTION of the blueprint for one cluster** — VIP,
   CIDRs, the node roster `{name, role, specialization?}` — PLUS the non-network facts (image
   fingerprint, RKE2/kube-vip versions, identity secret, remote endpoint). Thin controller: zero
   addressing logic in Go (the doc's anti-pattern).
3. **Three projections of the one blueprint**, never recomputed:
   - vmnet **DHCP reservations** (`GrowNetworkResolver`) → the node receives ITS ip at boot;
   - **`ClusterIntention`** (CAPI intent) → VIP, CIDRs, roster;
   - the **branch** cluster-common config (`RuntimeRke2ConfigManifestsUnit`) → VIP, CIDRs, tls-san.
4. **The branch owns the config PROFILES, per (role × specialization)** — control-plane,
   worker-`<spec>`. Cluster-common + per-role/spec config. `tls-san` = an **all-nodes superset**
   (VIP + every node's .local FQDN + gateway/lan/localhost — a cert valid for all, harmless).
5. **Per-node values are LOCAL facts** — `node-ip` (the node's own vmnet interface, DHCP-reserved
   from the blueprint), `node-name` (hostname), `provider-id` (`lxc:///<hostname>`). Resolved
   **on-node by oneshots** (already the pattern for `node-labels` + `provider-id`; add `node-ip`).
   NOT rendered per-node. **The `node=` render param dissolves.**
6. **Uniform delivery** — `nix run manifests/<cluster>#install-rke2-config`, standalone AND
   in-cluster. The node installs the profile matching **its (role, specialization)** — derived
   from its own identity (hostname), not a per-node fragment.
7. **Two config layers.** L1 = RKE2 **boot** config (branch profiles via `install-rke2-config`,
   PRE-Flux, needed at `rke2` start). L2 = the **app stack** (Flux in the cluster, with its OWN
   self-minted `contents:read` token — `GithubAppSecretManifestsUnit`).
8. **The workload node fetches its branch at boot (L1)** → needs a `contents:read` token in the
   cloud-init → **seed-incluster mints + injects it into the CAPRKE2 `RKE2Config`**
   (`preRKE2Commands`). NOT `fileNodeGithubToken` (which stays `WRKLD`-skipped: the render's
   cellar→devlxd pose is the standalone-GROW mechanism only).
9. **Day-0 workload render = a one-off Tekton `PipelineRun`** driven from the **mgmt**
   (seed-incluster), `render-manifests cluster=<host>-wrkld`. Steady-state = the workload's OWN
   PaC self-render once it is up. `.tekton/render.yaml` stays on the **source** branch (correct —
   push-triggered, source-scoped, cuts the render→render loop). `Repository` CR binds the REPO and
   selects the rendered branch via `spec.params.cluster`.
10. **All nodes = named pets** (CP AND workers), adopted by `providerID`. Workers grouped by
    **specialization** (multiple profiles + templates), NOT a `MachineDeployment` cattle pool
    (random names leak on cold-start — the seeding-controller invariant).
11. **State machine** — adopt-first funnel `Pending → Adopting(hub) → Provisioning / Degraded /
    Adopted`. CODED this session (transitions), build+vet green, UNCOMMITTED in the seed-incluster
    worktree.

## Grave (docs) — do first

- [ ] `docs/architecture/cluster-api/cluster-seeding-controller.adoc` — add the OWNERSHIP model
      (branch=profiles per role×spec; ClusterIntention=roster/netplan-projection; per-node=local
      oneshots; uniform `install-rke2-config`; L1/L2 layers; day-0 Tekton bootstrap render;
      read-token via seed-incluster, not fileNodeGithubToken). State machine already there.
- [ ] `docs/architecture/atlas/seed.adoc` (+ siblings) — the SSOT→projections figure; profile/roster
      ownership.
- [ ] `docs/architecture/cluster-api/manifests-rendered-branches.adoc` — branch = profiles per
      (role × spec); `install-rke2-config` role/spec-aware; uniform delivery; day-0 vs steady-state
      trigger.
- [ ] `docs/architecture/cluster-api/cluster-workload-completion-plan.adoc` — reconcile: pet
      workers + specializations + the render prerequisite; drop the MachineDeployment-cattle wording.

## Adapt (code) — DECOMPOSED shape (supersedes the monolithic A–H below)

Design converged 2026-09-13 (brainstorm) + graved into cluster-seeding-controller.adoc.
The 2×2 decomposition reworks the CRDs + reconcilers + moves the state machine to per-pool.

- [x] **C1. CRDs (seed-incluster `api/v1alpha1`)** — DONE. Decomposed into 5 files
  (`shared_types.go`, `clusterintention_types.go`, `poolintention_types.go`, `clusteradoption_types.go`,
  `pooladoption_types.go`); old `clusterprovision_types.go` + its CRD removed; deepcopy + CRD manifests
  regenerated. `NodeSpec.Role` lifted to pool grain as `PoolRole` (control-plane|worker, explicit
  discriminant, no magic pool-name). Cluster facts (image/versions/endpoint) DUPLICATED into the pool
  grain (both projected from the one SSOT) → reconcilers decoupled. Labels
  `cluster.seedmatic.io/{cluster,pool}` on PoolIntention/PoolAdoption for aggregation.
- [x] **C2. Reconcilers (seed-incluster)** — DONE (build+vet green). **DECISION: 4 reconcilers,
  strict 2×2 symmetry** (not the 2 the earlier note said) — the mirror-(b) choice + the self-guard
  finalizer (needs `For(ClusterAdoption)`) require it:
  - `ClusterIntentionReconciler` / `PoolIntentionReconciler` = THIN (CreateOrUpdate their mirror,
    SetControllerReference, mirror the phase back). PoolIntention stamps the cluster/pool labels.
  - `ClusterAdoptionReconciler` = owns Cluster+LXCCluster (paused), aggregates PoolAdoptions (lists by
    LabelCluster → existence=OR presence, poolsAdopted, anyProvisioning), unpauses Cluster on
    existence, reachability via RemoteConnectionProbe, self-guard finalizer, Watches(PoolAdoption).
  - `PoolAdoptionReconciler` = owns RCP+LXCMachineTemplate (ownerRef→PoolAdoption for GC) + per-pet
    Machine/LXCMachine + bootstrap sentinel; per-pool adopt-first funnel (the state machine MOVED here);
    worker path = honest guarded early-return (out of scope). BYO-CA gate here; identity gate at cluster.
  - Shared: `controller_shared.go` (GVKs, `ensure`, `setCondition`/`conditionTrue`/`conditionReason`,
    `newObj`, `toAny`, `ptrBool`). `main.go` wires all 4.
- [x] **C2-bis. Controller OWNS its deploy bundle — DONE (RBAC only).** Producer (seed-incluster
      `717ef7da0`): `make rbac` → `config/rbac/role.yaml` (roleName=seed-incluster, matches the
      binding), flake exports `seed-incluster-rbac`. Consumer (rke2lab `3ebc0cf34`): flake binds
      `seedInclusterRbac` + `rbacStagingDir` + `stageSeedInclusterRbac` + `apps.stage-seed-incluster-rbac`
      + stages it in `buildReactorExe`; pom chains the rbac staging exec + adds the `/rbac/` resource;
      `SeedInclusterManifestsUnit` includes the staged ClusterRole (retired the hand-listed rules) + the
      4 CRD names. **GENERALIZED to flox-controller — DONE** (rke2lab `350025374`, flox-controller
      develop `99075e3`): same pattern; the shared `/rbac/` dir is namespaced PER CONTROLLER
      (`/rbac/<controller>/role.yaml`) to avoid the generic-`role.yaml` collision; verified the
      generated role covers flox-controller's hand-listed rules exactly. Both relocked to local `path:`
      inputs (flake.lock committed).
- [x] **C3. Render — DONE (rke2lab `3ebc0cf34`).** `ClusterApiWorkloadManifestsUnit` +
      `ClusterApiManagementManifestsUnit` both emit `ClusterIntention` + a control-node `PoolIntention`
      via the shared `ClusterApiCrRenderer` (uniform). The mgmt self-adoption MOVED off the direct
      `ClusterAdoption` onto the same intent (kind=management, 1 pet) — required because the decomposed
      `ClusterAdoption` is a pure aggregate. `manifests-core` compiles (BUILD SUCCESS, staging skipped).
- [x] **C2-bis/C3 follow-ups — DONE.** (a) **Local-path flake input**: `nix flake lock
      --override-input seed-incluster path:…/rke2lab.d/seed-incluster` relocks to the local worktree
      (a controller commit propagates without push). PROVEN: `manifests-core package` (staging ON)
      stages the 4 seed-incluster CRDs → `/crds/` (7 total) + `role.yaml` → `/rbac/`, embeds them,
      BUILD SUCCESS — so `UpstreamYamlInclusion` will resolve them at synthesis. ⚠️ `flake.lock` is
      left UNCOMMITTED (host-specific local-path = dev state); at the live checkpoint, push
      seed-incluster + relock to the pushed rev + commit. Full synthesis-RUN validation = live
      checkpoint. (b) **Docs rename** DONE (`18ed69a83`): the 3 .adoc reconciled to the 2×2 (0 old
      names remain).
- [ ] **C4. Provision execution (greenfield) + workload config delivery** — for an absent pet:
  providerID EMPTY + a real `RKE2Config` (BYO-CA + init/join). **seed-incluster READS the workload's
  visible k8s config (per `cluster,pool`) + injects it into the `RKE2Config`** (bootstrap-inject = the
  managed delivery path; NO git-fetch / read-token on the workload node). Gate provision on existence
  (`<<existence>>`). NB: the day-0 app-branch Tekton bootstrap-render (one-off PipelineRun) is a
  SEPARATE app-stack concern (post-up), NOT a boot-blocker.
- [ ] **C5. Config-model refactor** — config = VISIBLE k8s resources in the mgmt (Flux-applied):
  - `RuntimeRke2ConfigManifestsUnit`: iterate `{subject} ∪ workloadTargets`, emit per
    `(cluster × pool)` under `.../rke2-config/<cluster>/<pool>/` on `manifests/<mgmt>`, **DROP
    `LOCAL_CONFIG`** so Flux APPLIES them (visible `ConfigMap`/`Secret`s). Split cluster-common vs
    per-pool; DROP the join token fragment (CAPRKE2 owns it); `tls-san` all-nodes superset; DROP
    per-node baking (node-ip/name/FQDN).
  - `install-rke2-config` flake = the **SELF path** (mgmt/root git-fetch): **filter by `(cluster,
    pool)` — MANDATORY** (install only THIS node's config, not the co-located workloads'). Node
    derives cluster+pool from hostname. (Managed workloads use bootstrap-inject — C4, not this.)
  - on-node `node-ip` oneshot (nixos/rke2.nix); `node=` param dissolves.
  - ROOT branch layout migration flat → `<cluster>/<pool>/` (next re-grow). Workload branch = app
    stack ONLY.
- [ ] **C6. `fileNodeGithubToken` skip for `WRKLD`** (rke2lab manifests-bdd).
- [ ] **C7. Étape B** — the `vmnet-<role>` NIC on the pool's `LXCMachineTemplate`.

Sequence: C1 (CRDs) → C2 (reconcilers) → C3 (render) → C6 (quick) → C5 (config-model) → C4 (execution)
→ C7 (NIC). Live deploy/re-grow = USER's.

## (SUPERSEDED) Adapt (code) — monolithic sequence

- [ ] **A.** seed-incluster: COMMIT the state-machine transitions (done, uncommitted).
- [ ] **B.** rke2lab `manifests-bdd`: `fileNodeGithubToken` — skip for `WRKLD` (role-aware).
- [ ] **C.** rke2lab `RuntimeRke2ConfigManifestsUnit`: split cluster-common vs per-role profiles;
      DROP the per-node baking (node-ip/node-name/mDNS-FQDN); `tls-san` = all-nodes superset; remove
      the `node=` dependence.
- [ ] **D.** `install-rke2-config` flake (in `ManifestSynthesisScenario`): role/specialization-aware
      profile selection (filter by the node's own identity).
- [ ] **E.** `nixos/rke2.nix`: on-node `node-ip` oneshot (join the node-labels + provider-id pattern).
- [ ] **F.** `NodeSpec` gains `specialization`/`pool` (seed-incluster api + rke2lab ClusterIntention
      render) — mostly FUTURE (workers); CP-only now.
- [ ] **G.** seed-incluster PROVISION execution: greenfield (providerID EMPTY + real `RKE2Config`:
      BYO-CA + init/join + the `install-rke2-config` invocation + inject the read-token); the day-0
      Tekton bootstrap-render trigger (one-off PipelineRun, replicating PaC's git_auth + flox
      annotations); gate provision on the render's success (a new funnel condition).
- [ ] **H.** Étape B: the `vmnet-<role>` NIC on the workload `LXCMachine`.

Sequence: grave → A,B (quick) → C,D,E (the config-model refactor) → G (execution) → H → F (workers).
