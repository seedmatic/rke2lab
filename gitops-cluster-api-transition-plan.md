---
name: gitops-cluster-api-transition-plan
description: "Design record (2026-06, status NOT yet decided — future memory-cleanup settles it): the peer1-and-beyond provisioning plan via a seed-peers module + GitOps + CAPI/CAPN/CAPRKE2 + Tekton-reactive drift-correction. Phase 1 (master node, declarative CAPI install) is COMPLETE and lives as-built in cluster-api-bootstrap-requirements.adoc. Phase 2+ (seed-peers synth, Flow A/B, Tekton) is superseded-or-reorganized by vcluster-implementation-plan.adoc — the contradiction ('still valid' vs 'superseded') is unresolved on purpose. Re-homed from docs/ (docs holds only .adoc); the seed-peers module it describes does not exist yet, so no co-located home exists."
metadata:
  node_type: memory
  type: project
---

# Plan: peer1 (and beyond) via seed-peers + GitOps + CAPI/CAPN/CAPRKE2 + Tekton-reactive

## Context

Today rke2lab provisions a single seed control-plane node (`master`) via Pulumi at `pulumi up` time on a dev machine. Module name `seed-master/` reflects "bootstrap" but is misleading — it's specifically about master.

Goal: bring up peer1 (and later peer2, peer3, worker1, worker2 — the canonical netplan topology) under a **type-safe, GitOps-with-build-step + Tekton-reactive** loop. Java code is source of truth; Tekton synthesizes the gitops/ tree from Java; Flux applies the synthesized YAMLs.

End state: edit `IncusResourceBootstrap.java` → push to GitHub → Tekton (re-)synthesizes the gitops/ tree, commits the diff, Flux reconciles, peers re-roll on cloud-init drift.

## Locked decisions

- **Module rename**: `seed-master/` → `seed-master/` (Pulumi-driven bootstrap of master; unchanged role).
- **New module**: `seed-peers/` — cdk8s-driven synth of peer-related CRs. Maven dependency on `seed-master/` for `HostStage.materializeAssets()` reuse, with **Pulumi SDK explicitly excluded** via Maven `<exclusions>`. Ensures seed-peers' classpath cannot accidentally invoke Pulumi.
- **Two flows for gitops/ updates** (both share the same `mvn -pl :seed-peers generate-resources` build):
  - **Flow A — operator-driven, intentional**: Java/synth changes → operator runs `mvn -pl :seed-peers generate-resources` locally → reviews diff → opens PR with the change + synthesized gitops/ diff → reviews in GitHub UI → merges to main → Flux applies.
  - **Flow B — Tekton drift-correction, reactive**: Tekton runs when commits to main touch `seed-master/`, `seed-peers/`, or `manifests/` (NOT when they touch only `gitops/` — that's Flux's job). Tekton synthesizes onto a feature branch `tekton/drift-<sha>`, applies, and on success opens a PR back to main. On apply failure: leaves the feature branch + PipelineRun evidence; no PR. **All merges to main happen via PR review**, whether operator-initiated or Tekton-initiated.
- **GitOps controller**: Flux Operator (already pinned + authored as `FluxInstanceLayer.java`). `GitRepository.spec.ref.branch=main`.
- **Trigger**: GitHub webhook → Tekton EventListener (public via Tailscale Funnel `tailscale.com/expose=true` — same pattern HeadscaleLayer uses).
- **Peer enumeration**: hardcoded canonical netplan topology (peer1/peer2/peer3/worker1/worker2). GitOps controls instantiation via `KThreesControlPlane.spec.replicas` and `MachineDeployment.spec.replicas`.
- **MachineTemplate granularity**: one `LXCMachineTemplate` per node, each referencing its per-node cloud-init Secret.
- **Cloud-init Secret encryption**: **SOPS** with cluster-side age key. Flux's SOPS decryption at apply time. All synthesized YAMLs (including cloud-init Secrets) committed.
- **Image distribution**: Maven `jib-maven-plugin` builds the seed-peers JAR-in-image, pushes to GHCR (`ghcr.io/seedmatic/seed-peers`).
- **Tekton credentials**: GitHub App with branch + PR-creation permissions on the repo (not just push-to-main). PR creation via `gh pr create` from a Task step.
- **Porch fate**: kept as-is during all three phases. Migration to plain Flux Kustomizations + HelmReleases is a separate later effort.

