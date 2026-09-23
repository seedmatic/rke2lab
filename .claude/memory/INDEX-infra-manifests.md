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
- [seed-vcluster](seed-vcluster.md) — ★ les vclusters se pilotent par CAPI (décision 2026-09-22) ; l'opérateur vcluster autonome N'EXISTE PAS en amont, et `ClusterRole` « workload does not host CAPI at all » devient faux.
- [cilium exige `nft_compat` sur un noyau nftables-only](cilium-needs-nft-compat-on-nftables-only-kernel.md) — sans lui, iptables-nft ne pose aucune règle, donc pas de `MARK_MAGIC_HOST`, donc tout trafic hôte→pod est `world-ipv4` et toute NetworkPolicy refuse les sondes du kubelet. Porte la liste de modules et les 8 pistes écartées.
- [Chaîne du matériel ssh sur le Mac non managé](unmanaged-mac-ssh-material-chain.md) — `999-ndh.conf` fossile d'avril référençant 4 fichiers absents ; la vraie chaîne est sops-nix → `ssh-keys-enrichment` → openssh. Ce Mac étant destinataire sops + profil déclaré, le rendre MANAGÉ coûte moins qu'un enrôleur. Bloqué par l'identité `nxmatic`.
- [GitOps + Cluster API transition plan (re-homed design record)](gitops-cluster-api-transition-plan.md) — **STALE (2026-06):** peer1+ provisioning via seed-peers + GitOps + CAPI + Tekton, over distrobuilder + cloud-init + `/srv/host` + `nxmatic/rke2lab` — nearly all superseded/deleted by [[nixos-node-substrate-state]]. Read as history; the live delivery model is now rendered-branch Flux.

- [Chaîne de bootstrap d'un cluster workload](workload-bootstrap-chain-cilium-kubevip.md) — bioskop-wrkld NotReady : cilium par défaut compose l'apiserver sur le ClusterIP avant tout CNI ; kube-vip jamais installé (ctr absent pré-RKE2, jq absent, airGapped). Voie bootstrap-manifest conçue, pas codée. `.skip` = passation non destructive.
- [netplan-projection-described-hosts](netplan-projection-described-hosts.md) — la projection décrivait des hôtes, pas des clusters ; et le roster est par rôle (mgmt single-node).
- [★★ segment fabric — nnh a défini le modèle : épinglage par `ipv4.address` sur le NIC, pas par MAC](fabric-segment-pinning-and-federation-from-nnh.md) — et chaque locataire ne publie que SA portion. ⚠️ la convention de carve nnh (haut=statique) entre en conflit avec le plan atlas (haut=autre lien). See [[netplan-per-node-projection-feeds-ndh-reservations]].
- [Migration fabric : état + file des chantiers](fabric-migration-state-and-queue.md) — les deux bare-metals renumérotés en `172.16.<hostId*16>.0/20` et vérifiés vivants ; puis `fabric-br`, les spans de cluster vers les slots 1-4, l'effondrement de `lan-br`. ⚠️ headscale ne peut PAS entrer dans la fabric (circularité de bootstrap).
