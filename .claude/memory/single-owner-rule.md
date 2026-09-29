---
name: single-owner-rule
description: "La règle qui explique CINQ défauts trouvés le 2026-09-21 : deux propriétaires d'une même chose finissent toujours par se battre. Le réflexe correct est de supprimer un propriétaire, jamais d'ordonner les deux — et le codebase l'énonçait déjà"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-22T21:25:35.741Z
---

Constat de l'utilisateur au terme de la soirée du 2026-09-21 : « on vient de re-découvrir la règle du
single owner ». Les cinq défauts trouvés ce soir sont **le même défaut**.

| Le défaut | Les deux propriétaires | Le correctif |
|---|---|---|
| `persist/funnel-cert` plat | deux clusters, un dataset + un FQDN | par cluster |
| pool openebs | tous les clusters, un `"master"` codé en dur | par cluster |
| affinité du PV funnel | le rendu ET le runtime prétendent connaître le nœud | le contrôleur possède |
| clés SSH d'hôte | cloud-init (`cc_ssh`) ET NixOS (`sshd-keygen.service`) | NixOS seul |
| nommage des pets | on a failli le prendre à `RKE2ControlPlane` | on annote, il possède |

Et la règle **était déjà écrite** dans `docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc`,
comme justification du second poseur : *« Single ownership is preserved with no new rule »* — une
ressource marquée quitte la branche, donc Flux ne la voit sur aucun des deux poseurs.

## Pourquoi c'est une règle de méthode, pas une observation

**Le réflexe correct est de supprimer un propriétaire, pas d'ordonner les deux.** J'ai proposé deux
fois l'inverse, et les deux fois l'utilisateur a corrigé :

- Sur les clés SSH, j'ai proposé `Before=` + `wantedBy=` entre `sshd-keygen.service` et
  `cloud-config.service`. Réponse : « on pose les choses à l'inverse non ? on met une dépendance
  explicite qui rend la cloud config mandatory. » Juste : ça faisait de la génération des clés d'hôte
  un satellite de cloud-init. Le bon geste était de retirer la génération à cloud-init
  (`ssh_deletekeys=false` + `ssh_genkeytypes=[]` dans la cloud.cfg de l'IMAGE).
- Sur le nommage, j'ai proposé que seed-incluster crée les Machines de greenfield avec des noms de
  pets. Réponse : « si on prend cette voie, on skip le control plane de rke2 non ? » Juste :
  `RKE2ControlPlane` nomme parce qu'il possède init-vs-join, le quorum etcd, le scale, les rollouts et
  la remédiation. On **annote** ce qu'il possède (un label de Machine propagé au Node), on ne le
  remplace pas.

⚠️ **Deux autres anti-réflexes, vus ce soir aussi :**

- Une **condition systemd** ne répare pas une course. L'unité `sshd-keygen` portait DÉJÀ
  `ConditionFileNotEmpty=|!` sur les deux chemins de clés : évaluée au démarrage, elle ne peut pas voir
  une écriture qui arrive 0,9 s plus tard. Une condition exprime un état, pas une exclusion mutuelle.
- Un **littéral** est souvent l'aveu d'un propriétaire implicite, pas une négligence.
  `NODE_NAME = "bioskop-mgmt-master"` et `controlNodePool("master")` disaient tous deux « cette unité
  n'a jamais été pensée pour tourner ailleurs ». Ne pas les paramétrer par réflexe : d'abord demander
  *qui doit posséder ça*, la réponse change souvent le correctif.

## Quatre de plus, le 2026-09-22 — la règle tient

| Le défaut | Les deux propriétaires | Le correctif |
|---|---|---|
| `insecure_ssl` du webhook | une constante d'edge ré-affirmée à CHAQUE grow **et** l'opérateur à la main | une posture déclarée une fois, les deux moitiés en dérivent |
| `leaf()` qui change de type | l'enum `FunnelLeaf` **et** le record `Funnel`, même nom d'accesseur | renommer en `leafName()` — deux noms ne se confondent pas |
| roster de nœuds | `CANONICAL_NODE_NAMES` (six, « every cluster ») **et** le découpage LAN (un, `/29`) | `ClusterTopology.of(role)`, la liste devient un surensemble |
| adressage exporté | l'exportateur redisait la table hôte→id et nœud→id que le netplan possède | dérivé du blueprint |

⚠️ **Deux enseignements neufs, tous deux sur ce qui rend un double propriétaire INVISIBLE :**

- **Un accesseur qui change de type sous le même nom.** `funnel.leaf()` rendait une String, puis l'enum.
  `"job-" + funnel.leaf()` a continué de compiler et a rendu `job-PIPELINES_WEBHOOK`, refusé par
  Kubernetes. La concaténation avale la différence ; seul un nom différent la rend visible.
- **Une valeur gonflée peut masquer un débordement.** Les ids de workers erronés de l'exportateur (10, 11)
  plaçaient les adresses assez loin pour ressembler à une tranche plus large. Avec les vrais ids, le
  débordement sautait aux yeux. Une donnée fausse peut *camoufler* un second défaut — corriger l'une
  révèle l'autre, ce qui est une raison de plus de ne pas corriger « en gros ».

Et un corollaire de méthode confirmé trois fois aujourd'hui : **une réplique managée ne peut pas recevoir
d'identité par nœud depuis un gabarit** — `spec.files` de CAPRKE2, le label pet, et maintenant
`LXCMachineTemplate.devices`. Quand on cherche à donner une identité par nœud, la question n'est pas
« quel champ », c'est « qui crée l'objet ».

## Le test à appliquer

Devant un symptôme de concurrence, de dérive ou de collision, poser la question avant de chercher un
ordonnancement : **qui possède cette chose, et y en a-t-il deux ?** Si deux, en retirer un. Ordonner
deux propriétaires, c'est acheter du temps au prix d'un couplage — et ça remord au boot suivant, par
tirage au sort.

See [[funnel-identity-is-per-cluster]] [[workload-bootstrap-chain-cilium-kubevip]]
[[rke2-peer-join-config-gap]].
