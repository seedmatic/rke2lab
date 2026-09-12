# Plan — réconcilier la config RKE2 (path config.yaml.d mort → livraison uniforme)

**Problème (2026-09-12)** : `RuntimeRke2ConfigManifestsUnit` rend 11 ConfigMaps RKE2_CONFIG (`workloads/runtime/rke2-config/` : advertise-address, cidrs, cluster-init, core, debug, disable, etcd-metrics, etcd, node-inetaddr, tls-san, token) destinées à être glob'ées dans `/etc/rancher/rke2/config.yaml.d` par **`rke2lab-config-install.sh` — RETIRÉ** (existe sur la réf `feature/cluster-seed-scenario`, plus dans la codebase). → **path MORT** : ces réglages ne s'appliquent plus. Symptômes prouvés live :
- Cert apiserver (`bioskop-mgmt-master`) **sans le VIP** `10.80.7.10` → CAPI clustercache `x509: certificate is valid for …, not 10.80.7.10` → `RemoteConnectionProbe` fail → `ControlPlaneInitialized` stalle. (Le drop-in nix POSE bien hostname+.local ; c'est le VIP qui manque — le `tls-san.yaml` rendu avait `vipGatewayInetAddr` (routeur, ERREUR) au lieu de `vipHostInetAddr`.)
- `rke2-ingress-nginx` + `rke2-snapshot-controller` **tournent** (disable perdu ; snapshot-controller en conflit avec openebs-zfs).
- node InternalIP = `192.168.1.131` (lan0), pas vmnet0 (node-ip perdu).

## Décisions tranchées (user)
1. **Canal = cloud-init/devlxd UNIFORME** (pas de séparation seed-master/CAPN). Zéro bénéfice à séparer.
2. **Découpe par NATURE** :
   - **STATIQUE** (identique partout : `disable` ingress-nginx/snapshot-controller/-webhook, bind-address, write-kubeconfig-mode) → **node-base `services.rke2`** (baké, s'applique mgmt + workload). ✅ `disable` FAIT (extraFlags).
   - **PER-CLUSTER** (cidrs, tls-san+VIP, node-ip, etcd node-name) → **rendu par-cluster** (SSOT = blueprint via `RuntimeRke2ConfigManifestsUnit`, VIP corrigé `vipGateway→vipHost`) sur la **branche `manifests/<cluster>`**.
3. **Livraison PER-CLUSTER = `nix run` depuis la branche, UNIFORME aux deux growers** (« même logique standalone ou in-cluster ») :
   - Une **app flake `install-rke2-config` sur la branche manifests** : extrait `.data` des ConfigMaps rke2-config → `config.yaml.d` (logique de l'ancien `rke2lab-config-install.sh` : `yq '(.data)|with_entries(.value|=from_yaml)'`).
   - **mgmt (seed-master)** : seed-master rend+push la branche AVANT de démarrer l'instance (chicken-egg résolu), **mint un token** (`GithubWriterTokenMint`, celui du push) + pose token+ref sur l'instance → oneshot nix `nix run github:…/manifests-<host>-mgmt#install-rke2-config` avant `rke2-server`.
   - **workload (CAPRKE2)** : token du **gtm** du cluster mgmt, livré via la cloud-init/`preRKE2Commands` de CAPRKE2 → `nix run …/manifests-<host>-wrkld#install-rke2-config`.
   - Auth confirmée dispo des deux côtés (seed-master mint App ; in-cluster gtm) → le fetch au boot n'est PAS bloqué.

## Composants à réaliser
1. **App flake `install-rke2-config`** à la racine de la branche `manifests/<cluster>` (rendue par la synthèse) : lit les ConfigMaps rke2-config du checkout, extrait `.data` → `config.yaml.d`. (Adapter l'ancien script réf.)
2. **mgmt** : `InstanceGrow` pose `user.rke2lab.rke2-config-ref` (HEAD poussée) + un boot-token (via `GithubWriterTokenMint` révélé dans `NodeBootstrapMaterial`) ; **oneshot nix** (avant rke2-server, `ConditionPathExists` node.env) fait le `nix run` pinné sur la ref, avec le token en `nix.conf access-tokens`.
3. **workload** : `ClusterApiCrRenderer.rke2ControlPlane` + le controller `rke2ControlPlaneObj` — un `preRKE2Command` (comme kube-vip) qui `nix run` la branche wrkld, token du gtm.
4. **Retraits** : oneshots nix `rke2lab-dualstack` + `rke2lab-tls-san` (remplacés par le config-install) ; **revert** `RKE2LAB_TLS_SANS` (GrowIdentityView.tlsSans + `GrowIdentityResolver` + `InstanceGrow`). **GARDER** : `disable` statique (extraFlags) + le VIP dans `tls-san.yaml` rendu.
5. **Nettoyage** : les refs devlxd stale (contrats `ClusterAgeKey`/`ClusterCaBundle`/`ServerManifestsBundle`, `nixos/sops.nix` commentaires, doc `deterministic-cluster-access §160`) décrivent l'ancien design clés-dédiées — à réconcilier avec le réel (cloud-init.user-data). Chantier annexe.
6. **Valider** : rebuild node-base + re-grow → cert a le VIP (RemoteConnectionProbe OK, ControlPlaneInitialized), ingress-nginx/snapshot-controller absents, InternalIP vmnet0.

## État non commité (à la compaction)
- `nixos/rke2.nix` : `disable` extraFlags (**garder**) ; drop-in tls-san converti en loop `RKE2LAB_TLS_SANS` (**à revert** → remplacé par config-install).
- `RuntimeRke2ConfigManifestsUnit.java` : `vipGateway→vipHost` (**garder** — c'était le vrai bug ; le VIP vit dans le rendu).
- `GrowIdentityView`/`GrowIdentityResolver`/`InstanceGrow` : champ `tlsSans` + `RKE2LAB_TLS_SANS` (**à revert** — approche env-var abandonnée).
- Plans : ce fichier + `.claude/rke2-adoption-controller-floxenv-plan.md`.

## Contexte lié
Le controller d'adoption tourne déjà sur le runtime flox (commit `f00d285df`, prouvé). Le blocage `bioskop-mgmt` (RemoteConnectionProbe) = ce chantier (VIP dans le cert). See mémoires [[cp-endpoint-reach-tailnet-headscale-migration]] [[all-workloads-on-flox-runtime]].
