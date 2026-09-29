---
name: node-image-published-as-cr
description: "★★ LIVRÉ le 2026-09-28 (non poussé) : l'image node-base devient un CR NodeImage, parce que rien ne la possédait et que son contrat d'exécution avait donc été restaté des deux côtés du seam — la copie in-cluster avait perdu xfrm_user + nft_compat + les xt_*, et c'est elle qui décide. Porte aussi le pilote producteur-typé et l'ordre de livraison (flake input épinglé par rev)."
metadata:
  node_type: memory
  type: project
  originSessionId: 635c1dc0-8df2-4b0a-95ae-504dc3770308
  modified: 2026-09-28T10:06:30.027Z
---

rke2lab `b11805cf3` + seed-incluster `d54da28aa`, **commités, PAS poussés**. Record de design
complet : `docs/architecture/cluster-api/node-image-lifecycle.adoc` (référencé dans
`docs/README.adoc` et depuis `cluster-seeding-controller.adoc`).

## ★ Le bug que le chantier a révélé

Le jeu de modules noyau était déclaré **deux fois** : 16 dans le profil incus `node-base` côté
hôte (`InstanceGrow`), **9** inline dans seed-incluster. Manquaient `xfrm_user`, `nft_compat`,
`xt_mark`, `xt_CT`, `xt_TPROXY`, `xt_comment`, `xt_conntrack`. ⚠️ Et c'est la copie courte qui
**gagne** : posée sur `LXCMachine.spec.config`, donc au niveau INSTANCE, qui l'emporte sur le
profil embarqué de CAPN *et* sur le nôtre. Conséquences déjà documentées ailleurs : sans
`xfrm_user` l'agent cilium ne démarre pas (« protocol not supported ») ; sans `nft_compat` + les
`xt_*`, 10 règles sur 34, trafic hôte→pod en `world-ipv4`, sondes kubelet refusées.
⚠️ **Jamais vérifié en vivant** — c'est une lecture de code. Le javadoc de `nodeProfileConfig()`
revendiquait « Single source for standalone + CAPN nodes », ce qui était faux depuis l'inline.

## La cause, et la forme retenue

L'image est **un** artefact et aucun objet ne la possédait : identité déchiquetée (empreinte
embarquée dans le spec de chaque pool, version rke2 en scalaire frère renormalisée aux **deux**
sites de rendu, projet dans un Secret d'identité) et 3 champs sur 6 n'alimentaient qu'un
ConfigMap `<cluster>-image-state` que **rien ne lisait** (vérifié sur les 3 dépôts) — fossile de
l'ère « Stage A→B / seed-peers ». Faute de propriétaire, le contrat d'exécution n'avait nulle part
où vivre → restaté → divergé.

Forme : `NodeRuntimeContract` (domaine incus) = LA définition + la traduction vers les clés incus ;
`InstanceGrow` la lit ; le scion la pousse dans l'amendement `IMAGE_STATE` ; `ImageState` la porte
(et y normalise le `v` une fois) ; le rendu émet un CR **`NodeImage`** ; `ImageRef` devient une
**vraie référence** (`{name}`), donc une génération est adressable. Unité ConfigMap supprimée.

## ★ Pilote « producteur structuré »

`NodeImageCr` est le **premier** de nos CRs rendu depuis un record (+ `toSpec()`, flatten-at-edge)
au lieu d'un `Map.of`. Adopté sur un objet **neuf** exprès, pour ne pas laisser l'ensemble existant
à moitié migré. Il achète la sécurité de type côté producteur ; il **ne prouve pas** l'accord avec
le schéma du CRD — cible gravée : POJOs générés par fabric8 `java-generator` sur les CRDs que le
build **stage déjà** dans `target/generated-resources/crds`. Voir le plan
`.claude/workload-provision-model-plan.md` § *REVISED PLAN … Java/fabric8*, dont le tempo dit
« finish the Go loop FIRST » — d'où le CRD écrit en **Go** et non en Java.

## ⚠️ Ce qui n'est pas vérifié, et l'ordre de livraison