## Audit-derived facts (key constraints)

- `IncusResourceBootstrap.HostStage.materializeAssets()` and per-target `materialize()` methods are **already pure** — no Pulumi calls. Consume `BootstrapConfig` + `ControlplanePolicy` + `BootstrapPaths`.
- `BootstrapConfig.nodeName` is fully configurable. `ClusterNetworkBlueprint` already maps each canonical node name → MAC + IP.
- `RuntimeCloudConfigLayer` (in `manifests/`) hardcodes `bioskop-master` — must be templated by `(clusterName, nodeName)`.
- `ComponentVersions.capi{IncusProvider,Rke2Provider}` and `clusterctl` pinned but unconsumed today.
- `FluxInstanceLayer` authors `FluxInstance` already; `TektonPipelinesLayer` authors only the operator (no Tasks/Pipelines/Triggers yet).
- Two dormant systemd units (`rke2lab-cluster-api-install`, `rke2lab-capn-provider-install`) install via clusterctl imperatively. Will be replaced and deleted.
- No code currently extracts Incus client cert/key from `~/.config/incus`. Required for CAPN-in-cluster.
- No container image build path exists in the repo. Added in Phase 3 (for the seed-peers JVM image).
- **`control-node` Incus image is already a Stage A output**: master's bootstrap pipeline (`imageProvider.ensureSeedImageFingerprint`) builds it via distrobuilder on bioskop-nixos and publishes it into the `rke2lab` Incus project. The fingerprint is exported as `seedImageFingerprint`. CAPN/peers reuse this — no separate image build for peers.

## Architecture

```text
                                       repo: github.com/nxmatic/rke2lab
                     ┌────────────────────────────────────────────────────┐
                     │ seed-master/         (Pulumi, runs on dev machine) │
                     │   bootstraps master                                │
                     │                                                    │
                     │ seed-peers/          (cdk8s, synth-only)           │
                     │   reads master state + emits gitops/               │
                     │                                                    │
                     │ manifests/           (cdk8s, in-cluster workloads) │
                     │                                                    │
                     │ gitops/              (Tekton/operator-committed)   │
                     │   clusters/bioskop/                                │
                     │     cluster.yaml          (Cluster + LXCCluster)   │
                     │     controlplane.yaml     (KThreesControlPlane)    │
                     │     peers/                                         │
                     │       peer1-template.yaml (LXCMachineTemplate)     │
                     │       peer1-cloud-init.sops.yaml (SOPS Secret)     │
                     │       peer2-...                                    │
                     │     workers/...                                    │
                     │     kustomization.yaml                             │
                     │   flux-system/                                     │
                     │     gitrepository.yaml, kustomization.yaml         │
                     └────────────────────────────────────────────────────┘
                                          │
                                          │  Two flows, both end in main:
                                          │  ─── Flow A (operator-driven) ───
                                          │    operator: mvn synth + git PR + review + merge
                                          │  ─── Flow B (Tekton drift) ─────
                                          ▼
                  ┌───────────────────────────────────────────────────────┐
                  │ GitHub webhook  (push to main, not gitops/-only)      │
                  └────────────────────────┬──────────────────────────────┘
                                           │ (1) Tekton EventListener
                                           ▼
                  ┌───────────────────────────────────────────────────────┐
                  │ Tekton Pipeline (in-cluster)                          │
                  │  Task: clone-repo                                     │
                  │  Task: synth-seed-peers (image: seed-peers:latest)    │
                  │    runs `mvn -pl :seed-peers generate-resources`      │
                  │    → /workspace/gitops/                               │
                  │  Task: sops-encrypt-secrets                           │
                  │  Task: branch-and-commit                              │
                  │    git checkout -b tekton/drift-<sha>                 │
                  │    if no diff vs main: skip, exit 0                   │
                  │    else: commit + push branch                         │
                  │  Task: try-apply (server-side dry-run + real apply)   │
                  │    if success: gh pr create tekton/drift-<sha> → main │
                  │    if failure: leave branch, fail PipelineRun         │
                  └────────────────────────┬──────────────────────────────┘
                                           │ (2) operator reviews PR + merges
                                           ▼
                  ┌───────────────────────────────────────────────────────┐
                  │ Flux GitRepository reconciliation (watches main)      │
                  │  Kustomization applies clusters/bioskop/              │
                  │  SOPS controller decrypts cloud-init Secrets at apply │
                  └────────────────────────┬──────────────────────────────┘
                                           │ (3) CAPI/CAPN/CAPRKE2 reconcile
                                           ▼
                                 LXC instances on bioskop-nixos
```

