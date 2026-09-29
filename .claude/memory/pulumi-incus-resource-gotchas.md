---
name: pulumi-incus-resource-gotchas
description: "Trois pièges du provider incus sous Pulumi, tous corrigés le 2026-09-21 : adoption-par-omission auto-destructrice, replaceOnChanges trop large qui tuait le nœud à chaque up, et importId résiduel qui replanifie un remplacement"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T19:16:00.920Z
---

Trois défauts trouvés en une soirée sur `InstanceGrow`, tous **démontrés par `pulumi preview`**
(exécution à blanc, donc gratuite — c'est l'outil de diagnostic à réflexe ici).

## 1. L'adoption-par-omission se détruit elle-même

`if (existingProfileId(...).isPresent()) return Output.of(name);` interroge le **daemon**, pas l'état
Pulumi. Donc : Pulumi crée le profil → le daemon l'a → le run SUIVANT saute la déclaration → la
ressource **quitte l'état** (`- 3 to delete`, `delete[retain]`, observé). Le garde provoquait la perte
d'état qu'il prétendait contourner, et `config` ne réconciliait jamais.

Conséquence vécue : six modules noyau que cilium exige sur un hôte nftables-only étaient dans le code
et **absents du profil vivant**, invisibles jusqu'à ce qu'un reboot de l'hôte couche cilium.

Correctif (`a7db9c3f9`) : **déclarer toujours**, sans `importId`. Un profil déjà dans l'état se
contente de différer. Prix accepté : un profil présent dans le daemon mais absent de l'état échoue
bruyamment (« already exists ») — préférable au silencieusement non-géré.

⚠️ **Piège de méthode** : ma première tentative changeait DEUX choses (ajouter `importId` **et**
réconcilier `config`) et j'ai conclu que la réconciliation churnait. C'était l'import seul.

## 2. `replaceOnChanges` trop large tuait le nœud à chaque `up`

L'instance déclarait `replaceOnChanges(["config", "config.*"])` pour une intention beaucoup plus
étroite : re-provisionner quand le checksum de build de l'image change. Or **tout le cloud-init vit
dans `config`**, et le token github du nœud est minté à neuf à chaque run (`Persistence.TRANSIENT`,
par conception). Donc chaque run voyait `~config` → `replace` + `delete original`, et l'instance n'a
**pas** de `retainOnDelete` : kill-and-recreate du nœud de contrôle à chaque `up`.

Invisible jusque-là parce que le preview plantait avant d'atteindre un plan, et que le `up` du jour
avait créé l'instance à neuf.

Correctif (`d429edf06`) : resserrer sur la seule clé qui le justifie —
`replaceOnChanges(["config[\\"user.rke2lab.imageBuildChecksum\\"]"])`. Preview : `update` au lieu de
`replace`.

**NE PAS** déplacer le token hors du `config` : les clés devlxd `user.rke2lab.*` **SONT** du config
d'instance, donc churneraient pareil — et l'identité a justement été consolidée *vers* le cloud-init,
canal uniforme partagé avec CAPN/CAPRKE2.

## 3. Un `importId` résiduel replanifie un remplacement à vide

Sur une ressource que Pulumi tient **déjà** dans son état, laisser la déclaration d'import fait
planifier un remplacement **sans aucun diff de propriété** : `importing replacement` →
`replacing[retain]` → `delete original[retain]`. Message du moteur : *« previously-imported resources
that still specify an ID may not be replaced; please remove the `import` declaration »*.

Donc `importId` sert l'adoption **une fois**, puis doit disparaître. Corrigé pour le `Certificate`
(`e90733d65`). Un doublon reste alors dans l'état (l'ancien marqué `delete: True, retain: True`) — pur
ménage, effacé au `up` suivant, l'entrée du daemon intacte.

## Et un quatrième, celui-là non corrigeable : `devices`

Incus stocke les devices en **map** (clé = nom, non ordonnée) ; le provider les modélise en **List
ordonnée**. Un refresh les rend donc dans l'ordre du daemon, jamais le déclaré, et Pulumi lit toute la
liste comme changée → remplacement à chaque run. Aucun ordre déclaré ne peut gagner : `devices` reste
dans `ignoreChanges`, et **ce n'est pas une dérive réelle** mais un artefact de modélisation. Même
famille que `project`, relu comme `null` alors qu'il est ForceNew — c'est LUI qui condamne l'import des
profils, re-testé contre 1.2.0 et toujours vrai.

## Le bump 1.2.0, honnêtement

Fait pour obtenir `getCertificate` (nouveau en 1.2.0) et adopter l'entrée de confiance par `importId`.
Or l'`importId` s'est révélé être le problème (§3) et le lookup est supprimé. **Le bump n'a pas servi
la raison invoquée.** Il n'est pas nuisible et fut bon marché — **41 fichiers** touchés, pas les 179
que la mémoire annonçait ; méthode : tag `recovery/incus-sdk-1.1.1-before-bump`, suppression du
dossier, régénération, récupération de `pom.xml`/`README.md`/`.gitattributes` depuis le tag. ⚠️
`version.txt` n'est **pas** produit par le générateur : il est à recréer à la main sous
`src/main/resources/com/pulumi/incus/`, sans saut de ligne final, sinon seed-master meurt sur
`expected resource 'com/pulumi/incus/version.txt' on Classpath`.

See [[incus-trust-store-is-destroyable-state]] [[workload-bootstrap-chain-cilium-kubevip]].
