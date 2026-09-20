---
name: cilium-needs-nft-compat-on-nftables-only-kernel
description: "Chaîne causale COMPLÈTE (2026-09-20/21) — substrat nftables-only sans nft_compat ⇒ cilium ne peut installer ses règles iptables-nft ⇒ pas de MARK_MAGIC_HOST ⇒ tout trafic hôte→pod est world-ipv4 ⇒ toute NetworkPolicy refuse les sondes du kubelet. Corrigé par nft_compat + xt_mark/xt_CT/xt_TPROXY dans le profil Incus"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T23:03:53.516Z
---

**Symptôme** : les contrôleurs Flux restent `0/1` indéfiniment, sondes de liveness en timeout, sur
un cluster dont le nœud est `Ready` et dont l'agent cilium se dit `OK`. Corrigé dans rke2lab
`6e1889e3e` (et `026c4bfed` pour les deux préalables).

## La chaîne, chaque maillon mesuré

1. Le noyau du nœud est **nftables-only** — le trio iptables legacy a été retiré volontairement
   (javadoc de `InstanceGrow.nodeProfileConfig`).
2. cilium embarque `iptables v1.8.8 (nf_tables)`, qui réalise `-m mark`, `-m comment`, `-j CT` et
   `-j TPROXY` **par `nft_compat`**. Ce module n'était pas chargé.
3. Donc chaque règle de ce type échoue — `Warning: Extension mark revision 0 not supported` — la
   boucle `iptables-reconciliation-loop` de l'agent reste **`[Degraded]`**, et **10 règles sur 34**
   seulement sont posées.
4. Les règles manquantes sont celles qui estampillent **`MARK_MAGIC_HOST`** sur le trafic émis par
   l'hôte.
5. Sans ce marquage, `inherit_identity_from_host()` (`bpf/lib/identity.h`) tombe dans son
   `else` et rend **`WORLD_ID`** ; et `resolve_srcid_ipv4()` (`bpf/bpf_host.c`) refuse ensuite
   **délibérément** de le corriger par l'ipcache — commentaire à l'appui : sous SNAT en entrée, un
   paquet venu du monde porte lui aussi l'IP source de l'hôte, donc l'ipcache n'est pas digne de
   confiance sur ce point.
6. Donc **tout** paquet hôte→pod est `world-ipv4`, et tout pod portant une politique refuse les
   sondes du kubelet. Flux installe **deux** NetworkPolicies à sélecteur vide, d'où un deny par
   défaut sur son namespace entier — c'est pour ça que c'est Flux qui l'a révélé, mais le défaut est
   **systémique** : vérifié, le flux vers coredns portait aussi `world-ipv4`, il passait seulement
   parce qu'aucune politique ne l'arrêtait.

**Ce qui ressemblait à un bug cilium et n'en était pas** : l'ipcache disait `reserved:host` dans la
vue agent **et** dans la carte BPF ; la carte de politique de l'endpoint autorisait `reserved:host`
sur `ANY` — avec **0 paquet** — et le verdict disait quand même `world-ipv4`. Rien n'était
incohérent : le marquage n'était simplement jamais appliqué.

## Le correctif

Dans `linux.kernel_modules` du profil Incus (`InstanceGrow.nodeProfileConfig`) :
`nft_compat` · `xt_mark` · `xt_CT` · `xt_TPROXY` · `xt_comment` · `xt_conntrack`, plus
**`xfrm_user`** (préalable distinct : le route reconciler appelle `safenetlink.NewHandle(nil)`, qui
ouvre une socket pour *toutes* les familles netlink dont `NETLINK_XFRM` ; sans lui l'agent meurt au
démarrage sur `protocol not supported`).

Charger `nft_compat` a fait passer le compte de règles **10 → 34** et Flux à **1/1**, sans aucun
autre changement. `xt_TPROXY` était ensuite requis par la règle du proxy DNS.

⚠️ Domicile : **le profil Incus, pas la config NixOS de l'hyperviseur.** Un conteneur ne peut pas
`modprobe` — il n'a ni noyau ni arbre de modules — donc seul le daemon Incus peut le faire, sur
l'hôte, au démarrage du conteneur. Mécanisme vérifié : les neuf entrées préexistantes du profil sont
toutes vivantes dans `lsmod` alors qu'aucune n'est déclarée dans ndh.

## Pistes ÉCARTÉES, à ne pas rejouer

Les modules **ipset** (`ip_set` chargé n'a rien changé ; l'ancien commentaire de
`CiliumConfigManifestsUnit` qui les accusait était **faux**, il a été réécrit) · le masquerading
iptables (sans `bpf.masquerade` l'agent meurt sur `error while creating ipset cilium_node_set_v4`) ·
le host-routing classique (`enable-host-legacy-routing=true` : sans effet) · **`allow-localhost`**
(inopérant **par construction** — il porte sur l'identité `host`, précisément celle qui n'était pas
résolue) · la recréation des pods · l'auto-détection d'interfaces de cilium (elle ne voyait que
`lan0` sur un nœud à deux NIC ; corrigé par `devices`, sans effet sur la panne) · les options
nftables de rke2 (`proxy-mode=nftables`, `enableNFTables`, `linuxDataplane`) qui concernent
**kube-proxy et les autres CNI**, la doc disant de cilium « uses eBPF filtering; no nftables
configuration needed » · la migration ingress→traefik (lue : ne parle ni de politiques ni de sondes).

## La méthode qui a tranché

Aucun réglage ne l'a trouvé — c'est la **lecture du code C de cilium** qui l'a fait, en trois sauts :
le drop nommait `bpf_lxc.c:2405` → `resolve_srcid_ipv4()` dans `bpf/bpf_host.c` montre que l'ipcache
est **ignoré** quand il dit `HOST_ID` → l'appelant `cil_from_host` calcule son argument par
`inherit_identity_from_host()` dans `bpf/lib/identity.h`, dont le `else` rend `WORLD_ID`. À partir de
là, la question « qui pose le mark ? » a une seule réponse : les règles iptables de cilium.

Outils décisifs : `cilium-dbg monitor --type policy-verdict` (le verdict nomme l'identité distante),
`cilium-dbg bpf policy get <ep>` (montre `Allow Ingress reserved:host` avec son compteur de paquets),
`cilium-dbg bpf ipcache list`, et la **santé de l'agent** — c'est `[Degraded]
iptables-reconciliation-loop` qui portait la cause, alors que `cilium-dbg status --brief` disait `OK`.

See [[unmanaged-mac-ssh-material-chain]] [[destructive-gate-needs-three-valued-probe]].