## Phasing

Three phases. Each independently testable; later phases reuse earlier work.

### Phase 1 — declarative install of the framework on master

Replaces the two clusterctl-driven shell-script systemd units with cdk8s manifest-layer authoring. End state: master comes up clean with CAPI core + CAPN + CAPRKE2 + Flux Operator + FluxInstance + Tekton Operator all installed via the existing `rke2lab-cluster-manifests.service` manifests-applier flow. Plus Flux is bootstrapped to start watching `gitops/clusters/bioskop/` as soon as master is up.

**What stays**:
- `rke2lab-cluster-manifests.service` — the existing manifests-applier. Becomes the install path for CAPI/CAPN/CAPRKE2.
- `FluxInstanceLayer.java` (already authored) — the FluxInstance CR.
- The `manifests/` module's existing in-cluster workload layers.

**What's added**:
1. **Upstream YAMLs vendored** under `manifests/src/main/resources/upstream/clusterapi/{core,infra-incus,cp-rke2}/release-vX.Y.Z.yaml` matching `ComponentVersions` pins (clusterctl=v1.12.3, capiIncusProvider=v0.8.6, capiRke2Provider=v0.24.4).
2. **CAPI/CAPN/CAPRKE2 manifest layers** — `manifests/.../layers/clusterapi/{ClusterApiCoreLayer,ClusterApiInfraIncusLayer,ClusterApiCpRke2Layer,ClusterApiDomainRegistrar}.java` mirroring `TektonPipelinesLayer` pattern. These are first-class manifest layers, applied by `rke2lab-cluster-manifests.service`.
3. **`link.clusterApi.enabled` policy toggle** in `ManifestLinkPolicy`; default `true` in `Pulumi.dev.yaml`.
4. **Identity Secret extraction** (Stage A side): Incus CAPN identity secret created via `IncusIdentitySecretManifestUnit`. See [Bootstrap Identity Provider](bootstrap-identity-provider.adoc) for setup and usage patterns.
5. **`bioskop-image-state` ConfigMap** (Stage A side): emit a `ConfigMap` in `capn-system` carrying `imageAlias`, `imageFingerprint`, `imageBuildChecksum`, `incusProject`, `incusRemoteAddress`. This is the explicit Stage A → Stage B handoff for image identity, consumed by seed-peers in Phase 2 to populate `LXCMachineTemplate.spec.template.spec.image`.
6. **GitOps bootstrap layer** — `manifests/.../layers/gitops/FluxRootLayer.java` (new) emits the `GitRepository` (pointing at this repo, `branch=main`) + root `Kustomization` (watching `gitops/clusters/<cluster>/`). Applied by `rke2lab-cluster-manifests.service` at master bootstrap. From then on Flux self-manages reconciliation of the `gitops/` subtree. The same `GitRepository` + root `Kustomization` YAMLs *also* live committed under `gitops/flux-system/` for self-reference, but the bootstrap source is the manifest layer (chicken-and-egg solved).
7. **Cluster age key bootstrapping** (also Stage A): generate an age keypair if absent on the operator's machine, push the public half into `gitops/clusters/bioskop/.sops.yaml` (committed), apply the private half as `Secret sops-age` in `flux-system` namespace. Configure the root Kustomization with `decryption.provider: sops, secretRef: sops-age`. Allows Phase 2 SOPS-encrypted cloud-init Secrets to decrypt at apply time.

