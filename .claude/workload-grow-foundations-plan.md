# Workload Grow — Foundations Plan

Working plan (evolving worklist) for laying **all** the foundations before the first workload
cluster (`bioskop-wrkld`) is greenfield-created. The settled *architecture* lives in `docs/`
(management-workload-topology, manifests-rendered-branches, the completion plan); this file is the
engineering backlog + resume point.

## Premise — settled model B (docs, commit `15af9c5e5`)

Workload CAPI CRs (`Cluster`/`LXCCluster`/`RKE2ControlPlane`/`RKE2ConfigTemplate`/`LXCMachineTemplate`/`MachineDeployment`)
live on `manifests/<host>-mgmt` — the branch the management cluster's own Flux tracks. Flux applies
them to the mgmt API; CAPI/CAPN reconcile. No imperative `kubectl apply`. The `-wrkld` branch = only
the workload's app stack (its own Flux). **The CRs live where CAPI runs.**

## Current-state baseline (verified 2026-09-07, file:line)

- **Addressing — ✅ DONE.** `ClusterNetworkBlueprint` handles `role=wrkld` (`bioskop-wrkld` = clusterId 1:
  pod `10.45/16`, svc `10.49/16`, VIP `10.80.15.10`, ASN 64513, mesh-id 2, LAN `192.168.1.144/28`). Builder derives every span.
- **Render — ❌ single-cluster.** Keyed by one `bootstrapIdentity().clusterName()`; no registry / no
  `workloadTargets` / no loop; one `synthOutdir` + one branch per run. (`ManifestsRunbookInput` `Identity`,
  `BootstrapIdentity.clusterSlug()`, `DefaultManifestSynthesisService`, `FluxRootManifestsUnit` BRANCH_PREFIX+slug.)
- **cluster-api units — ❌** render for the one current `clusterName` only (`ClusterApiDomainRegistrar`: 4 units).
- **kube-vip — ❌** VIP hardcoded `10.80.7.10` (`KubeVipManifestsUnit` daemonset env `address`), not from blueprint.
- **Dataplan — ❌ mono-cluster** `tank/rke2lab/control-nodes/<node>` (`DataplanLayout.canonical()`), no `<cluster>` dim.
- **Incus project — ✅ single `rke2lab` IS the design** (foundation 4 DROPPED). Instance names are
  already globally unique via the blueprint (`bioskop-mgmt-master`, `bioskop-wrkld-peer1`, …), and
  networks/images are shared regardless — per-cluster projects add complexity for marginal isolation.
  The operator sees all node instances in one project (the `<cluster>-<node>` naming was designed for it).
- **CAPN identity — per REMOTE, not per cluster** (foundation 5, rescoped). All clusters on a
  bare-metal share the one `rke2lab` project → one identity Secret per remote (`bioskop`, `nikopol`;
  `client-crt`/`client-key` shared, `server`/`server-crt` per remote — the handoff-contract shape),
  carrying `project: rke2lab`. The workload `LXCCluster.secretRef` names `<host>-incus-identity`.

## Foundations DAG

```mermaid
flowchart TD
  F2a["2a — workload-targets carrier<br/>(net-new · the foundation)"]
  F1["1 — CR-set units"]
  F6["6 — domain split by role"]
  F2b["2b — second render run (-wrkld app stack)"]
  KV["kube-vip — VIP from blueprint"]
  F3["3 — dataplan cluster dimension"]
  F5["5 — CAPN identity per REMOTE"]
  GREEN["greenfield bioskop-wrkld"]
  F2a --> F1 --> KV --> GREEN
  F2a --> F6 --> F2b -.-> GREEN
  F1 --> GREEN
  F3 --> GREEN
  F5 --> GREEN
```

Blue chain (2a→1→6→2b + kube-vip) = the render work. 3/5 = parallel plumbing, independent of 2a.
**Foundation 4 (Incus project per cluster) is DROPPED** — one `rke2lab` project suffices (see the
Incus-project baseline row + foundation 5).

## Foundation 2a — the workload-targets carrier — ✅ DONE (built + tested)

