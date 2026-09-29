---
name: kubeconfig-context-per-cluster-intention
description: "Les TROIS contextes mgmt sont livrés (2026-09-23, rke2lab 9f392870a) ; il ne manque que bioskop-wrkld-vip, bloqué par UNE asymétrie : ADMIN_CREDENTIALS est une coordonnée de cellier unique là où WORKLOAD_CLUSTER_CAS est déjà une liste"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T09:44:52.662Z
---

Demandé par l'utilisateur pour requêter chaque cluster depuis bioskop. **Livré aux trois quarts le
2026-09-23** (rke2lab `107b39069` le renderer, `9f392870a` les contextes).

## Ce qui est livré

`.local.d/kubeconfig.yaml` porte **trois** contextes, ordonnés par fiabilité mesurée depuis le Mac :

| contexte | endpoint | mesure |
|---|---|---|
| `bioskop-mgmt` (**current**) | `192.168.1.131:6443` — l'adresse LAN du netplan | **401 en 6,5 ms**, et répond **à travers** un cold start |
| `bioskop-mgmt-vip` | `10.80.7.10:6443` — la VIP kube-vip | 401 en 39 ms via le Connector ; survit au **remplacement** du nœud, mais muet pendant des minutes après un cold start |
| `bioskop-mgmt-mdns` | `bioskop-mgmt-master.local:6443` | dernier, jamais current : résout vers l'IPv6 **globale** depuis le Mac, pas le LAN |

★ Les trois sont dans les SANs du certificat apiserver, donc **aucun** `insecure-skip-tls-verify`.
Et le nom mDNS se clave sur le **nom du nœud** — admissible ici et nulle part ailleurs : un nœud de
mgmt est un **pet adopté** au nom déterministe, pas le bétail CAPI que l'interdit visait.

## ★ Le design qui a débloqué : l'hôte LIT la projection

Le blocage n'était pas le rendu mais la **connaissance** des endpoints. L'hôte ne compile pas contre
netplan (`runtime` scope) et ne doit pas ré-énoncer sa loi d'adressage ; et le domaine netplan **ne
garde volontairement aucune coordonnée de cellier** (« SYNTHESISES-and-MATERIALISES … stores no
harvest behind the cellar »).

La réponse était sous le nez : il matérialise dans `network-blueprint.json`, committé, **déjà**
consommé tel quel par ndh. L'hôte le lit, résolu contre le CWD comme `Main` résout `.secrets` (le
code dit « NO worktreeRoot — that is the worktree soil's harvest, no longer a host-carried scalar »).
Il manquait **une** valeur : `vipHost`, cluster-scoped, ajoutée aux `ips` de chaque nœud.

⚠️ Absence de fichier ou de clé ⇒ **throw**, pas de repli : le fichier est committé et une porte nix
(`blueprint-fresh`) échoue déjà s'il dérive, donc son absence signe un arbre cassé — et une
kubeconfig qui pointe vers quelque chose de plausible et faux est pire qu'aucune.

## Ce qui reste — UNE asymétrie, dans le sceau PKI

`bioskop-wrkld-vip`. Un cluster de charge a ses **propres** CA, donc il lui faut son propre certificat
admin, signé par son `clientCa` (présent dans `WORKLOAD_CLUSTER_CAS` avec sa clé, par cluster).

Le blocage, nommé : **`ClusterPkiCoordinate.ADMIN_CREDENTIALS` est une coordonnée UNIQUE** (le
cluster de soi) là où `WORKLOAD_CLUSTER_CAS` est déjà une liste. Lever ça = modifier le sceau.

Bonne nouvelle : `ClusterPkiSealScenario` a déjà tout sous la main — les deux coordonnées ET les
cibles de charge, sur lesquelles il itère déjà (sa ligne ~215 : « the clusters newly appearing in
workloadTargets »). Ce n'est pas un gros chantier, c'est juste celui où une erreur coûte l'accès aux
clusters.

## L'outillage prêt pour ça

`AdminCredentials.kubeconfig(clusterName, List<Access>)` — `Access(contextName, server)`, le premier
étant `current-context`, **un** user pour tous (les credentials sont endpoint-indépendants, c'est
précisément pourquoi plusieurs contextes les partagent). Quatre tests par SECTION dans
`AdminCredentialsKubeconfigTest` : compter `- name: <cluster>` sur tout le document ne prouve rien,
il apparaît dans `clusters` **et** dans `contexts` (mon premier jet a échoué là-dessus).

⚠️ `manifests-contract`'s `OperatorPkiMaterial` porte les mêmes trois PEM de l'autre côté du seam
manifests et **duplique** le gabarit pour son cas mono-endpoint. Son consommateur (le Secret
`<cluster>-kubeconfig`) ne veut qu'un contexte, donc rien à faire — à fusionner au troisième.

See [[funnel-identity-is-per-cluster]] [[netplan-projection-described-hosts]]
[[incus-bridge-dnsmasq-is-every-pod-first-resolver]].
