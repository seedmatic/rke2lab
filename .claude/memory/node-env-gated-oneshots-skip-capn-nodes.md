---
name: node-env-gated-oneshots-skip-capn-nodes
description: "Tout oneshot nixos gardé par ConditionPathExists=/var/lib/rke2lab/node.env est INERTE sur un nœud CAPN — c'est une classe de défaut, pas un incident : providerID (réparé le 09-23) puis les node-labels (réparé le 09-23) en sont deux instances"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T12:46:51.674Z
---

`nixos/rke2.nix` porte trois oneshots par nœud — `rke2lab-node-labels`,
`rke2lab-provider-id`, `rke2lab-node-ip` — tous gardés par
`unitConfig.ConditionPathExists = "/var/lib/rke2lab/node.env"`. Seul un nœud **grow par
l'hôte** a ce fichier. Un nœud provisionné par CAPN les **saute tous les trois, en silence**.

★ Ce n'est pas un incident, c'est une **classe de défaut**. Deux instances trouvées le même
jour :

| oneshot sauté | symptôme observé | résolution |
|---|---|---|
| `rke2lab-provider-id` | cluster entièrement Ready, et CAPI le dit indisponible (`Waiting for a Node with spec.providerID…`) | CAPN l'estampille : `cloudProviderNodePatch: true` sur le `LXCCluster` |
| `rke2lab-node-labels` | **trois** Kustomizations networking en échec, d'apparence indépendantes | `PoolIntention.spec.nodeLabels` → `agentConfig.nodeLabels` (rke2lab `6ced3bf06`, seed-incluster `3c1637315`) |

Le troisième, `rke2lab-node-ip`, n'a pas encore été audité sur un nœud CAPN. **À vérifier :**
son absence provoquait, sur un pet, un `--node-ip` auto-détecté sur `cilium_host` → mismatch
x509 cassant `kubectl logs/exec`. Si un nœud CAPN n'a pas d'équivalent, le défaut est latent.

## La cascade du label flox — trois échecs, une racine

Mesuré le 2026-09-23 sur `bioskop-wrkld` :
`mesh-headscale`, `networking-kdns` et `runtime-seed-incluster-operators` stallés
(`Deployment … status: 'Failed'`), plus six Kustomizations tailscale bloquées en dépendance.
Ça ressemblait à trois pannes réseau distinctes. Une seule cause :

```
label flox.seedmatic.io/enabled absent du nœud
  → DaemonSet flox-controller : nodeSelector ne matche rien, DESIRED 0
    → aucun env flox GC-rooté sous /nix/var/nix/gcroots/flox-runtime/env
      → plugin NRI : « failed to resolve flox environment: flox environment
        toolchains/kube not GC-rooted » sur CHAQUE conteneur flox-carrier
```

347 redémarrages sur 78 min. ⚠️ **Leçon de diagnostic** : plusieurs Kustomizations en échec
dans des domaines différents, toutes sur des Deployments qui ne démarrent pas, pointent vers
une porte d'admission commune (ici le NRI), pas vers N bugs. Lire l'événement `kubelet`
plutôt que le statut Flux.

## Pourquoi `agentConfig.nodeLabels` et pas un fragment `config.yaml.d`

Mon premier jet ajoutait `20-node-labels.yaml` à `RuntimeRke2ConfigManifestsUnit` (les
fragments de branche que seed-incluster reverse en `spec.files`). L'utilisateur a objecté —
et avait raison deux fois :

1. CAPRKE2 **possède le champ** : `RKE2ControlPlane.spec.agentConfig.nodeLabels` (vérifié
   dans le schéma du CRD, avec `nodeAnnotations` et `nodeTaints`). Passer par un fragment,
   c'est atteindre le même flag kubelet en contournant le contrat du provider.
2. Collision : l'oneshot écrit `30-node-labels.yaml`, aussi sous `node-label`. Et
   `config.yaml.d` **remplace** une clé de liste par la valeur du fichier
   **alphabétiquement dernier** (règle déjà documentée dans `nixos/rke2.nix` pour
   `kubelet-arg+`, où la forme `+` APPEND). Donc lequel des deux gagne aurait été une
   propriété d'un nom de fichier.

★ La granularité juste est le **pool**, parce que c'est celle d'`agentConfig` : un
`RKE2ControlPlane` par pool control-plane, un `RKE2ConfigTemplate` par pool worker. C'est
exactement la promotion que la note « promote to a devlxd per-node key once node roles
diverge » de `nixos/rke2.nix` attendait.

## ⚠️ Le label n'atteint PAS un nœud déjà inscrit

kubelet n'applique `--node-labels` qu'à la **première** inscription du nœud (écrit dans
`nixos/rke2.nix:143`, leçon déjà payée : « a fragment delivered later arrives AFTER the join
and is silently ignored »). Donc la déclaration se réconcilie sans re-grow — branche → Flux →
`spec.files`/`agentConfig` — mais le label sur un nœud vivant demande son **remplacement**,
ou un `kubectl label node` posé à la main une fois.

C'est la différence qui compte et qui a été discutée : **la déclaration réconcilie, le label
sur un nœud déjà joint non.** Un label de `nodeSelector` peut d'ailleurs être posé à chaud
sans dommage — contrairement au `providerID`, qui est immuable et doit être juste dès la
première inscription.

See [[funnel-identity-is-per-cluster]] [[kubeconfig-context-per-cluster-intention]]
[[hub:MEMORY]].