**What's removed** (see [Cluster API Bootstrap Requirements](cluster-api-bootstrap-requirements.adoc) for cleanup plan):
- `manifests/src/main/resources/systemd/systemd-units/rke2lab-cluster-api-install.service`
- `manifests/src/main/resources/systemd/systemd-units/rke2lab-capn-provider-install.service`
- `manifests/src/main/resources/systemd/systemd-scripts/rke2lab-cluster-api-install.sh`
- `manifests/src/main/resources/systemd/systemd-scripts/rke2lab-capn-provider-install.sh`
- The clusterctl install logic in `FloxRuntimeAssets.java` (asset wiring) + the `clusterctl` binary install in any flox env.
- These imperative shell wrappers are superseded by deliverable #2's declarative manifest layers.

**Validation**:
- `kubectl get crd | grep -c cluster.x-k8s.io` ≥ 50.
- CAPI/CAPN/CAPRKE2 controller-managers `Running`.
- `kubectl get secret -n capn-system bioskop-incus-identity` exists with four keys.
- `kubectl get fluxinstance -A` shows the FluxInstance Ready.
- `systemctl list-dependencies rke2lab.target | grep -E "cluster-api|capn"` returns no rows.

### Phase 2 — seed-peers module + operator-driven peer1

Author the `seed-peers/` module. Operator runs it locally, commits the synth output, peer1 comes up.

**Deliverables**:
1. **Module rename**: `seed-master/` → `seed-master/`. Update Maven `<artifactId>`, `<name>`, parent pom's `<modules>`, all refs across CLAUDE.md / pom files / scripts. Group id stays `io.seedmatic.rke2lab`.
2. **Image-state ConfigMap as Stage A output**: master's bootstrap (in HostStage's k8s ConfigMap authoring) emits a `ConfigMap bioskop-image-state` in `capn-system` carrying:
   - `imageAlias: control-node`
   - `imageFingerprint: <sha from imageProvider>`
   - `imageBuildChecksum: <from BuildMetadata>`
   - `incusProject: rke2lab`
   - `incusRemoteAddress: https://bioskop-nixos:8443`
   This is the explicit handoff surface seed-peers reads at synth time to populate `LXCMachineTemplate.spec.template.spec.image` and `LXCCluster.spec.controlPlaneEndpoint` references.
3. **New module `seed-peers/`**:
   - `pom.xml` with deps: `seed-master/` (Pulumi excluded), `manifests/` (for cdk8s App + ApiObjects), `cdk8s` from BOM.
   - `Main.java`: fluent pipeline `environment` (load BootstrapConfig + ControlplanePolicy + read `bioskop-image-state` ConfigMap via kubeconfig) → `synth` (synthesize gitops tree) → `output` (write to `gitops/clusters/<cluster>/`).
   - Synth code: per canonical node, materialize cloud-init via `HostStage.materializeAssets` (output to a tmp dir per node) → pack into a cdk8s `Secret` → emit `LXCMachineTemplate` referencing that Secret AND `image: { alias: <imageAlias> }` from the image-state ConfigMap. Plus single-instance `Cluster`, `LXCCluster` (with `secretRef: bioskop-incus-identity`), `KThreesControlPlane`, `MachineDeployment` resources.
   - SOPS-encrypt cloud-init Secrets via shell-out to `sops` (simpler than Java bindings).
