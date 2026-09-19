# Infra / manifests / config — section index (rke2lab memory)

Section of the rke2lab memory index. Loaded on demand; the injected root is [MEMORY.md](MEMORY.md).
One line per entry (~200 chars); detail lives in the linked file.

## Infra / manifests / config

- [★ NixOS node substrate (CURRENT chantier)](nixos-node-substrate-state.md) — **the post-June trunk: every RKE2 node = same NixOS Incus image (nixos-generators), role is config not OS; cloud-init + distrobuilder + /srv/host systemd all collapse.** Base = `feature/cluster-seed-scenario` (not main). Substrate MATURE (nix build, devlxd identity, deterministic PKI, rendered-branch Flux). GAP = no management cluster to grow workloads; node NotReady (`cni="none"`, cilium only an rke2 addon override). Baremetal nikopol→bioskop. NEXT = boucler le substrat (cilium/CNI Ready + validate the Flux loop). See [[cluster-seed-execution-state]] [[master-execution-stage-missing-state]] [[gitops-cluster-api-transition-plan]].
- [OSGi-idiom debt: logging + shadowed services](osgi-logging-and-cli-debt.md) — TRIAGED: no standalone debt remains. Log→LogService via Pax (R4/R6); ServiceLoader→DS = the runtime trunk; the rk2lab typo is dead. See [[hub:check-osgi-standard-before-modeling]].
- [Coherence-rules coordinator](coherence-rules-coordinator.md) — walker-retirement: cross-domain rule = explicit pure check that REPORTS (option B), not silent Felix prune; `resolve()` = single coherence gate. CoherenceRule interface deferred to rule-of-three.
- [Config restructuring state](config-restructuring-state.md) — Inc1 (Rke2labConfig DTO + InfraDomain enum) MERGED; Inc2 (doctor remediation) waits on doctor work.
- [Manifests doc consolidation](manifests-doc-consolidation.md) — DONE: hub manifests-architecture.adoc + 4 companions.
- [Terminology refactor state](terminology-refactor-state.md) — manifests renames done; BootstrapPhase + DomainRegistrars pending.
- [Manifest registrars enum refactor](manifest-registrars-enum-refactor.md) — NEXT-TOPIC: 11 registrars → enum; defer (touches dormant buildDomainRegistry).
- [Refactor pipeline candidates](refactor-pipeline-candidates.md) — 9 methods to refactor (fluent grammar/builder/record); start with synthesizeInContext.
- [Package-private sweep](package-private-sweep.md) — remove non-essential private; exemplar = DefaultManifestSynthesisService.
- [Domain registry abstraction](domain-registry-abstraction.md) — DEFERRED; unify Manifest+Infra registry pairs at rule-of-three.
- [seed-vcluster](seed-vcluster.md) — next chantier: bootstrap vCluster gitops-mgmt + Flux; needs vcluster operator unit + pulumi-command.
- [GitOps + Cluster API transition plan (re-homed design record)](gitops-cluster-api-transition-plan.md) — **STALE (2026-06):** peer1+ provisioning via seed-peers + GitOps + CAPI + Tekton, over distrobuilder + cloud-init + `/srv/host` + `nxmatic/rke2lab` — nearly all superseded/deleted by [[nixos-node-substrate-state]]. Read as history; the live delivery model is now rendered-branch Flux.

