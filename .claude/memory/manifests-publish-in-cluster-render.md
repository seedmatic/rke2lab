---
name: manifests-publish-in-cluster-render
description: "In-cluster manifests render chantier — ExecutionEnvironment FACT + `manifests publish` verb shipped; PaC design converged+graved (Tekton objects = rendered manifests); NEXT = build the cicd units. 2026-08-31."
metadata: 
  node_type: memory
  type: project
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-08-31T09:06:16.004Z
---

**Chantier: the GitOps loop closes — the manifests renderer runs in two containers (operator standalone, in-cluster Tekton), the webhook we shipped triggers Flux.** Operational model (user, settled): **grow = seed-master** does the FIRST render+push host-side (bootstrap — nothing else exists yet, Flux needs the branch to pull); **steady-state = webhook + Flux + Tekton** maintains it. Not delegation between binaries — the render+push OPERATION lives in OSGi (the `ghapp`→`auth`→`manifests` scion graph, driven through the broker membrane); both seed-master and manifests-cli are thin HOSTS that sow the SAME coordinates, differing only by envelope (Pulumi grow vs standalone CLI).

**SHIPPED + PUSHED (feature/nixos-node-substrate, commits `0184fa1f0` + `44cfed288`):**
- **`host/host-runtime`** (new Pulumi-free module): `ExecutionEnclosure` enum FACT {OPERATOR, IN_CLUSTER} (sibling of RunMode, orthogonal) + **`ExecutionEnvironment`** — an INSTANCE holding the process env, resolving `enclosure()` (override `RKE2LAB_EXECUTION_ENCLOSURE` then kubelet `KUBERNETES_SERVICE_HOST`) + `secretsGateway()`. The Dot/Chained/Tailscale gateways MOVED here from seed-master's config pkg; ClusterSeedScenario's hardcoded chain → `new ExecutionEnvironment(System.getenv()).secretsGateway()`. **Two containers, two secret sources**: OPERATOR = ndh OAuth client + `.secrets`; IN_CLUSTER = `EmptySecretsGateway` (secret-blind — see below). Validated live via re-grow (githubapp + 5 replicator sources present).
- **`manifests publish` verb** (manifests-cli): renders into SOIL + signed-commit + ff-push `manifests/<cluster>`. `PublishCliScenario` sows ghapp→auth→manifests sharing the run cellar+Parcel; pushes as the **GitHub App** (baked for this — user confirmed "on a bake la github app pour cet usage"). Facet gains `delivery.push=true`. pom staged ghapp-bdd/ghapp-edge/auth-bdd + **bouncycastle** (bcprov/bcpkix/bcutil — ghapp-edge's JWT minter imports it; else BootPlanner can't system-export → assembly won't boot). host-runtime compile dep.

**KEY FINDING (verified at source):** the reconciled branch Flux tracks carries **ZERO secret bytes** — both secret-bearing units (`GithubAppSecretManifestsUnit`, `ReplicatorManifestsUnit`) render onto the `NODE_BOOTSTRAP` lane, which the exploder writes OUTSIDE the git worktree (`NodeBootstrapArtifact` = sibling one level above the tree; never staged/committed/pushed). So the in-cluster render is **structural, secret-blind** → IN_CLUSTER gateway = no-op (`EmptySecretsGateway`); `KubeSecretsGateway`-as-.secrets-mirror is UNNECESSARY (shelved). The in-cluster push token comes from **PaC's `git_auth_secret`** (see the PaC section below), not from a gateway.

**Boy-scout applied:** Main's private-static helpers → instance methods (user's recurring [[object-graph-navigability-principle]] / [[refactor-statics-on-touch]] feedback, tightened this session to "toutes les méthodes statiques, factory = instance pas helper"). No static methods left bar `main()`.

**PaC DESIGN CONVERGED + GRAVED (2026-08-31, commits `887ec1dba` spec+atlas+funnel, `fc5648459` reconcile).** In-cluster render = **Tekton Pipelines-as-Code** (deployed, profile `all`; App NOT configured yet). Spec = `docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc`; atlas Diagram O. THE decisive realisation (user): **the Tekton objects are ordinary K8S resources → RENDERED as manifests** (cicd units, Flux-reconciled in `tekton-pipelines`), NOT dissociated onto a branch. This DISSOLVED a long design detour: ❌ no `tekton/<cluster>` branch, ❌ no remote pipeline resolution, ❌ no second rendered branch, ❌ no `sourceBranch` synthesis-input threading (the deterministic branch is just the cluster name, already in `BootstrapIdentity`).
- **Only source footprint = a minimal `.tekton/render.yaml` PipelineRun stub on main** (PaC's trigger requires it; `pipelinerun_provenance` is only `source`|`default_branch`). It `pipelineRef`s the IN-CLUSTER `render-manifests` Pipeline by name + `on-event:[push]`/`on-target-branch` + reads per-cluster identity from the `Repository` CR `spec.params`. Glue, not logic.
- **Self-reference is benign — the trigger is SOURCE-scoped**: PaC only runs on `on-target-branch` (the SOURCE branch); the render's push to `manifests/<cluster>` (OUTPUT) + Flux's reconcile never re-trigger PaC → loop cut at the trigger. A Pipeline-def change = a one-run self-management lag (grow bootstraps). Corrected the earlier over-strong "pipeline must be source" claim.
- **Auth**: PaC = our baked App (EXTEND it, not a new App: point the App webhook at the PaC funnel, add `push` event + `checks:write`; Flux uses a SEPARATE repo-level webhook → coexist). PaC injects a `git_auth_secret` (App token) into the PipelineRun → in-cluster `publish` consumes it → **`EmptySecretsGateway` VINDICATED** (render secret-blind, token from PaC), no KubeSecretsGateway.
- **funnel SHIPPED**: `PacWebhookManifestsUnit` (Tailscale funnel Ingress → `pipelines-as-code-controller:8080`), registered in `CicdDomainRegistrar`, builds green (in `887ec1dba`).

**NEXT — build the cicd units (all rendered manifests) + the stub:**
1. `RepositoryManifestsUnit` (cicd): the PaC `Repository` CR, per-cluster, `spec.params` = cluster identity (from `BootstrapIdentity`, already available). The Tekton twin of the Flux `GitRepository`.
2. `pipelines-as-code-secret` unit (cicd, NODE_BOOTSTRAP lane): `github-application-id`/`github-private-key` (from `GithubAppMaterial`) + `webhook.secret` — reuse `github.webhook.token` exposed as a NEW synthesis material (twin of `GithubAppMaterial`).
3. The **`render-manifests` Pipeline + Task(s)** unit (cicd): clone → `mvn -pl :manifests-cli -am clean verify` → `manifests publish`. Runtime = flox-NRI-injected toolchain FloxEnv (JDK25/maven) — needs jdk25/maven added to the `flox-catalogue` flake (CROSS-BRANCH; the catalogue currently exposes only workload pkgs kdns/headscale/…) + a `FloxEnvFolder.CICD` flavor. Maven cache = RWO PVC at ~/.m2, concurrency 1, never `install`.
4. The `.tekton/render.yaml` stub committed on the source branch.
5. Publish in-cluster: `manifests publish` reads PaC's `git_auth_secret` for the push token (no ghapp/auth in-cluster).
Then extend the App (operator ceremony) + one re-grow → functional loop. **DO NOT re-grow before all this** (the funnel alone is inert).

See [[flux-per-service-kustomizations]] (the webhook) [[secrets-git-flox-activate]] [[flox-env-migration-design]] [[constants-review-backlog]].
