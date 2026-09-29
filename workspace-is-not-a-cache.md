---
name: workspace-is-not-a-cache
description: "Un workspace Tekton est un brouillon pour UN run, un cache est de l'état porté ENTRE les runs — déclarer un cache comme workspace fait entrer l'assistant d'affinité et toute la cascade qui suit"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-24T16:22:35.757Z
---

Diagnostic de l'utilisateur, le 2026-09-24 : *« on s'est trompé, on a confondu un
workspace et une cache »*. C'était la cause, pas un détail.

## La cascade, dans l'ordre

Le cache Maven du pipeline de rendu était déclaré comme **workspace Tekton**. Tout
le reste en découle mécaniquement :

1. l'**assistant d'affinité** de Tekton co-monte *tout* workspace adossé à un PVC,
   à côté du pod de tâche → un cache à écrivain unique se retrouve avec **deux**
   monteurs ;
2. donc il faut la classe `openebs-zfs-shared` (`shared: yes`, bind-mount), sans
   quoi la classe exclusive échoue « device already mounted » ;
3. or cette classe n'existe que sur le tier **ephemeral** ;
4. où un `pvc-<uuid>` dynamique **fuit un dataset par cold-start** (mesuré : 8
   datasets pour 1 claim vivante) et un PV épinglé au nœud **échoue au premier roll**
   du control plane.

Aucun maillon ne parle de cache. Tout vient d'avoir répondu « workspace » à une
question qui était « cache ».

## La règle

- **workspace** = brouillon d'UN run, partagé entre ses tâches → `source`, per-run,
  `volumeClaimTemplate`. L'assistant d'affinité est *voulu*.
- **cache** = état porté ENTRE les runs → volume **brut** monté par la seule étape
  qui l'écrit. L'assistant ne doit jamais le voir.

Et les deux caches du rendu s'échappent par des routes **différentes** : le store
nix n'est pas dans le pod du tout (c'est l'overlay `/nix` du **nœud**, câblé par le
plugin NRI flox, envs provisionnés dans le namespace du nœud par `nsenter`) ; le
cache Maven ne peut pas prendre cette route, parce qu'un cache qui doit **survivre
au nœud** ne peut pas vivre sur le nœud.

## La suite, décidée mais pas écrite

La stratégie retenue est **overlay + publication du delta** : tout le monde lit la
base, seule l'écriture finale est sérialisée. Deux moitiés, deux mécanismes :

| | mécanisme | volume partagé |
|---|---|---|
| build-cache (résultats) | le cache **distant** de `maven-build-cache-extension`, save opt-in (`-Dmaven.build.cache.remote.save.enabled=true`) — le patron de BuildFetch : jetons `readonly` par défaut, `readwrite` pour le CI | aucun |
| dépôt local (dépendances) | dépôt **chaîné** Maven 3.9 : `maven.repo.local.tail` en base lecture, `maven.repo.local` en head | oui, et c'est là que va le verrou |

L'extension est **déjà** configurée ici (`.mvn/maven-build-cache-config.xml`, et la
commande de build passe `-Dmaven.build.cache.skipCache=false`) — donc le cache
distant est une config, pas une adoption.

Deux propriétés qui rendent le schéma *sûr* et pas seulement pratique : un
coordonnée Maven publiée est **immuable**, donc la fusion est une union add-only et
deux runs qui ajoutent le même artefact écrivent les mêmes octets (les SNAPSHOT et
les fichiers de métadonnées sont l'exception) ; et côté Kubernetes le mutex natif
est `ReadWriteOncePod` — à poser sur le pod qui **écrit**, pas sur celui qui
construit. ⚠️ Le support `SINGLE_NODE_SINGLE_WRITER` d'openebs-zfs n'est pas vérifié.

Enfin, `CSI` sait **cloner** (`PVC.spec.dataSource`) mais n'a aucun verbe pour
**promouvoir** un delta — cette moitié est à nous, et c'est la même forme que la pile
de couches EROFS du `/nix/store` : on ajoute une couche, on compacte plus tard.

⚠️ `concurrency 1` sur le `Repository` PaC n'est pas une précaution, c'est l'autre
moitié du choix d'un cache mutable partagé — et un plafond de débit, puisque le rendu
est **par cluster**.

See [[volume-owner-is-who-mounted-not-where-data-is]] [[erofs-store-layer-stack-vision]].