SHIPPED as the sub-facet design below. Carrier flows config → `Facets.workloadTargets` (wire) →
`ManifestSynthesisRequest.workloadTargets` → `ManifestSynthesisContext.workloadTargets()`. New
top-level `WorkloadTarget(host, role)` record (self-contained, no netplan dep; topology CANONICAL
derived at consumption). Coalesces to empty. Verified: `-Pnxmatic` package green + facet coalescing
test passes (`Tests run: 2`). Build note: **Claude builds in ITS lane `-Pclaude,all-worlds`** (→
`target~claude`), NOT `-Pnxmatic` (the user's lane / `target~nxmatic`, which collides with their
warm-up). bnd generates the bundle MANIFEST.MF (the default profile fails at maven-jar, no
MANIFEST.MF); foundation SNAPSHOTs are not in `~/.m2` so a subset `-pl … -am` needs `package` not
`test-compile`. **Build-cache trap:** a plain `package` can restore modules from cache and print
BUILD SUCCESS *without* recompiling edits (`Found cached build, restoring …`). To force a real
rebuild add `-Dmaven.build.cache.skipCache=true` (rebuilds AND rewrites the cache entry), NOT
`-Dmaven.build.cache.enabled=false` (bypasses without updating → cache left stale). See the
`maven-build-cache-force-rebuild-flag` memory.
Files: `WorkloadTarget.java` (new), `ManifestsRunbookInput.java` (Facets +4th sub-facet),
`ManifestSynthesisRequest.java` (slice+builder+toBuilder), `ManifestSynthesisContext.java` (accessor),
`ManifestSynthesisScenario.java` (threading + UPDATE/EDIT merge keeps seeded targets),
`ManifestsRunbookInputFacetsTest.java` (coalescing test). **NEXT = foundation 1 (CR-set units) consuming `ctx.workloadTargets()`.**

**Decision: a new sub-facet inside `Facets`** (the `PublishFacet` sibling pattern), NOT a new
cross-domain amendment role. Rationale (from the mechanism trace): the FACET amendment role is
mandatory + single-bearer (`AmendmentBinder.fieldFor` rejects >1 bearer; `requireMandatoryAmendments`
fails if unoffered), so a manifests-scoped carrier must ride inside the existing `manifests` FACET as a
sub-facet coalesced in the `Facets` compact ctor — exactly like `publish`/`debug`/`delivery`. A new
`Amendment.*` role + its own contributor/sow (the IDENTITY pattern) is only for a *cross-domain*
carrier sown per-consult by another scion; workload targets are ambient config, so they don't need it.

**Chain to build (mirrors the `publish` facet end-to-end):**

1. **Config** — `rke2lab:manifests:workloadTargets:` (a list of `{ host, role: wrkld, topology }`).
   Rides the existing verbatim contribution: `Rke2labConfig.ManifestsConfig.rest` (`@JsonAnySetter`) →
   `facetJson()` → `Main.java` `.facet("manifests", …)` → `FacetContributor(AmendCoordinate("manifests"))`.
   **No new host wiring** if it lives under `rke2lab:manifests:`.
2. **Wire record** — add `WorkloadTargetsFacet` (a `@SeedContract` sub-record: `List<Target>`, each
   `Target(String host, String role, Topology topology)`) as a new component of `Facets` in
   `ManifestsRunbookInput`; coalesce to empty in the `Facets` compact ctor (the null→default line).
   Plain JSON record → crosses the membrane safely.
3. **Membrane** — unchanged: `ManifestsAmendReflector` + `AmendmentBinder` bind the `manifests` FACET
   onto `ManifestsRunbookInput.facets`; `ManifestsRunbookHandler.seedFrom` decodes.
4. **Scenario → request** — in `ManifestSynthesisScenario.When` (near the `publish`/identity threading,
   ~lines 638/665/681-713), read `facet.facets().workloadTargets()` and set it on
   `ManifestSynthesisRequest.Builder` (+ add the slice to the request record).
5. **Context accessor** — add `ManifestSynthesisContext.workloadTargets()` delegating to
   `request.workloadTargets()` (mirror `componentVersions()` at ctx line 124-126).
6. **Unit read** — the (new, foundation 1) cluster-api CR units read
   `ManifestSynthesisContext.current().workloadTargets()`; per target derive its blueprint via
   `ClusterNetworkBlueprint.builder().cluster(target.host()+"-"+target.role()).node(n).deriveRecipeModel().build()`.

