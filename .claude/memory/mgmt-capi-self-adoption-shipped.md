---
name: mgmt-capi-self-adoption-shipped
description: WIN 2026-09-13 — bioskop-mgmt se self-adopte dans CAPI (RemoteConnectionProbe=True) ; chaîne de fixes rke2/réseau + gotchas réutilisables
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-13T12:21:56.734Z
---

**★ WIN (2026-09-13) : bioskop-mgmt s'auto-adopte dans CAPI de bout en bout.** `ClusterAdoption bioskop-mgmt` = `Adopted`/`READY=True` ; conditions Cluster toutes vertes dont **`RemoteConnectionProbe=True`** (le blocker d'origine — cert apiserver sans VIP) + `ControlPlaneInitialized=True`, etcd/CP healthy. C'était l'objectif du chantier. Cluster up → Flux reconcilie → FloxEnvs réalisés (headplane le dernier lourd) → render in-cluster.

**Chaîne de fixes shippée cette session** (branche `feature/nixos-node-substrate`, jusqu'à `179649ddc`, tous poussés) :
- **cache** : `nixConfig.extra-substituters += cache.flox.dev` (+nxmatic) dans flake.nix rke2lab + flox-nri-plugin + flox-controller (repos publics) ; racine = `~/.config/nix/nix.conf` avec `substituters =` PLAT qui écrasait la closure cache-trust ndh (nettoyé).
- **flox-controller lock** : 12107→5 nodes (redirect tous les inputs flake-commons inutilisés → nixpkgs). rke2lab NON taillable (agrège ndh qui consomme ripvcs&co).
- **réseau node** : DNS (resolved + `useHostResolvConf=false`) ; routage (`vmnet0 UseGateway=false`, plus de default via l'interne) ; **vmnet v6 /64** déterministe.
- **rke2-config** : `cni` dédupliqué (node-base only) ; `cluster-init` mort retiré ; **node-ip dual-stack** ; **tls-san = VIP + FQDN mDNS** (SOT `NamePlan.nodeMdnsFqdn`) ; **kubelet `--node-ip` forcé via kubelet-arg**.
- **auth** : token mint fail-loud à la frontière (`String`, plus `Optional`). **adoption-controller** mem 128→256Mi.

**Gotchas réutilisables (non-évidents, re-mordront) :**
1. **rke2 ne propage PAS `node-ip` au kubelet pour une adresse DHCP-`dynamic`** → kubelet auto-détecte → prend `cilium_host` (IP pod-cidr) en InternalIP → cert kubelet (10250) mismatch → `kubectl logs/exec`+metrics cassés. **Fix = `kubelet-arg: ["node-ip=<v4>,<v6>"]`** (escape hatch documenté).
2. **dnsmasq DHCPv6 refuse un préfixe < /64** ("prefix length must be at least 64") — viser le /64 du rôle node, pas le /56 cluster.
3. **le bridge vmnet incus est ADOPTÉ** (InstanceGrow ensureNetwork early-return si existant) → un changement de config réseau n'est PAS appliqué au re-grow ; il faut recréer le bridge (ou `incus network set` + restart).
4. **`~/.config/nix/nix.conf` avec `substituters =` PLAT (pas `extra-`)** écrase toute la closure cache-trust système → build depuis sources.
5. cert kubelet SANs = adresses du node (node-ip), PAS le `tls-san` (qui est l'apiserver 6443).

Prouvé live : le v6 déterministe `fd96:6924:3693:20::a50:a` (embedded-v4) est bien sur vmnet0 + dans le cert kubelet. See [[capi-cluster-seeding-mirror-design]] [[rke2-config-reconciliation-nixrun-delivery]] [[workload-grow-foundations-resume]].
