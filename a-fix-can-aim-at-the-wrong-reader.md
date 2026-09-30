---
name: a-fix-can-aim-at-the-wrong-reader
description: "★★★ Un défaut a SURVÉCU à son propre correctif (2026-09-30) : la valeur a été corrigée sur l'objet que personne ne lit. La famille des quatre défauts de point de vue, et le réflexe — qui OUVRE la socket ?"
metadata:
  node_type: memory
  type: feedback
---

**2026-09-30.** Quatre défauts de la branche `feature/viewpoint-separation` partagent une forme :
*une valeur juste du point de vue de qui l'a calculée, fausse du point de vue du sujet qu'elle
décrit.* Le quatrième enseigne ce que les trois premiers n'enseignaient pas.

| # | La valeur | Juste pour | Fausse pour |
|---|---|---|---|
| 1 | `resolveRoster` branche SELF prenant `spec.nodes` | le RENDERER, qui a écrit ce seed | le cluster né sous un nom frappé par CAPI. Corrigé (forme B, seed-incluster `4af4e79c9`) |
| 2 | `ClusterIntention.spec.remote.endpoint` vide en vue de soi | la RACINE, moteur local | un sous-plan. Corrigé `9e4f1b131` |
| 3 | `PoolReflection` dictant le COMPTE de replicas du RCP | l'OBSERVATEUR, qui a vu un nœud | l'intention, qui en déclarait trois. Forme A, pas faite |
| 4 | `server` du Secret d'identité, depuis `material.serverAddress()` | l'hôte qui a FRAPPÉ le matériel — juste pour la racine **par accident**, elle frappe le sien | tout enfant. Corrigé `75dc18404` |

## ★★★ Le n°4 a survécu au correctif du n°2, et s'est présenté à l'identique

`9e4f1b131` a corrigé l'endpoint sur la `ClusterIntention` — **et personne ne dial une intention.**
L'adresse sur laquelle CAPN ouvre réellement une socket vient du Secret d'identité, et ce champ
portait encore celle du frappeur. Symptôme inchangé : `nikopol-mgmt` bloqué en `Adopting`
(`0/1 present, 1 pending`), son CAPN en boucle sur
`Get "https://nixos.bioskop:8443/1.0": dial tcp 172.16.0.1:8443: i/o timeout`, pendant que son
PARENT voyait la même instance `Running` et provisionnée.

La leçon n'est pas « vérifier deux fois ». C'est : **un correctif de la bonne FORME peut viser le
mauvais LECTEUR.** Énoncer une valeur ne retire le point de vue que pour le lecteur à qui on
l'énonce.

## Le réflexe

Avant de corriger une valeur « mal vue » : **demander qui OUVRE la socket / lit le champ en
dernier**, pas quel objet le déclare le plus proprement. Puis calculer la valeur **UNE fois** et la
donner à tous ses lecteurs — ici l'endpoint est UNE seule expression, passée à l'intention *et* au
Secret, qui ne peuvent plus diverger.

★ Et noter ce qui n'était PAS faux, ça resserre la recherche la fois suivante : le roster (la forme B
avait bien observé le vrai nœud), le `target` de placement, les quatre CA, le bundle d'amorçage et
l'adoption par le parent — tous corrects. **Seule l'adresse.**

Gravé dans `docs/architecture/cluster-api/cluster-seeding-controller.adoc` §
`viewpoint-family`. See [[measure-the-derived-value-not-the-assumed-one]].