**Invariant (❌ anti-pattern):** keep `bootstrapIdentity().clusterName()` = the MGMT cluster (branch
owner, render subject). Do NOT overload it, and do NOT spin a second full `BootstrapIdentity`, to "be"
a target. The targets are a set beside the identity; only the cluster-api units consume it.

**Open sub-question:** does `Target.topology` reuse `ClusterNetworkBlueprint.ClusterTopology`
(CANONICAL 1+3+2) or a smaller shape? Default: reuse CANONICAL; workloads are HA (master+peer1+peer2).

## Remaining foundations (intent)

- **1 — CR-set unit** — ✅ DONE (`24faa4044`). `ClusterApiWorkloadManifestsUnit` (registered in
  `ClusterApiDomainRegistrar`, dependsOn `cluster-api/operator`) loops `ctx.workloadTargets()` and,
  per target, derives the blueprint + reads `ctx.imageState()` to render the full CR set into
  `rke2lab-<cluster>`: `Cluster` (v1beta2, clusterNetwork pod/svc CIDRs +
  controlPlaneEndpoint=VIP), `LXCCluster` (infra v1alpha2, `secretRef <cluster>-incus-identity`,
  `loadBalancer.kubeVIP`), `RKE2ControlPlane` (controlplane v1beta2, replicas **3** = master+peer1+peer2,
  `registrationMethod=address` on the VIP, kube-vip bootstrap via preRKE2Commands+files, version
  `v`+`rke2Version()`), control-plane + worker `LXCMachineTemplate` (instanceType container, privileged
  config mirroring `InstanceGrow`, `image.fingerprint`), `MachineDeployment` (v1beta2, **replicas 0** —
  workers = 2.C), `RKE2ConfigTemplate` (bootstrap v1beta2). No-op if no targets or no ImageState. CRD
  shapes verified vs pinned upstreams (CAPN incus v0.9.0 v1alpha2 + kube-vip template; CAPRKE2 v0.25.2
  v1beta2). **To actually render:** set `rke2lab:manifests:workloadTargets` config (e.g. `[{host: bioskop,
  role: wrkld}]`) config (done in `Pulumi.dev.yaml`) — the 2a carrier rides it. `LXCCluster.secretRef`
  is per-REMOTE (`<host>-incus-identity`, `project: rke2lab`) — single project, foundation 4 dropped.
  Open reconciliations deferred by design: per-remote CAPN identity Secret (foundation 5), rke2
  config-ownership CAPRKE2-vs-node-base (validated at first provisioning). **1c only renders at a GROW
  (needs ImageState); an in-cluster UPDATE render replays it — see 1d.**
  - **NO `spec.paused` (`501e5fb08`) — paused is incompatible with Flux, verified vs CAPI v1.14.0.**
    A paused Cluster (a) can't be deleted (Reconcile returns on the paused check *before*
    `reconcileDelete` → the `cluster.cluster.x-k8s.io` finalizer never clears → a Flux prune wedges the
    namespace `Terminating`, observed live), and (b) never advances `observedGeneration`/`Ready` →
    kstatus InProgress forever → Flux `wait:true` wedges. So the CRs render UN-paused: **presence in
    `workloadTargets` IS the provisioning trigger** (add when ready → CAPI greenfield-creates; remove →
    Flux prunes + CAPI cleans up). Plus **`wait:false`** on any cell rendering a `cluster.x-k8s.io/Cluster`
    (CAPI provisioning is long+async). This SUPERSEDES the completion-plan's "spec.paused until host up".
    A stuck-Terminating namespace from a prior paused prune needs a manual finalizer patch to clear.
