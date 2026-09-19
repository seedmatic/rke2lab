---
name: rke2-config-reconciliation-nixrun-delivery
description: "La config RKE2 (config.yaml.d) est livrée UNIFORMÉMENT par `nix run <branche-manifests>#install-rke2-config` ; statique→node-base, per-cluster→rendu+fetch. Path glob mort à réconcilier."
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-12T19:21:49.881Z
---

**Le path de config RKE2 est CASSÉ + le design de réconciliation est TRANCHÉ (2026-09-12, code PAS commencé).** Plan détaillé : `.claude/rke2-config-reconciliation-plan.md`.

**Problème** : `RuntimeRke2ConfigManifestsUnit` rend 11 ConfigMaps RKE2_CONFIG (`workloads/runtime/rke2-config/`) que **`rke2lab-config-install.sh` (RETIRÉ)** devait glob'er dans `/etc/rancher/rke2/config.yaml.d`. → **path MORT**. Réglages perdus prouvés live : le **VIP absent du cert apiserver** (`x509 … not 10.80.7.10` → `RemoteConnectionProbe` fail → `ControlPlaneInitialized` stalle sur `bioskop-mgmt`), `disable` (ingress-nginx + snapshot-controller **tournent**, conflit openebs), `node-ip` (InternalIP=lan0 au lieu de vmnet0). Bug d'origine dans `tls-san.yaml` rendu : `vipGatewayInetAddr` (routeur) au lieu de `vipHostInetAddr` (VIP).

**Design tranché (user)** :
1. Canal cloud-init/devlxd UNIFORME (seed-master + CAPN) — zéro bénéfice à séparer. (devlxd n'est plus utilisé en clés dédiées ; tout passe par `cloud-init.user-data`, lu over `/dev/incus/sock` par le shim `nixos/cloud-init.nix`. Les refs devlxd des contrats/docs sont STALE.)
2. Découpe par NATURE : **STATIQUE** (disable, bind-address…) → node-base `services.rke2` (baké, tous nœuds ; `disable` déjà mis en extraFlags) ; **PER-CLUSTER** (cidrs, tls-san+VIP, node-ip) → rendu par-cluster (SSOT blueprint) sur la branche `manifests/<cluster>`.
3. **Livraison per-cluster = `nix run <branche>#install-rke2-config` UNIFORME** (« même logique standalone/in-cluster ») : une app flake sur la branche extrait `.data`→`config.yaml.d` ; **mgmt** = seed-master push la branche AVANT de démarrer l'instance + mint un boot-token (`GithubWriterTokenMint`) + pose token+ref → oneshot nix `nix run` avant rke2-server ; **workload** = token du gtm mgmt via `preRKE2Commands` CAPRKE2. Auth dispo des deux côtés (seed-master mint App / in-cluster gtm) → fetch boot OK.

**À faire** : (1) app flake `install-rke2-config` sur la branche ; (2) InstanceGrow pose ref+token + oneshot nix ; (3) preRKE2Command workload ; (4) **revert** `RKE2LAB_TLS_SANS` (GrowIdentityView.tlsSans/resolver/InstanceGrow) + oneshots nix dualstack/tls-san — **GARDER** disable-statique + `vipGateway→vipHost` dans le rendu ; (5) valider re-grow (VIP au cert). Réf de l'ancien script : branche `feature/cluster-seed-scenario` `osgi/.../systemd/systemd-scripts/rke2lab-config-install.sh`.

See [[cp-endpoint-reach-tailnet-headscale-migration]] [[all-workloads-on-flox-runtime]] [[flox-controller-github-token-via-gtm]] [[github-token-mint-on-demand]].

**★ CORRECTION (2026-09-19, vérifié contre `feature/nixos-node-substrate`) — « code pas commencé » était FAUX.**
Le code existe et est câblé de bout en bout : `RuntimeRke2ConfigManifestsUnit`
(`osgi/domains/manifests/manifests-core/.../units/runtime/rke2/`), `nixos/rke2.nix`, plus les
points d'appel `InstanceGrow`, `NodeBootstrapMaterial`, `ManifestSynthesisScenario`,
`DefaultManifestExplodeService`, `ClusterSeedScenario`, `GrowIdentityView` — 10 fichiers hors
mémoire, commit `884fcd518` (« order rke2lab-rke2-config after rke2lab-identity »). Ce qui reste
est de la **validation**, pas de l'écriture. Note antérieure conservée ci-dessus pour le design.