4. **Refactor `RuntimeCloudConfigLayer`** to template `clusterName` + `nodeName` + hostname (currently hardcoded). Master also benefits.
5. **Cluster age key**: at master bootstrap (Phase 1 work, but executed here): generate an age keypair if absent, push the public half into `gitops/clusters/bioskop/.sops.yaml` (or equivalent), apply the private half as a `Secret` named `sops-age` in `flux-system`. Configure Flux's Kustomization with `decryption.provider: sops + secretRef: sops-age`.
6. Author `gitops/clusters/bioskop/kustomization.yaml` and `gitops/clusters/bioskop/.sops.yaml`. The kustomization references a `cluster.yaml`, `controlplane.yaml`, and the `peers/` directory.
7. Initial seed-peers synth produces all resources (peer1 LXCMachineTemplate, peer1 cloud-init Secret, peer2/3 + workers similarly), but `KThreesControlPlane.spec.replicas: 1`. peer1's pieces are present in gitops/ but not yet instantiated.

**Operator workflow to bring up peer1**:
1. `mvn -pl :seed-peers generate-resources` (synthesizes `gitops/clusters/bioskop/`).
2. `git add gitops/ && git commit && git push`.
3. Edit `gitops/clusters/bioskop/controlplane.yaml`: `replicas: 1 → 2`. Commit. Push.
4. Flux reconciles → CAPRKE2 sees replica delta → CAPN provisions LXC → rke2 boots → joins.

**Validation**:
- `mvn -pl :seed-peers generate-resources` produces ≥ 5 LXCMachineTemplates + 5 cloud-init Secrets + 1 Cluster + 1 KThreesControlPlane + 1 MachineDeployment.
- Cloud-init Secrets are SOPS-encrypted (`grep ENC\\[ gitops/clusters/bioskop/peers/peer1-cloud-init.sops.yaml`).
- After replicas bump commit: `kubectl get cluster bioskop` Provisioned, `kubectl get nodes` lists `bioskop-peer1` Ready, `incus list bioskop-peer1` on bioskop-nixos shows it Running.

### Phase 3 — Tekton drift-correction with branch + PR-on-success

GitHub webhook triggers seed-peers in-cluster on relevant pushes; Tekton synthesizes onto a feature branch and opens a PR if apply succeeds. Operator reviews + merges the auto-PR exactly like a normal PR.