- **1b — rke2 version from nix** (the RKE2ControlPlane.spec.version source; option A = image state):
  - Layer 1 ✅ DONE: `build-node-base-image.sh` now `nix eval`s
    `nixosConfigurations.rke2-node-base.config.services.rke2.package.version` from the SAME staged tree
    the artifacts build from, and emits `rke2.version` beside `incus.tar.xz`/`rootfs.squashfs` (a true
    image property, from nix — not hand-pinned; `1.34.8+rke2r2` today). Freshness gate rebuilds if the
    file is missing. Changes `BuildRecipe.digest` (one cache-invalidating rebuild, expected).
  - Layer 2 ✅ DONE: the dormant `ImageState` carrier is revived end-to-end.
    - Receiving end: new `Amendment.IMAGE_STATE` role + `@Amendment(IMAGE_STATE) Optional<ImageState> image`
      on `ManifestsRunbookInput` (reuses the profile record); `ManifestSynthesisScenario` maps
      `facet.image() → builder.imageState(...)`; `ImageState` gains `rke2Version`; ConfigMap emits it.
    - Producer (incus scion): `IncusProvisionScenario.the_manifests_are_cultivated` assembles the
      `IMAGE_STATE` JSON via the SAME `GrowPlanAssembler` the THEN seals the plan with
      (`imageView()` now public → alias/paths/buildChecksum) + `SplitImageFingerprint.of(...)` +
      reading the nix-emitted `rke2.version` beside the artifacts; remoteAddress derived
      `https://<host>-nixos:8443`. Empty on a survey (no artifacts) → units no-op.
    - Note: `getImagePlain` (GetImageResult) exposes NO custom `properties`, so the round-trip is
      nix→artifact→scion, never a getImagePlain property read.
    - 1c ✅ DONE: the CR-set unit reads `ctx.imageState()` (fingerprint→LXCMachineTemplate image,
      rke2Version→RKE2ControlPlane version) + `ctx.workloadTargets()` (per-target blueprint).
- **1d — ImageState follows the grow** ✅ DONE. The incus scion sows `IMAGE_STATE` ONLY at a grow, so
  a steady-state **in-cluster UPDATE render was ImageState-blind** → it rendered the image-pinned CR set
  (and the `image-state` ConfigMap) EMPTY and force-pushed → stripped the grow-rendered CRs off the
  branch. Fix (extends `render-facet-follows-grow`): `ManifestSynthesisScenario.recordRenderFacet` now
  records `image: <ImageState>` in the branch-root `manifest.yaml` (via `YAML_MAPPER` + `Jdk8Module` for
  the `Optional`), and `resolveFacet` REPLAYS the recorded HEAD image into the effective input when the
  seeded one is empty (UPDATE/EDIT verbs). Fixpoint (update re-records identically); backward-compatible
  (a branch with no `image` key → empty → graceful). Validate: grow (writes image + CRs) → in-cluster
  update (replays image, re-renders CRs, no strip).