- **Aucun test ne lie d'`ImageState`** → les unités cluster-api no-op sous test, donc *rien
  n'exerce le nouveau rendu*. Réacteur vert avec `-DskipTests=false` = compilation + non-régression,
  pas justesse. seed-incluster n'a **aucun test** du tout.
- **Chaîne de livraison** : rke2lab consomme seed-incluster comme **input flake épinglé par rev**
  (`github:seedmatic/rke2lab/seed-incluster`, branche orpheline). Donc : push de seed-incluster →
  bump `flake.lock` de rke2lab → le CRD et la RBAC voyagent (`nix run .#stage-seed-incluster-crd`).
  Tant que ce n'est pas fait, le CRD n'atteint aucun cluster.

## ★★ Le vestige : j'ai bâti du mauvais côté du seam (à corriger)

`docs/architecture/osgi/host-cellar-realisation-spec.adoc` dit que l'empreinte de l'image est une
**récolte conservée au cellier**, relue par adresse (`Cellar.fetch`), et nomme le champ `imageState`
un **vestige** du bootstrap hôte qui « **must STAY unwired** ». Or `ManifestsRunbookInput` porte
`@Amendment(IMAGE_STATE) Optional<ImageState>` — le code a recâblé ce que la spec interdit — et
**c'est ce chemin que mon incrément a approfondi** en y ajoutant `runtime` et en rendant le CR
depuis lui.

Le discriminateur de la spec donne la cible : un amendement porte le *où* et la *posture*, **jamais
une valeur cultivée** (empreinte, urn, checksum). Donc la cible n'est pas « supprimer l'amendement »
mais **séparer les deux natures que `ImageState` fusionne** : le contrat `runtime` est une
**constante déclarée** de la recette (peut voyager), l'**identité** est cultivée (doit être
fetchée). Aujourd'hui elles voyagent ensemble, et c'est pour ça que celle qui ne doit pas voyager
voyage.

## ★ Trois sources pour une identité d'image (et non deux)

minter · **le cellier** (conçu, non câblé depuis le grow hôte) · **l'alias `node-base` du démon**
(câblé : `IncusImportLookup.nodeBaseFingerprint`, ajouté le 09-28 ; `imageExists` se réexprime
par-dessus). ⚠️ Le lookup reste **par alias** : une requête par empreinte SIGSEGV le
terraform-provider-incus et empoisonne tous les RPC suivants.

Erreur seulement si les trois se taisent. Deux correctifs livrés le 2026-09-28 :
`6a297176d` (le grow résout au lieu d'inventer ou de sauter) et `99e20f2ad` (la sonde à trois
valeurs — voir [[destructive-gate-needs-three-valued-probe]], qui porte la généralisation « sous
`prune: true`, ne rien émettre EST un acte »).

⚠️ **Piège d'outillage** : `Pulumi.yaml` pointe `exec/seed-master/target~nxmatic/…jar`, la voie du
profil Maven `-Pnxmatic` (l'utilisateur). Le profil jumeau `claude` écrit dans `target~claude` pour
éviter la collision (`build-parent/pom.xml:1077`). Donc **un build d'agent ne met PAS à jour le jar
que la preview exécute** — c'est à l'utilisateur de refaire sa voie, avec
`-Dmaven.build.cache.skipCache=true` (le cache rejoue des variantes périmées entre voies).

## Suite gravée, non commencée

Générations d'images + campagnes d'upgrade : alias dérivé du contenu (`<base>-<checksum8>`,
idempotent sous rebuild ; DNS-1123 car il nomme l'objet), alias **flottant** pour les humains mais
**jamais** en entrée du provisioning, cellier = l'ordinal, branche = la cible, rollout in-cluster =
l'exécution. Deux obstacles mesurés dans [[cluster-owned-decision-cannot-live-where-grow-reasserts]],
plus le `prune: true` du rendu qui impose une **fenêtre de deux générations**.

See [[cluster-owned-decision-cannot-live-where-grow-reasserts]]
[[cilium-needs-nft-compat-on-nftables-only-kernel]] [[cold-start-2026-09-27-nikopol-mgmt]]
