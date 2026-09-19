---
name: constants-review-backlog
description: "Ranked backlog of bare literals across the OSGi codebase that should become typed abstractions (enums, value records, named-constant catalogs) — from two 2026-08-30 surveys."
metadata: 
  node_type: memory
  type: project
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-08-30T05:57:27.087Z
---

Two read-only survey passes (2026-08-30, user-requested after introducing the `FloxEnvFolder` enum) of the rke2lab OSGi codebase (`osgi/`), looking for literals that want a typed abstraction. **Non urgent — a map to act on incrementally, NOT a blind mass-migration.** Codebase already models most closed vocabularies as enums (`SystemdUnitId`, doctor `Specialty`/`Severity`, `ClusterReadinessPhase`, `ImageState`, synthesis `Phase`, `EnforcementLevel`, ingress `Component`/`BumpLevel`); the items below are the outliers. Hotspot = `manifests-core/.../units/**` (builders inline nearly every literal into `Map.of`).

**Why:** bare literals of a closed set silently misfire — a typo binds nothing (the `clusterApi`≠`cluster-api` class of bug), a port typed both `int` and `String` drifts, a copy-pasted resource profile splits. A type makes the set exhaustive + validatable.

## PASS 1 — string→enum (STRONG first)
- **① flox env compound coordinates `"<folder>/<name>[-debug]"`** — STRONG. `"mesh/headscale"`, `"networking/kdns"`… passed to `FloxDebugPolicy.resolveMeshEnvironment/resolveNetworkingEnvironment` (~15 sites: HeadscaleManifestsUnit, HeadplaneManifestsUnit:525/668/811, KdnsManifestsUnit:249/256). Folder half is hand-concatenated instead of `FloxEnvFolder.value()`. **Target = a coordinate type composing `FloxEnvFolder` + workload + prod/debug suffix**, taken by `resolve*Environment`. Directly extends the enum we just shipped → promote `FloxEnvFolder` to manifests-contract (+ spec) then.
- **② reconcile LAYERS `crds/foundation/operators/workloads`** — STRONG. `ManifestAnnotations.LAYER_*` (:66/74/77/80), closed AND ORDERED (`FluxRootManifestsUnit:69-84` chains dependsOn in that order; `DefaultManifestExplodeService:190-193`). Threaded as bare `String` through `PackageMetadataProfile` + ~12 unit ctors (~20 refs). Enum adds the ordering guarantee strings can't.
- **③ domain IDs** — already `ManifestDomainCatalog` (record+builder), NO new enum. But ~15 sites still pass raw kebab literals (`PackageMetadataProfile("mesh",…)`, `DOMAIN_NAME="networking"` in KdnsManifestsUnit:34, ReplicatorManifestsUnit:24, OpenebsZfsManifestsUnit:22) — route through the constants.
- ④ `flox.dev/{environment,home,uid,gid}.<c>` annotation prefixes — WEAK enum / MEDIUM "centralize 4 prefixes" (KdnsManifestsUnit:315-318, FloxShellSidecarProfile:155-158, Headscale/Headplane).

## PASS 2 — non-string typed abstractions (STRONG first)
- **① k8s well-known ports** — STRONG, ~20 sites. Same port typed both `int` (containerPort) and `String` (env value); half-named (APISERVER_PORT ClusterKubeconfigManifestsUnit:53, WEBHOOK_TARGET_PORT FloxWebhookManifestsUnit:64) half-inline (6443/8080/9090/9443/50443/5353 across Kdns/Cilium/KubeVip/Headscale/Headplane/FloxController). `svc.cluster.local:8080` URL hardcoded twice. **Target = shared `Ports` catalog / `Port` newtype.**
- **② container resource requests/limits** — STRONG, ~15 blocks / 65 literal lines. Identical profile shapes copy-pasted (Headscale:865-872 == Headplane:771-778; sidecar 200m/256Mi recurs Headscale:1206/1402, EnvoyGateway:271). **Target = `ResourceProfile` value record + named catalog (STANDARD_SERVICE/SMALL_SIDECAR/TINY) in manifests-core `profiles/`.**
- **③ `defaultMode` 493** — STRONG, 7 sites (Headplane:604, Headscale:892/1118/1242/1438, Kdns:327, EnvoyGateway:306). `493` = decimal-encoded octal `0755`, intent invisible. **Target = `EXEC_SCRIPT_MODE = 0755` const (or `FileMode` enum, also covers `"0640"` RuntimeRke2ConfigManifestsUnit:72).**
- ④ network CIDR/IP literals in manifests (`10.80.x` ×10, `192.168.1.x` ×6) — MEDIUM/STRONG: re-encode octets the well-typed `ClusterNetworkBlueprint`/`Cidr` (netplan-contract) already owns → expose as blueprint accessors, manifests consume them.
- ⑤ health-probe timing ints (`ProbeSpec` record) — MEDIUM. ⑥ `GitCli.run(boolean check,…)` positional bool → `ExitPolicy` enum, ~10 sites (worktree-core) — MEDIUM. ⑦ Flux interval `"5m"` ×3 — WEAK. ⑧ `--timeout=300s` in heredocs ×7 — WEAK. ⑨ scattered `Duration.ofSeconds(20|30)` — WEAK (only worktree domain worth consolidating).

**Top-3 to act on:** PASS2 #1 ports, #2 resource-profile, #3 exec-mode — highest density + duplication, all in `manifests-core/units`. PASS1 ① best extends the just-shipped [[flox-env-migration-design]] `FloxEnvFolder`. Excluded (already modelled / LDAP-inlined / external lib): ClinicianProperties, GardeningSelection, ClusterNetworkBlueprint octets, BouncyCastle criticality booleans, `Map.of(...,"readOnly",true)` YAML values.
