---
name: cold-start-2026-09-27-nikopol-mgmt
description: "★★★ Cold start RÉUSSI le 2026-09-27 (2 clusters Provisioned) + nikopol-mgmt DÉCLARÉ et adopté par bioskop-mgmt, bloqué sur la seule ressource côté hôte : le profil incus node-<cluster>. Correctif écrit (rke2lab 70489ed9c), il ne manque qu'un grow. Plus : les faits incus sur les images et le cluster à deux membres."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T19:21:26.202Z
---

Soirée du **2026-09-27**. Meilleur cold start à ce jour, puis premier `nikopol-mgmt`.

## ★★★ L'ÉTAT, et la seule chose qui manque

```
bioskop-mgmt   Provisioned · node Ready    VIP 10.80.7.10  ✓
bioskop-wrkld  Provisioned · Remote=True   VIP 10.80.15.10 ✓
nikopol-mgmt   Provisioned · Adopting      VIP 10.80.23.10
incus          bioskop-nixos database-leader · nikopol-nixos database-client
               scheduler.instance=manual sur nikopol · image sur LES DEUX membres
```

`nikopol-mgmt` a traversé toute la chaîne — config de stack → rendu opérateur → branche manifests →
rendu in-cluster → Flux → seed-incluster → CR CAPI — avec `kind: management`, **un seul** pet, et
`target: nikopol-nixos`. Il bute sur : `Requested profile "node-nikopol-mgmt" doesn't exist`.

★ **Il ne manque qu'un `grow` sur bioskop.** Le profil est une ressource Pulumi, et rke2lab
`70489ed9c` fait désormais qu'un grow en crée un par slot **réalisé** (donc `node-nikopol-mgmt` et
`node-nikopol-wrkld`, ce dernier inerte).

## ★★ Ce que le correctif a appris (rke2lab 76c671875 + 70489ed9c)

- **Le côté k8s s'était généralisé aux enfants, le côté HÔTE non.** Les profils n'étaient assurés que
  pour les clusters **co-localisés** avec le grow — vrai tant qu'un enfant partageait le bare-metal.
- ⚠️ **Élargir la carte existante aurait été faux deux fois** : `clusterBridges` pilote aussi
  `ensureNetworks`, donc un cluster non co-localisé y aurait créé un bridge sur le mauvais hôte — et
  **collision**, le nom de bridge étant *role-scoped* (`vmnet-mgmt`), donc deux clusters mgmt sur des
  membres différents veulent le même nom. D'où deux ensembles séparés : `clusterBridges` (créé ICI)
  et `profiledClusters` (doit seulement EXISTER).
- ★ L'ensemble est **dérivé, pas acheminé** : `clustersInFleet()` = slots réalisés × rôles, donc rien
  ne traverse la membrane hôte↔OSGi et **déclarer un cluster ne demandera plus jamais d'acte hôte**.
  Un profil est une définition de NIC ; un profil inutilisé est inerte. Sa valeur est le **nom** de
  bridge, qui se résout PAR MEMBRE — une instance ciblée sur nikopol prend le `vmnet-mgmt` de nikopol.
- `HOST_SLOTS` est désormais **une** liste de `HostRef(name, id, realised)` dont `HOST_IDS` dérive
  (forme sur le fil figée : ndh l'importe à l'éval du flake). `test` est carvé et **non réalisé** —
  la réalisation est un ATTRIBUT, plus un test sur le nom.
- La forme du cluster enfant dérive du rôle : `mgmt` → 1 nœud + `"management"`, `wrkld` → 3 +
  `"workload"`. Sans quoi `nikopol-mgmt` naissait à **trois** nœuds en s'annonçant workload.

## ★★ Faits incus à ne pas re-découvrir

- **Une image = des FICHIERS root-only** `<fp>` + `<fp>.rootfs` dans `/var/lib/incus/images/`. Le
  dataset `<pool>/incus/images/<fp>` est le volume **déplié**, créé au premier usage sur ce membre —
  **jamais** un indicateur de présence d'image.
- `cluster.images_minimal_replica = -1` **préfetche vraiment** : l'image était sur les deux membres.
  La tâche `autoSyncImagesTask` est **horaire** et **réservée au leader**, journalise en `Info`, et
  incusd n'émet rien sous `Warning` → ses ticks sont invisibles sans `--verbose` (aucune option
  `virtualisation.incus` ne l'expose : ce serait une surcharge d'`ExecStart`).
- `incus image list` est **par projet** : nos images vivent dans `rke2lab`, pas `default`.
- ⚠️ `incus-cluster-roles` (ndh) ne vit que sur le membre **bootstrap** et itère les autres — c'est
  bioskop qui pose le `database-client` et le `scheduler.instance=manual` de nikopol, jamais nikopol.
  Il a fallu le relancer après la jointure ; il refuse de réussir si un itinérant est encore votant.

## Reste ouvert

- **Le grow sur bioskop** → crée le profil, débloque la naissance. C'est l'unique étape.
- Le répertoire de rendu `cluster-api-workload` et la classe `ClusterApiWorkloadManifestsUnit`
  nomment « workload » ce qui veut dire **enfant**. Renommer le dossier n'est PAS gratuit : chaque
  répertoire a sa Kustomization avec `prune: true`, donc ce serait un cycle suppression/recréation.
- `ClusterRole.of` garde son repli `default -> MGMT`. Le rendre bruyant a été **tenté et annulé** : le
  scion a plus d'un appelant sans cluster (nom vide légitime). `ofToken` est le chemin loud à préférer.
- `PaxLoggingJulCaptureTest` échoue sous `-T1C` et passe seul — course de capture de logs.

See [[measure-the-derived-value-not-the-assumed-one]] [[incus-placement-must-be-stated-not-inferred]]
[[nikopol-mgmt-federation-clustermesh-first-case]]
