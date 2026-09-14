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
> **⚠️ PIVOT 2026-09-15 — C4b greenfield-arm REVERTED; the reflector comes FIRST.** The live re-grow
> PROVED the C4b "adopt-shape probe → flip to provision-shape" model WRONG: seed-incluster deleting/
> re-creating Machines to flip them FIGHTS CAPRKE2 (the RCP owns the Machines + maintains replicas) →
> churn/deadlock. **User's corrected model (2026-09-15): seed-incluster only OBSERVES; CAPI/CAPN/CAPRKE2
> create+delete the Machines.**
> - **Greenfield** = `PoolReflection` ABSENT: seed-incluster creates Cluster + RCP(replicas=N) + template
>   + config, UNPAUSED, and creates NO Machines — CAPRKE2 provisions its own → CAPN launches → the
>   **reflector observes** the emergent roster and writes the first `PoolReflection`. Random CAPRKE2 names
>   are FINE because the reflector persists them (that IS the unconditional determinism).
> - **Adopt** = `PoolReflection` PRESENT (a prior life persisted the observed roster): pre-create the
>   named Machines from that roster → CAPRKE2 adopts the surviving instances by name.
> - **The switch is `PoolReflection` PRESENCE, not the CAPN liveness probe** (the graved `<<existence>>`
>   axis must be revised — the CAPN adopt-shape probe as adopt/greenfield DECIDER is dropped).
> - **Build ORDER inverted: the reflector is FIRST** (it produces the `PoolReflection` the decision reads).
> Reverted: seed-incluster `10e4dc920` (reverts `0938170e1`), pushed. Live: seed-incluster deploy scaled
> to 0, bioskop-wrkld frozen (`Cluster.paused=true`) — a failed CAPN launch attempt on -master may have
> left an Incus residue to GC at teardown. Pins still at C4b (moot at 0 replicas; roll forward on the
> reflector build).
>
> **NEXT:** build the reflector (`PoolReflection` CRD + observe→git loop + App-token) FIRST, then the
> adopt/greenfield switch on presence, then greenfield=RCP-replicas-only. Then C7 (`vmnet-<role>` NIC).
> Scope still LOCKED to `bioskop-wrkld` × `control-node`. Standing: commit freely on topic branches
> (don't ask); push/relock/pulumi/kubectl = USER (but the user ceded the checkpoint this session); French.
> DONE (unchanged): C6, C5.1–C5.4, C4a (seed-incluster `264d79694`).

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
- [x] **C4a. Workload config bootstrap-inject — DONE** (seed-incluster `264d79694`).
  `PoolAdoptionReconciler.clusterConfigFiles` lists the visible RKE2 config ConfigMaps rke2lab renders
  into `rke2lab-<cluster>` (annotated `io.seedmatic.rke2lab/rke2-config`), reconstructs each
  config.yaml.d fragment (`data | value|=from_yaml`), and injects them as `RKE2ControlPlane.files` — so
  a provisioned replica boots with the same config a standalone node git-fetches, no git/read-token on
  the node. Gated like the BYO-CA (essential config: wait for Flux). Added the `configmaps` RBAC.
- [x] **C4b. Greenfield provision — DONE** (seed-incluster `0938170e1`, build+vet+gofmt green, NOT pushed).
  `PoolAdoptionReconciler` now gives `Provisioning` its action: reads the cluster-grain verdict
  `ClusterAdoption.status.existence` (`clusterExistence`); `greenfield = existenceDecided && !exists`. In
  greenfield, `provisionPet` flips each pet to provision-shape via `ensureProvisionShape` (per-object:
  absent→create provision / terminating→wait / adopt-shape→delete+recreate / provision→leave — keyed off
  each object's OWN shape, idempotent): providerID-EMPTY LXCMachine + Machine `bootstrap.configRef`→per-pet
  `RKE2Config` = copy of the LIVE RCP `{agentConfig,files,preRKE2Commands}` (`liveRCPConfigSpec`, so CAPRKE2
  won't roll). `materialized` requires BOTH LXCMachine AND Machine in provision-shape; RCP held paused
  (`flipping>0` → early requeue) until all pets stable, then unpaused. JOIN case (absent & cluster exists via
  another pool) = HOLD reason `NodeMissing`→Adopting (never greenfield a rival). Watches: `ClusterAdoption`→
  pools (verdict flip) + `LXCMachine`→pools by cluster label (presence trigger — the plan's `Owns(&Machine{})`
  was WRONG: LXCMachine/Machine controller-ownerRef is the RCP/Machine, not PoolAdoption, so Owns never
  fires). ClusterAdoption gained `Owns(Cluster)/Owns(LXCCluster)` (recreate/rebirth F5). RBAC: rke2configs
  create + delete machines/lxcmachines + get clusteradoptions (`make rbac` regenerated `role.yaml`). NEXT for
  C4b = live re-grow of bioskop-wrkld to validate greenfield end-to-end (tokens: relock rke2lab on the new
  seed-incluster rev + push). — historical notes below (superseded by the shipped code):

  The per-pet delete-recreate flip I started was WRONG (user correction
  2026-09-14). Decision is CLUSTER-LEVEL, adopt-first, per `docs/architecture/cluster-api/cluster-seeding-controller.adoc`
  §Reconcile (l.169-177) + the user's restatement.

  **The state machine — THREE cluster-level outcomes (adopt-first, never greenfield a rival):**
  1. **Adopt succeeds** (all pets present by providerID + `RemoteConnectionProbe` reachable) → **ADOPTED, stop.**
     CODED + PROVEN LIVE (mgmt self-adoption, `ccad711fb` unpause fix).
  2. **≥1 live machine but adopt incomplete** → **HOLD, stop** (Adopting/Degraded; retry). User: "s'il existe
     au moins une machine vivante, on s'arrête aussi" — DON'T provision the missing ones. ⚠️ This SIMPLIFIES
     the spec, which says `absent & exists → PROVISION as JOIN` (l.174). For the current scope (bioskop-wrkld
     = zero machines) both agree on greenfield; the provision-as-JOIN recovery case = DEFER (decision: spec
     says join, user leans hold).
  3. **Zero live machines** (existence=false, operator-armed by dropping Incus nodes) → **GREENFIELD: create
     the cluster via CAPRKE2.** ← THE MISSING TRANSITION = C4b.
  Existence = ∃ ≥1 pet present by providerID (ANY pool), aggregated on `ClusterAdoption`.

  **What's coded:** adopt path (adopt-shape pets → CAPN presence → RCP adopt → C4a config-inject); existence
  aggregation; phase routing (Adopting/Provisioning/Adopted/Degraded). But `Provisioning` currently takes NO
  action (the gap).

  **CAPRKE2 v0.25.2 contract (from the source read — the enablement; `ghcr.io/rancher/cluster-api-provider-rke2-*:v0.25.2`):**
  - RCP **ADOPTS pre-created named Machines** (ownerRef→RCP + labels `cluster-name`+`control-plane`), NO rival,
    IF `replicas == len(ownedMachines)`. Counts by ownerRef+label only (no hash/annotation needed).
  - **Mixed-mode guard**: EVERY CP-labelled Machine for the cluster must be RCP-owned or the RCP HALTS.
  - Machine (provision): labels + ownerRef→RCP + `spec.clusterName` + `spec.version==rcp.spec.version` +
    `bootstrap.configRef`→RKE2Config + `infrastructureRef`→LXCMachine.
  - **RKE2Config.spec MUST deep-equal `rcp.spec` (rke2ConfigSpec inline: `agentConfig`+`files`+`preRKE2Commands`)
    or the RCP ROLLS the machine** (→ random-name replacement). ⇒ BUILD it by COPYING the LIVE RCP's
    `{agentConfig,files,preRKE2Commands}` (CAPI defaults them, e.g. `agentConfig.format: cloud-config`). Owner→Machine.
  - **init vs join = AUTOMATIC** (bootstrap controller: first while `ClusterControlPlaneInitialized`=false takes
    a per-cluster init lock; rest join once the serving secret + `status.availableServerIPs` exist). NOT encoded.
  - **token + server-URL = FREE** (generated into `<cluster>-token`; init URL from `spec.controlPlaneEndpoint.Host`:9345).
  - **BYO-CA = automatic by naming** `<cluster>-{ca,cca,etcd,peer-etcd}` — the `materialMissing` gate already ensures.
  - LXCMachine (provision): `providerID` EMPTY → CAPN CREATES; ownerRef→Machine; OMIT `TemplateClonedFrom*` annots.

  **WHY pre-create named pets even for greenfield (the crux, user-confirmed 2026-09-14):** letting the RCP
  create its own control-nodes uses RANDOM names (`<rcp>-xxxxx`, `GenerateName` — CAPRKE2 contract). Random
  names break the pets-named invariant → a later cold-start can't re-adopt by deterministic name
  (`lxc:///<cluster>-<node>`) → it would re-greenfield a rival. So greenfield MUST pre-create the NAMED pets
  in provision-shape; CAPRKE2 adopts them (0 rival if `replicas==count`), inits the first, joins the rest.

  **Coding approach — CLUSTER-grain shape decision, NO per-pet flip:**
  - The pet's shape is dictated by the CLUSTER existence verdict, decided BEFORE materializing pets:
    existence=true → adopt-shape (providerID set) [current]; existence=false → provision-shape (providerID
    empty + Machine configRef + RKE2Config=copy of live RCP rke2ConfigSpec) → CAPRKE2 inits first, joins rest.
  - `gvkRKE2Config` (`bootstrap.cluster.x-k8s.io/v1beta2`) already added to `controller_shared.go`.
  - **RCP-rival guard:** RCP stays PAUSED until the pet CR-set is stable (all named pets materialized owned),
    THEN unpause → CAPRKE2 provisions/inits/joins. (RCP paused ≠ bootstrap paused; joins wait on RCP-populated
    `status.availableServerIPs`, so unpause once stable.)

  **⚠️ OPEN DECISION (blocker for coding — user was undecided, "je suis perdu"):** how to establish existence
  BEFORE materializing pets, to avoid the adopt-then-flip churn:
  - (a) **adopt-shape probe** (spec-literal, providerID-based): create adopt-shape LXCMachines (never provision) →
    read CAPN `InstanceProvisioned` → existence. If false → recreate pets provision-shape (a ONE-TIME
    cluster-level arm, not per-pet reactive). Keeps a recreate.
  - (b) **direct Incus query** (user's "statuer si le cluster a des machines dans Incus au runtime"):
    seed-incluster lists Incus instances via the `<host>-incus-identity` creds → existence with ZERO CRs →
    create the right shape upfront, no recreate. Adds an Incus client to the controller; diverges from the
    spec's providerID-based existence.
  - **RESOLVED (user, 2026-09-14): pick (a) CAPN probe — stay in the CAPN layer.** (b) direct-Incus is
    REJECTED: CAPN OWNS the Incus connection (its whole purpose); a seed-incluster Incus client would
    DUPLICATE CAPN and break the layering (same spirit as "one owner per manifest"). Existence goes THROUGH
    CAPN: create the LXCMachine in adopt shape (providerID `lxc:///<cluster>-<node>` set → CAPN never
    creates, only reports present / `InstanceDeleted`). The **providerID stays the matching KEY** (spec l.147)
    — but CAPN confronts it to Incus reality, not us directly.
  - **Consequence — FLEXIBLE arbitrary-node discovery is DROPPED** (it required the direct query that bypasses
    the layer). CAPN can only probe a KNOWN roster (we hand it the `<cluster>-<node>` names to check), it does
    not "discover" arbitrary running nodes. Fine for our scope: the roster is known (`CANONICAL_NODE_NAMES`).
  - **Greenfield via CAPN** = probe adopt (all `InstanceDeleted`) → existence=false → transition to
    provision-shape. The adopt→provision recreate (providerID is immutable) is INHERENT to CAPN, but it is a
    **one-time day-0 ARM** (not per-pet reactive churn), done **RCP-PAUSED** (else the RCP scales a rival while
    the owned Machine is recreated — CAPRKE2 contract). Post-compaction sub-question: minimize/structure that
    recreate cleanly (cluster-level arm, RCP paused until the provision CR-set is stable, then unpause).

  **★★ RESOLVED (CAPN v0.9.0 source read, `github.com/lxc/cluster-api-provider-incus`) — NO in-layer node
  discovery.** CAPN surfaces NO cluster instance-list via any CRD/status/controller (`LXCClusterStatus` has
  only `Initialization.Provisioned` + conditions). The ONLY k8s-visible existence signal is
  per-declared-`LXCMachine` `InstanceProvisioned` — CAPN does a **GET-by-name** on Incus
  (`controller_normal.go:37-47`: `GetInstanceState(instanceName)` → `Instance not found` → `InstanceDeleted`).
  Findings that shape the design:
  - **Grouping key = the config key `user.cluster-name`** (CAPN tags every launched instance with
    `user.cluster-name`/`-namespace`/`-machine-name`/`-cluster-role`, `controller_util_launch.go:92-95`), NOT
    the `lxc:///<cluster>-` providerID/name prefix (fragile — the name is the CAPI object name). ⇐ corrects the
    earlier note.
  - providerID = `lxc:///<name>`, `<name>` = LXCMachine object name = Incus instance name.
  - A bulk `ListInstances`/`GetInstancesFull` exists but under `internal/` (not importable); an external
    controller could reuse the upstream `lxc/incus/v6/client` lib + the `<host>-incus-identity` secret to list
    by `user.cluster-name` (~20 lines). This is a READ (discovery), NOT a reconcile bypass — CAPN keeps
    launch/delete. So the earlier "direct-Incus = bypass CAPN" worry is softer: it duplicates no reconcile.
  **DECISION (settles the fork):** for our scope the roster is KNOWN (`CANONICAL_NODE_NAMES`), so establish
  existence by **probing the known roster via CAPN GET-by-name** (declare adopt-shape LXCMachines, read
  `InstanceProvisioned`) — IN-LAYER, no direct query, no flexible discovery. FLEXIBLE discovery (unknown
  roster) would need the direct Incus read by `user.cluster-name` — DEFER (not needed now). So: adopt-probe
  the CANONICAL roster → all `InstanceDeleted` ⇒ existence=false ⇒ greenfield (one-time day-0 arm to
  provision-shape, RCP-paused). Code the greenfield transition on this basis.

  **Code touch-points (seed-incluster):** `ClusterAdoptionReconciler` (existence verdict, mechanism a/b),
  `PoolAdoptionReconciler` (consume cluster existence; shape = adopt|provision; provision builders
  `rke2ConfigObj`/provision `machineObj`/`lxcMachineObj`; RCP pause-until-stable). NB: day-0 app-branch Tekton
  render = SEPARATE app-stack concern (post-up), not a boot-blocker.
- [x] **C5. Config-model refactor — DONE** (rke2lab `5b143c96b` C5.1, `574a43485` C5.2, `cd23927fb`
  C5.3, `1cb9d3c26` C5.4). config = VISIBLE k8s resources in the mgmt (Flux-applied):
  - **C5.1** `RuntimeRke2ConfigManifestsUnit` (`5b143c96b`): iterates `{subject if MGMT} ∪
    workloadTargets` via `ManifestSynthesisContext.current()`, projects each cluster's blueprint,
    emits per cluster under `rke2-config/<cluster>/control-node/` (package-name subpath), namespace
    `rke2lab-<cluster>` (REFERENCED not created — the cluster-api units own it). Dropped LOCAL_CONFIG,
    the join-token Secret (CAPRKE2 owns it), and all per-node baking (node-ip/name/advertise-addr).
    `tls-san` = all-nodes superset. **Re-cadrage: no common/pool split** — workers are CAPRKE2-injected
    (never fetch), so all branch fragments are control-plane config; there is no cross-pool common.
  - **C5.2** exploder (`574a43485`): removed the RKE2_CONFIG verbatim-name short-circuit so the
    ConfigMaps take the normal `02-configmap-*.yml` visible name → Flux applies them; install-rke2-config
    keys on the annotation, not the filename. Dropped the unused SECRET_KIND; updated the exploder test.
  - **C5.3** `install-rke2-config` flake (`cd23927fb`): the SELF/root path now filters to the node's
    OWN cluster — derives it from the hostname `<cluster>-<node>`, keeps only fragments whose namespace
    is `rke2lab-<cluster>` (the MANDATORY filter). Dropped the now-dead sops-decrypt path.
  - **C5.4** `nixos/rke2.nix` (`1cb9d3c26`): on-node `rke2lab-node-ip` oneshot reads the node's own
    dual-stack `vmnet0` address LIVE, writes `35-node-ip.yaml` (kubelet-arg+, coexists with provider-id),
    gated on node.env (mgmt-only; workload takes node-ip from CAPN). Dropped SOPS_AGE_KEY_FILE (installer
    no longer decrypts) + stale header comments. `node=` param already dissolved in C5.1.
  - **DEFERRED to the live re-grow**: ROOT branch layout migration flat → `<cluster>/<control-node>/`
    (lands with the filter at next re-grow). Workload branch = app stack ONLY. Full synthesis RUN +
    Flux-apply validation = the live checkpoint (compile + exploder-test + nix-parse proven).
- [x] **C6. `fileNodeGithubToken` skip for `WRKLD` — DONE** (rke2lab `262938ba9`). The cellar→devlxd
  token pose is the standalone-GROW mechanism only; a CAPI workload node takes its read token from
  CAPRKE2, so a WRKLD render must not fail-loud demanding a reader mint.
- [ ] **C7. Étape B** — the `vmnet-<role>` NIC on the pool's `LXCMachineTemplate`.

## ACTED — the reflector (cluster→git) IS the workload model (canonical for self)

> DECIDED 2026-09-14 (not reserve). The reflector gives a STRONGER determinism than canonical:
> canonical is deterministic *conditionally* (assumes reality never drifts from the declared roster —
> under any drift/scale, git reflects the INTENT, not reality); the reflector is deterministic
> *unconditionally* (git is reconciled FROM reality → git == reality → cold-start reproduces the actual
> cluster, even after scaling). Build ORDER: C4b greenfield first (the cluster must be born), then the
> reflector loop on top. Honest scope note: at bioskop-wrkld's 3 FIXED pets the reflected roster ==
> the canonical roster, so the write-back is functionally a no-op UNTIL we scale — but it is the model,
> not an option.
>
> **GRAVED (2026-09-14, specs/atlas):** the full design is now in
> `docs/architecture/cluster-api/cluster-seeding-controller.adoc` (§reflector — figures F1 round-trip,
> F3 precedence, F4 boundary; §recreate F5; §greenfield-arm) + the new atlas L1 view
> `docs/architecture/atlas/cluster-seeding.adoc` (Diagram W, avant→après), registered in
> `integration-atlas.adoc`. Carrier finalized = **`PoolReflection`** CR (NOT ConfigMap). Term "reflector"
> KEPT (qualified "cluster→git reflector" in the atlas to avoid the pipeline `ShapeReflector` clash).
> Below the ConfigMap wording is superseded by `PoolReflection`.

- **What we reflect = ONLY our intents** (`cluster.seedmatic.io` — `Cluster/PoolIntention`). The CAPI /
  CAPRKE2 / CAPN graph is NOT ours — never committed, always runtime-derived from the intent roster.
- **Git write-surface (minimal, name-based):** the controller commits ONLY (a) **a `PoolReflection` CR**
  (per pool, `cluster.seedmatic.io` — a DEDICATED typed CR, **NOT a ConfigMap** [decision 2026-09-14]:
  the ConfigMap was the untyped odd-one-out amid the typed CR family) — the observed-state annex of a
  `PoolIntention`, whose spec is the observed roster (`{name, pool}` per pet); and (b) **owner
  annotations** — the etcd `ownerReferences` translated to a UID-free, cold-start-durable form.
  `PoolReflection` completes a role TRIAD: `PoolIntention` (desired, Flux/git) · `PoolAdoption`
  (observed EPHEMERAL, etcd) · `PoolReflection` (observed DURABLE, reflector/git).
- **etcd↔git ownerRef translation (the controller is the bidirectional translator):**
  - etcd = native `ownerReferences` (UID, k8s GC works in-cluster);
  - git = annotation mirroring OwnerReference MINUS the UID:
    `cluster.seedmatic.io/owner-ref: {"apiVersion","kind","name","namespace"}`
    (namespace same-ns-implied by k8s ownerRef rule; explicit is fine / future cross-ns).
  - **export (cluster→git):** read the object (with its UID ownerRef) → strip the UID → write the
    name-based annotation → commit.
  - **reconcile (git→cluster), the inverse — a self-healing invariant EVERY loop:** the object comes
    back from Flux with the annotation, NO ownerRef (the old UID is dead); the controller reads the
    annotation, finds the owner by `{kind,name,namespace}`, and RE-STAMPS the native `ownerRef` (new
    UID) once the owner exists. ⇒ the **annotation is the source of truth of the relation; the ownerRef
    is a derived etcd projection** the controller re-maintains each reconcile. Exactly Velero's
    ownerRef-remap-on-restore pattern.
- **Precedence encoded by PRESENCE:** no annex ConfigMap (pre-adoption / day-0 greenfield) → seed from
  the Intention's canonical roster; annex present (post-adoption) → the controller PREFERS its observed
  roster. The object's existence IS the "observed is now authoritative" switch → the day-0→steady-state
  handoff is not a special rule, it's the presence of the annex. **The controller reads the roster as
  `annex-if-present ELSE Intention.spec.nodes` — build C4b through this lens so the reflector slots in
  without rework.**
- **No git merge — disjoint files:** the Intention file = seed-master-owned; the state-ConfigMap file =
  reflector-owned. Two committers, disjoint paths → git NEVER 3-way-merges. Both `fetch→apply→commit→
  push` with retry-on-conflict, NEVER force-push (the `image-automation-controller` loop — the blessed
  Flux precedent for cluster→git; git write token via the existing `github-token` App machinery). The
  precedence decision lives in the CONTROLLER (annex presence), not in git's merge semantics — which is
  where it belongs (reconciling desired-vs-observed = a reconciler's job).
- **Boundary — mgmt (self) is the PRINCIPLED exception, canonical ALWAYS:** maps onto the existing
  `STANDALONE` vs `IN_CLUSTER` Enclosure. mgmt = STANDALONE/self = the cluster the controller RUNS ON →
  it CANNOT reflect its own state during its own cold-start (at mgmt bootstrap the controller does not
  exist yet — chicken-and-egg). So mgmt's roster is canonical NOT because it is single-node, but because
  it is self (holds even if mgmt grew to 3 fixed nodes). Workloads = IN_CLUSTER/managed → their CRs live
  in the mgmt's etcd (survives a workload-only cold-start), the controller observes them → reflector-
  eligible. Symmetry: **STANDALONE = canonical (self can't reflect itself); IN_CLUSTER = reflector.**

### Design-close addenda (2026-09-14 session)

- **Recreate gestures + who resurrects (all end in greenfield, Intention present):**
  `delete clusters.cluster.x-k8s.io` → CAPI ordered teardown of the whole graph + CAPN destroys
  instances → OUR `ClusterAdoption` re-`ensure`s the Cluster from the still-present Intention →
  probe dead → greenfield → reborn. Self-serializes (a terminating Cluster with deletionTimestamp is
  seen by `ensure` and NOT recreated until fully finalized). Our `Adoption`/`Intention` CRs SURVIVE a
  Cluster delete (they OWN the Cluster, not vice-versa) — that is WHY it reborns. Wire it: add
  `Owns(&Cluster{})` (+ `Owns(&LXCCluster{})`) to `ClusterAdoptionReconciler.SetupWithManager` so a
  Cluster delete triggers a prompt re-ensure (today only the PoolAdoption watch does it indirectly).
  To DRIVE the annex mirror, `PoolAdoptionReconciler` should `Owns(&Machine{})` / watch `LXCMachine`
  so a presence change re-reflects. **The annex is a pure DERIVED cache with ZERO command semantics** —
  deleting it does nothing durable (next reflect re-writes it from observed reality; VMs untouched).
  The gestures that MEAN something: Intention presence (via `workloadTargets`) = manage-or-not;
  physical instance liveness (probe) = adopt-or-greenfield. Teardown CAUSES the annex to empty (reflector
  observes 0), never the reverse.
- **Annex-semantics FORK — decision deferred to when scaling opens (moot at 3 fixed pets, annex==Intention):**
  does the annex mirror raw LIVENESS or DESIRED MEMBERSHIP?
  - **reset-baseline** (mirror liveness): empties on any observed teardown → a delete-to-recreate of a
    SCALED cluster downsizes it back to the Intention seed (recreate = clean slate). Simple.
  - **restore-scaled** (mirror desired membership): prune ONLY on deliberate roster removal (Intention
    scale-down), NOT on transient teardown → a delete-to-recreate keeps the scaled set. This is what
    actually makes the reflector do its job (preserve scaling). LEAN = restore-scaled.
  NB an emergent asymmetry under reset-baseline: controller UP during teardown (delete-to-recreate) →
  empties → reset; controller DOWN (full-lab cold-start) → the git annex is untouched → restores scaled.
  Behavior depends on whether the controller witnessed the teardown — a reason to prefer restore-scaled
  (deterministic regardless of controller uptime).

Sequence: C1 (CRDs) → C2 (reconcilers) → C3 (render) → C6 (quick) → C5 (config-model) → C4 (execution)
→ C7 (NIC). Live deploy/re-grow = USER's. **DONE: C1, C2, C2-bis, C3, C6, C5, C4a, C4b. NEXT: C7 (+ the
reflector loop, deferred until dynamic scaling opens). Live re-grow of bioskop-wrkld validates C4b.**

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