**Deliverables**:
1. **Image build**: add `jib-maven-plugin` to `seed-peers/pom.xml` (and CI workflow `.github/workflows/seed-peers-image.yml`). Push to `ghcr.io/seedmatic/seed-peers:<git-sha>` + `:latest`.
2. **Tekton manifests** as a new layer `manifests/.../layers/cicd/SeedPeersTektonLayer.java`:
   - `Task: clone-repo`.
   - `Task: synth-seed-peers` (image: `ghcr.io/seedmatic/seed-peers`). Workspace shared with clone-repo. Output: `/workspace/gitops/`.
   - `Task: sops-encrypt` — safety net; the Java side does the encryption, this verifies + re-encrypts any plaintext Secret that slipped through.
   - `Task: branch-and-commit` — `git checkout -b tekton/drift-<sha>`; if `git diff --quiet HEAD` against the equivalent on main: exit cleanly without a branch (no drift); else commit + push the branch.
   - `Task: try-apply` — server-side dry-run apply of the new branch's gitops/ tree. If dry-run fails, fail the Task. If dry-run succeeds, real apply.
   - `Task: open-pr-on-success` — `gh pr create --base main --head tekton/drift-<sha> --title "[tekton] drift correction from <sha>" --body "<diff summary>"`. Only runs if `try-apply` succeeded.
   - `Pipeline: drift-correct` chaining the above with appropriate `runAfter` and `when` conditions.
   - `EventListener: rke2lab-github-listener` exposed via Service `LoadBalancer` with `tailscale.com/expose=true`.
   - `TriggerBinding: github-push-binding` extracts `repository.full_name`, `head_commit.id`, `head_commit.modified` (changed file paths).
   - `TriggerTemplate: github-push-template` instantiates `PipelineRun` of `drift-correct` with `revision=$head_commit.id`.
   - `Interceptor: github-webhook-validator` verifies the GitHub webhook secret.
   - `Interceptor: cel-path-filter` skips the run if `head_commit.modified` only touches `gitops/` (Flux's domain) or `docs/` (no synth impact).
3. **GitHub App credentials**: Tekton needs branch + PR-creation rights, NOT push-to-main. Create a GitHub App with `contents: write` + `pull_requests: write` on this repo. Install on `nxmatic/rke2lab`. Apply private key + app ID as `Secret github-app-creds` in `tekton-pipelines`. Each Task that uses git/gh sources this Secret.
4. **CAPN re-roll on Secret change**: a downstream `Task: roll-machines-on-secret-change` runs after Flux applies the merged PR. Compares each Machine's referenced Secret hash against the freshly-applied Secret; if changed, deletes the Machine. CAPRKE2 spawns a replacement.
5. **Image drift is out-of-band**: changes to the `control-node` image (distrobuilder config, base packages) require running `pulumi up` against master to rebuild the image and refresh `bioskop-image-state`. Tekton in-cluster only reacts to changes that don't cross the Stage A image-build boundary. Document this in `seed-peers/`'s README.

**Validation**:
- Push a no-op commit touching `seed-master/...`. PipelineRun runs, `branch-and-commit` finds no drift, exits cleanly. No PR opened.
- Push a meaningful Java change (e.g. user-data templating tweak). PipelineRun runs: branch created, diff committed, `try-apply` passes, PR opened against main. Operator reviews PR, merges. Flux applies. `roll-machines-on-secret-change` re-rolls affected nodes.
- Push a Java change that breaks something (intentional). PipelineRun runs: branch created, diff committed, `try-apply` fails, PR NOT opened, branch left for inspection. Operator inspects branch + PipelineRun logs, fixes.

**Validation**:
- Push a commit touching `seed-master/...`. `kubectl -n tekton-pipelines get pipelinerun -w` shows a new run.
- Commit gets pushed by Tekton with regenerated `gitops/clusters/bioskop/peers/peer1-cloud-init.sops.yaml`.
- Flux reconciles. CAPN re-rolls peer1. New cloud-init applied.
- Pushing a docs-only commit does NOT trigger the pipeline (path filter works).

## Critical files (representative — not exhaustive)

**Phase 1**:
- `manifests/src/main/resources/upstream/clusterapi/{core,infra-incus,cp-rke2}/release-vX.Y.Z.yaml` (new)
- `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/clusterapi/{ClusterApiCoreLayer,ClusterApiInfraIncusLayer,ClusterApiCpRke2Layer,ClusterApiDomainRegistrar}.java` + ManifestUnits (new, mirrors Tekton pattern)
- `manifests/src/main/java/io/seedmatic/rke2lab/manifests/layers/gitops/FluxRootLayer.java` (new) — emits `GitRepository` + root `Kustomization` referencing this repo's `gitops/` subtree
- `seed-master/.../incus/IncusResourceBootstrap.java` HostStage — add identity-Secret materialization
- `seed-master/.../policy/ManifestLinkPolicy.java` + `ControlplanePolicy.java` — add `policy.link.clusterApi.enabled`
- `manifests/src/main/resources/systemd/systemd-{units,scripts}/rke2lab-{cluster-api,capn-provider}-install.{service,sh}` (delete)
- `manifests/.../FloxRuntimeAssets.java` — drop clusterctl asset wiring
- `Pulumi.dev.yaml` — add `policy.link.clusterApi.enabled=true`

**Phase 2**:
- Module rename: `seed-master/` → `seed-master/` across `pom.xml` (root + module), `CLAUDE.md`, all refs
- New module `seed-peers/` with `pom.xml` (deps: seed-master with Pulumi exclusions, manifests, cdk8s from BOM), `src/main/java/.../Main.java`, synth code reusing `HostStage.materializeAssets`
- `manifests/.../runtime/RuntimeCloudConfigLayer.java` — parameterize cluster + node + hostname
- `gitops/flux-system/{gitrepository,kustomization}.yaml` + `gitops/clusters/bioskop/{cluster,controlplane,kustomization,.sops.yaml}.yaml` (synth output, committed)
- Cluster age key bootstrapping (Phase 1 work, executed here): generate, apply private half as `Secret sops-age` in `flux-system`, public half lives in `gitops/clusters/bioskop/.sops.yaml`

**Phase 3**:
- `seed-peers/pom.xml` — add `jib-maven-plugin`
- `.github/workflows/seed-peers-image.yml` (new) — build + push on main
- `manifests/.../layers/cicd/SeedPeersTektonLayer.java` (new) — Task/Pipeline/EventListener/TriggerBinding/TriggerTemplate
- Deploy key / GitHub App credentials as `Secret` in `tekton-pipelines`

## Verification (end-to-end)

**Phase 1**: `kubectl get crd | grep -c cluster.x-k8s.io` ≥ 50; CAPI/CAPN/CAPRKE2 controllers Running; `bioskop-incus-identity` Secret valid; FluxInstance Ready; dormant systemd units removed.

**Phase 2**: `mvn -pl :seed-peers generate-resources` synthesizes the gitops/ tree with SOPS-encrypted cloud-init Secrets. After replicas 1→2 commit + push: peer1 joins control plane.

**Phase 3**: Pushing a `seed-master/` change triggers Tekton; Tekton commits regenerated YAMLs; Flux applies; affected Machines re-rolled; cloud-init drift resolved automatically.

## Out of scope (flagged, separate efforts)

- Migrate `PorchResourcesLayer` packages (cilium, headscale, etc.) to plain Flux Kustomizations + HelmReleases.
- Worker `MachineDeployment` instantiation. Templates synthesized in Phase 2; replicas bumped in a future commit.
- nikopol cluster (multi-cluster). seed-peers is parameterized for it but `gitops/clusters/nikopol/` not populated initially.

## Phase 2+: vCluster-Based Architecture (REVISED)

**IMPORTANT**: The original Phase 2-4 plan has been superseded by a revised approach based on architectural analysis.

**Original plan**: Phase 2-3 install Flux in core, Phase 4 move to vCluster.
**Revised plan**: Skip "Flux in core" step, go directly to gitops-mgmt vCluster pattern.

See [vCluster Implementation Plan](vcluster-implementation-plan.adoc) for the complete revised roadmap (Phase 1.5-6).

**Key change**: Phase 1-3 from this document are now consolidated into Phase 1.5-4 in the implementation plan, with better separation of concerns between RKE2 auto-deploy (infrastructure) and Flux (workloads).

**Key architectural shift**: Phase 1-3 installs Flux + CAPI in the **core cluster** as a temporary arrangement. Phase 4 migrates them into a dedicated **`gitops-mgmt` vCluster**. This enables:

- **Reentrant testing**: Test core cluster upgrades in isolation via `gitops-mgmt-next` before promoting to production
- **Complete reentrancy**: The core cluster is managed like any other vCluster (Flux applies to parent via `parent-cluster-kubeconfig` Secret)
- **Better isolation**: Flux failure in gitops-mgmt doesn't crash the core cluster
- **Upgrade validation**: Standard workflow for testing Flux/CAPI upgrades before applying to production
- **Mutualized management**: One Flux instance manages both the core cluster and all lab vClusters (not N × 120MB)

**Migration path**: Phase 4 includes a detailed migration plan from "Flux in core" (Phase 1-3 state) to "Flux in gitops-mgmt vCluster" with rollback procedures and validation steps.

**When to start Phase 4**: After Phase 2-3 are complete and peer provisioning is stable. The vCluster architecture assumes the cluster infrastructure (CAPI + peers) is operational.