- **6 — domain split by role** — ✅ DONE (`f51ba1d10`). The render's domain set is now a FUNCTION of
  the cluster ROLE, not a config-toggled facet. New `ClusterRole` (mgmt/wrkld, parsed from the
  `<host>-<role>` clusterName) owns the role→`ManifestDomainPolicy` mapping — the single canonical
  domain-set the synthesis already consumes; `ManifestSynthesisScenario` derives the policy from
  `ClusterRole.of(clusterName)`. **`PublishFacet` REMOVED entirely** (record + `Facets.publish` +
  builder + coalesce + `rke2lab:manifests:publish:` config + CLI `publish.*` edit overrides + the
  dead `RKE2LAB_MANIFESTS_PUBLISH_*` env javadocs — the domain policy was its only live consumer, the
  publish-env contributor was already gone). `withPublishDebug`→`withDebug`; `FACET_READER` tolerates
  unknown keys so a branch with a stale `publish:` sub-map still decodes. Tests green (Facets 2/2,
  ShapeReflector 4/4), full chain BUILD SUCCESS (claude lane).
  - **Why role, not a facet — the concept we'd missed:** the `PublishFacet` conflated *structural
    membership* (which domains a cluster's role IS made of) with an *operator toggle*. That worked
    while there was ONE cluster (its config booleans WERE the role's set, hand-baked); a 2nd role
    makes them contradict. The domain set is STRUCTURAL to the role — a mgmt cluster does not "turn
    off cluster-api" as an op. `ManifestDomainPolicy` was already "the canonical domain-set shared by
    synthesis + activation", so role→policy directly (no PublishFacet intermediate) is the clean end.
  - **Sets shipped:** mgmt = cluster, runtime, platform, gitops, **cluster-api**, networking, storage,
    high-availability. wrkld = cluster, runtime, platform, gitops, networking, storage,
    high-availability, **mesh**, **cicd**. Role-exclusive: cluster-api (mgmt), mesh+cicd (wrkld). Easy
    to tune (one place: `ClusterRole.enabledDomainIds`).
  - **ingress deferred to a follow-up** (see the ingress-domain entry below) — mesh stays wrkld-only
    for now; mgmt gets no mesh/ingress this step (fine for the cold-start: the mgmt render is at-grow,
    no webhook needed yet).
- **ingress domain extraction** — ✅ DONE (`97160ad8f`). New `ingress` domain (IngressDomainRegistrar:
  `mesh-system` namespace + Tailscale operator + funnel cert-restore/state-persistence/tailnet-purge),
  STRUCTURAL on both roles; `mesh` = Headscale+Headplane, `dependsOn` ingress, WRKLD-only; `ClusterRole`
  adds ingress + cicd to both roles. 5 units keep `units/mesh` package + shared `MeshRefs`, k8s namespace
  name kept `mesh-system` (rename would relocate the funnel cert + Headscale state) — tidy follow-ups.
  planner tailscale.com cell → `ingress/tailscale`. Graph resolves, BUILD SUCCESS, registrar-table doc
  updated. **Remaining:** the PaC webhook rewiring (below). Original design notes: **Finding:** `TailscaleManifestsUnit` is NOT the node-plane client — its
  javadoc is "Tailscale operator connector + oauth"; it provisions the **funnel** proxies that expose
  services PUBLICLY (`PacWebhookManifestsUnit` cicd, `FluxReceiverManifestsUnit` gitops). Its own code
  says "Funnel … NOT the self-hosted headscale mesh (which has no funnel)". So `mesh` conflates TWO
  concerns (same PublishFacet-style smell, at the taxonomy level):
  - **mesh** = Headscale/Headplane — the self-hosted workload-plane control SERVICE. Stateful
    (SQLite, `replicas:1`, `Recreate` — single-writer, verified NOT multi-replica-capable), always-live
    → **wrkld-only** (hosted on the always-live `bioskop-wrkld`, never the on-demand mgmt).
  - **ingress** = tailscale operator + `FunnelCertRestore` + `FunnelStatePersistence` + `TailnetPurge`
    — a public-door CAPABILITY (not a shared service). Each cluster provisions its OWN doors →
    **STRUCTURAL, base, BOTH roles** (like cluster/runtime/platform). User's call: the mgmt needs
    webhooks / to expose its endpoints, structurally. The funnel state-persistence (stable FQDN across
    restarts) + tailnet-purge (stale-device GC) are PRECISELY what makes ingress viable on an
    on-demand/recreate cluster — so ingress-on-mgmt is the nominal case, not an entorse.
  - Node-plane Tailscale (`mammoth-skate`, every node a member) = baked in NixOS node-base, NOT a
    manifest. The mgmt reaches workloads over it at bootstrap, independent of Headscale — dissolves the
    chicken-and-egg.
  - Consumers keep their funnel `Ingress` objects (FluxReceiver, PacWebhook) and `dependsOn` the
    ingress domain (as PacWebhook already dependsOn FunnelStatePersistence). Name = `ingress` (a
    `manifests.ingress` package already exists). **OPEN TRAP (Q4):** the tailscale operator + headscale
    share the `mesh-system` namespace (`MeshSystemNamespaceManifestsUnit`, FOUNDATION layer). Splitting
    needs its own `ingress-system`/`tailscale-system` namespace or a shared-namespace owner without an
    inter-domain cycle — resolve as you code. Then `ClusterRole` adds `ingress` to BOTH role sets.
- **CONVERGED domain/role model (brainstorm 2026-09-08) — only TWO role-exclusive domains.** After the
  ingress + cicd findings the target is: **structural base (BOTH roles)** = cluster, runtime, platform,
  gitops, networking, storage, high-availability, **ingress** (funnel capability), **cicd** (Tekton
  render + its webhook). **Role-exclusive** = **cluster-api** (mgmt — CAPI runs there), **mesh**
  (wrkld — the always-live Headscale/Headplane control service). Everything else is shared: the role
  differs ONLY on *who reconciles clusters* and *who hosts the mesh control-plane*.
  - **cicd is structural (both roles), NOT wrkld-only** (my `f51ba1d10` split was wrong on this):
    each cluster runs its OWN in-cluster manifests-render Tekton pipeline (the mgmt ALREADY has at
    least the manifests-render pipeline). So `ClusterRole` must add cicd (+ ingress) to BOTH sets.
- **★ mesh→ingress path drift — ROOT CAUSE FOUND + FIXED (`0017d8658`, 2026-09-09).** NOT a clean-tree
  bug — the clean-tree render was never at fault. `RenderedBranch.prepare`/`GitCli.worktreeAdd` DOES
  empty the worktree each render (`git rm -rf .`) and `stageAll` (`git add -A`) stages deletions, so a
  dropped path IS pruned. The REAL cause: the mesh→ingress move (`ff95fe7ad`) was INCOMPLETE — it
  shifted the Java package, the domain registrar and the k8s namespace, but the five moved units kept
  stamping `PackageMetadataProfile("mesh", …)`. That first arg is the `io.seedmatic.rke2lab/domain`
  annotation the exploder turns into the `<layer>/<domain>/<package>` output path
  (`DefaultManifestExplodeService:100-109`) and `FluxServiceKustomizationPlanner` turns into a
  `flux/<domain>/*` cell (`Cell(layer,domain,pkg)` from the tree dirs). So every render emitted a FULL
  `mesh/` tree IN PARALLEL with the ingress-domain Kustomizations (split brain). On bioskop-mgmt the
  stale `flux/mesh/mesh-tailscale` applied the operator UNGATED (the tailnet-purge gate lives on the
  ingress cells) → funnel proxies drifted to a `-N` MagicDNS suffix. **Fix:** relabel the domain
  "mesh"→"ingress" on all five units (+ one stale comment). The `git ls-tree` "both mesh AND ingress"
  and the tip commit's `M`/`D`/`A` mesh churn were the tell: mesh files were being RE-RENDERED each
  grow (M), not carried over — exactly what an incomplete rename + working clean-tree produces. Build
  SUCCESS (`-Pclaude,all-worlds`, skipCache). NOTE: `PURGE_ENV="mesh/tailnet"` is a FloxEnv reference
  (`FloxEnvFolder.MESH`, runtime domain, structural on BOTH roles — that's why the purge ran on mgmt),
  NOT an output path; left as-is (renaming the flox folder is a separate runtime-domain concern).
  Live cleanup of the already-drifted branch (optional, the next grow's clean tree does it anyway):
  `git rm -r workloads/mesh flux/mesh flux/mesh.yml operators/mesh foundation/mesh` on
  `manifests/bioskop-mgmt` + push. NEXT: fresh cold-start to validate.
- **★ ingress → RENOMMÉ `tailscale` + mesh a son namespace (`50d6f76e1`, 2026-09-09).** Décision user (brainstorm) : l'opérateur Tailscale > ingress/funnel (il porte aussi le Connector subnet-router + l'inscription tailnet ; funnel = 1 capacité), et « ingress » collisionne avec EnvoyGateway (l'ingress in-cluster réel). Rename complet domaine `ingress`→`tailscale` (catalog, registrar, package `units.tailscale`, `TailscaleRefs`, namespace dédié `tailscale-system`, les 5 `PackageMetadataProfile`), + **mesh récupère son `mesh-system`** (`MeshSystemNamespaceManifestsUnit`, drop dependsOn tailscale) → dissout la dette de partage. Graphe résout, BUILD SUCCESS. **⚠️ FOLLOW-UP `.secrets` (sops, user) :** `replicateTo` `ingress-system` → `tailscale-system` (operator-oauth, tailnet-purge-oauth, floxhub-token pour le Job purge) + `mesh-system` (Headscale/Headplane). Supersède le change mesh→ingress précédent non-committé.
- **PaC/Tekton webhook rewiring** (chantier cicd, IN PROGRESS — chunk 1 shipped `033b7fc2e`).
  **Chunk 1 DONE (ghapp repo-webhook capability):** `TokenScope.REPO_ADMIN` (administration:write) +
  minter mapping; `RepoWebhookConfig` + `GithubRepoWebhookConfigurer` contract +
  `GithubRepoWebhookConfigurerEdge` (mints REPO_ADMIN token, idempotent GET/POST/PATCH
  `/repos/{repo}/hooks` keyed by url, fail-fast, gardening-gated); `GithubAppCli` registration
  pre-fills `administration=write` (operator GRANTED it on the App). App-webhook path untouched.
  **Chunk 2 TODO (wiring — rewires the grow, do fresh):**
  1. `PacWebhookFunnel` per-cluster: add cluster → leaf `pipelines-webhook-<cluster>` (LEAF is used
     by BOTH `PacWebhookManifestsUnit` (Ingress tls host, has `bootstrapIdentity().clusterName()`)
     and the host (`ClusterSeedScenario`/`GithubAppCli` via `url()`) — they must compute the SAME
     per-cluster leaf).
  2. Rewire the `ghapp-webhook` scion (`GithubAppWebhookScenario`) to reconcile a REPO webhook via
     `GithubRepoWebhookConfigurer` + `RepoWebhookConfig(repo="seedmatic/rke2lab", per-cluster-url,
     secret from .secrets github.webhook.secret, events=PaC set)` — instead of the App webhook.
     `WebhookReconcileInput` gains repo/events (or the scenario hardcodes them).
  3. `ClusterSeedScenario.the_github_app_webhook_is_reconciled` sows the per-cluster funnel url.
  4. RETIRE the App-webhook path (no-dead-code): delete `GithubAppWebhookConfigurer` + its edge +
     the App webhook_url/webhook_active from `GithubAppCli` registration (PaC no longer uses it).
     Each cluster's grow reconciles ITS repo webhook on the shared repo (N repo webhooks OK).
  6. **Generalise funnel-state PERSISTENCE beyond PaC (root-cause fix, found live 2026-09-08).** The
     Flux funnel (`FluxReceiverManifestsUnit`) sets only `tailscale.com/funnel:true`, NO
     `tailscale.com/proxy-class` — so it has NO persisted tailscale identity. Each grow its proxy pod
     re-registers a NEW device → the old `flux-webhook` device lingers → MagicDNS drifts
     `flux-webhook-1…-4` (observed live: `tailscale funnel status` on `ts-flux-webhook-*` shows
     `flux-webhook-4.mammoth-skate.ts.net`, while the repo webhook points at `flux-webhook` → "failed
     to connect to host"). PaC does NOT drift because it HAS the ProxyClass.
     `FunnelStatePersistenceManifestsUnit` is hardcoded PaC-only (`STATE_SECRET`/`PROXY_CLASS` =
     `PacWebhookFunnel.LEAF`). Fix: PARAMETERISE it per funnel — each funnel (flux, pac) × cluster gets
     its own ProxyClass + `ts-<leaf>-state` secret + persist volume + backup/restore. Each grow the
     drift silently burned LE prod certs (every `flux-webhook-N` is a distinct FQDN = a distinct cert
     vs mammoth-skate.ts.net's 50/week) — the persisted certs were PaC-only, never Flux.
  5. **`funnelCertStaging` SSOT (user requirement):** on staging the funnel certs are self-signed →
     GitHub's webhook TLS handshake fails UNLESS the webhook is `insecure_ssl: 1`. So ONE config flag
     `funnelCertStaging` (default false) must drive BOTH: (a) `TailscaleManifestsUnit`
     `useLetsEncryptStagingEnvironment` (via the manifests facet → context, remove the hardcoded
     `false`), and (b) the repo webhook's `insecure_ssl` (1 when staging, 0 when prod — add
     `insecureSsl` to `RepoWebhookConfig` + the edge; the host sows it from the same config). Flip to
     true for the rewire shakeout, back to false before the real grow. Two flags that must agree =
     one source (SSOT discipline). Today `PacWebhookManifestsUnit`
  uses the SINGULAR **GitHub App webhook** (its javadoc: "so GitHub's App webhook can reach it",
  validates "the App webhook's signature" against `pipelines-as-code-secret`), while Flux uses a
  per-repo `Receiver` (`type: github`, own `github.webhook.secret`). A GitHub App has ONE webhook URL;
  a repo has MANY. So the App webhook can't fan out to two clusters' PaC controllers — a mono-cluster
  assumption. Fix: PaC on a **per-repo webhook + own HMAC secret + own funnel** (mirror Flux); the
  GitHub App keeps ONLY the auth role (token mint, per-cluster). Each cluster: Flux receiver funnel +
  PaC webhook funnel, both `dependsOn` the ingress domain.
- **2b — second render run** — produce `manifests/<host>-wrkld` by running the render a SECOND time
  with `role=wrkld`. The mgmt render (role=mgmt) already produces `-mgmt` (+ the workload CRs on it via
  1c). 2b is the twin invocation for the workload's own branch: `manifests publish cluster=<host>-wrkld`
  (or the grow loops over {mgmt, each workload}). It renders the role=wrkld domain set (foundation 6) →
  the workload's app stack on `-wrkld`. The workload's own Flux (installed via the wrkld gitops domain)
  then pulls `-wrkld` autonomously (Tier 1). Depends on 6 (need the role-split to know WHAT to render
  for wrkld). Open: who triggers the initial `-wrkld` render — the operator (a second publish), or the
  workload's own in-cluster render once it's up (mirroring the mgmt in-cluster render model)? The
  config already frames "two clusters = two runs", so the mechanism is a second render invocation.
- **3 — dataplan** `<cluster>` dimension (+ ndh `catalog.datasets`, `zfs-disko-config`, `zpool-init`).
  **⏸ EN PAUSE (pivot ProxyGroup 2026-09-09).** Forks TRANCHÉS : (1) producteur = **workloadTargets dans le
  facet dataplan** (le grow mgmt émet l'union {mgmt + workloadTargets} par cluster ; miroir de foundation 1) ;
  (2) persist = **tout par-cluster** (funnel-cert ET maven-cache — le repo local Maven n'est pas
  concurrency-safe, un cache partagé se corromprait avec des builds simultanés). Design : `DataplanLayout`
  paths `tank/rke2lab/<cluster>/{control-nodes/<node>,persist/*}` ; consommateurs (OpenebsZfs, FunnelCertRestore)
  passent `clusterName()` ; producteur `DataplanScenario` émet l'union. **Scoping restant à confirmer À LA REPRISE :**
  `NetplanRunbookInput`+`DataplanRunbookInput` ne portent QUE le SOIL (pas d'identité/cluster) — or netplan EST
  cluster-aware ⇒ trouver COMMENT NetplanScenario obtient son cluster aujourd'hui (le pattern à mirrorer pour
  injecter le set de clusters dans dataplan). ndh probablement transparent (dataplan.json = liste plate de datasets,
  chemins plus profonds OK tant que les parents `rke2lab/<cluster>[/control-nodes|/persist]` sont émis).
- **4 — Incus project per cluster** — ❌ DROPPED (2026-09-07). One `rke2lab` project suffices: instance
  names are globally unique via the blueprint, networks/images are shared regardless, and the operator
  wants all nodes in one project (the naming was designed for it). Marginal isolation not worth the cost.
- **5 — CAPN identity per REMOTE** — ✅ DONE. `ClusterApiWorkloadManifestsUnit` renders
  `<host>-incus-identity` (data `server`/`server-crt`/`client-crt`/`client-key` + `project: rke2lab`)
  into the workload namespace on the **NODE_BOOTSTRAP lane** (a credential — seeded node-side at the
  grow, never committed to the branch, survives secret-blind in-cluster renders), with a
  node-bootstrap copy of the namespace so the set is self-contained (twin of the CAPI kubeconfig
  Secret). Rendered only when the material is revealed (a grow). The dead capn-system
  `IncusIdentitySecretManifestsUnit` (per-cluster, unconsumed) is DELETED. Deferred: per-host material
  for a DIFFERENT remote (nikopol) — today the one revealed identity is bioskop's, shared by
  bioskop-wrkld.
- **kube-vip** — ✅ DONE (1a): VIP now read from `NetworkTopology.vipHostInetAddr()` (blueprint-derived,
  per-cluster: mgmt 10.80.7.10 / bioskop-wrkld 10.80.15.10), no more hardcode. Per user directive
  "automate like the other netplan addresses". Still open: settle unit-vs-`LXCCluster.spec.loadBalancer`
  when 1c wires the workload endpoint.

## Deferred (NOT blocking the first workload)

Cluster PKI in git (sops) · full per-remote CAPN identity (nikopol) · hygiene (DNS >3 nameservers,
`rke2lab-*-manifests.service` ordering, snapshot-CRD `disable:`).

## Sequence

2a → 1 → 6 → 2b (+ kube-vip); 3/4/5 in parallel with 1; live checks; greenfield `bioskop-wrkld`.

## Commits

- `15af9c5e5` docs: reconcile Phase 2.B mechanism (model B).
- (this plan relocated out of docs/ into `.claude/` per the plans convention.)
