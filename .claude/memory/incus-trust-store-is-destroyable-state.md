---
name: incus-trust-store-is-destroyable-state
description: "Le trust store Incus est de l'état daemon : re-minter les certificats d'un nœud efface capn-provider et CAPN répond `not authorized` en silence pendant des heures — corrigé le 2026-09-21 par une ressource incus.Certificate déclarée au GROW (1re version via ndh, révoquée)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-21T09:02:30.410Z
---

Le 2026-09-20 au soir, ndh a re-minté les certificats Incus de `bioskop-nixos`. Ça a effacé
l'entrée de confiance **`capn-provider`** du trust store — une entrée qui n'existait que parce
qu'elle avait été tapée **une fois à la main** (`bootstrap-identity-provider.adoc`, « Step 2 »,
qualifiée de « one-time »). Rien, ni dans rke2lab ni dans ndh, ne la réinscrivait.

## Pourquoi ça a coûté sept heures

La cause est à quatre couches sous le symptôme, et chaque couche mentait par omission :

1. Flux : `cluster-api-cluster-api-management` **False**, « timeout waiting for:
   ClusterIntention … status: 'InProgress' ».
2. `PoolAdoption` : `PetsPresent=False — AwaitingInstances: 0/1 present, 1 pending`, alors que le
   `Machine` CAPI était **Provisioned** avec nodeRef et que le `providerID` du `LXCMachine`
   correspondait à celui du nœud.
3. `LXCMachine` : `InstanceProvisioned=False`, motif `WaitingForClusterInfrastructureReady`, posé
   **3 secondes après la création** et jamais retouché en 7h14 — alors que
   `Cluster.status.initialization.infrastructureProvisioned` était `true` et le `LXCCluster`
   `Ready=True`. J'ai d'abord cru à une condition périmée : **faux**, CAPN réconciliait sans
   arrêt (294 lignes de log). La condition ne bougeait pas parce que la réconciliation **échouait
   avant** de la mettre à jour.
4. Logs CAPN : `"Failed to check instance state" err="not authorized"` — l'erreur réelle, visible
   nulle part sur les CRs.

**Leçon de diagnostic** : une condition figée sur sa valeur de naissance ne signifie pas « pas de
watch » ; ça peut être « la réconciliation meurt avant l'écriture ». Le discriminant est le
**compte de logs**, pas l'horodatage de la condition.

`lxcMachinePresence` (seed-incluster, `pooladoption_controller.go`) ne tranche que sur
`InstanceProvisioned=True` (présent) ou `False`+`InstanceDeleted` (absent) ; tout autre motif est
indécis → `pending`. **C'est correct** : il attend un verdict que CAPN ne peut pas rendre. Ne pas
« corriger » seed-incluster face à ce symptôme.

## Le correctif — RÉVISÉ le 2026-09-21 (la 1re version est révoquée)

Le certificat client est du **PEM public** (versionné dans rke2lab depuis `68e9f7413`) ; seule sa
moitié privée est dans le Secret d'identité.

**Première tentative, RÉVOQUÉE** : rke2lab exposait `lib.capnProviderCert` (`72290388f`) et ndh le
lisait via `catalog.incus.trustedClients` pour l'asserter à chaque démarrage du daemon (`78249644`).
Reverté des deux côtés (rke2lab `5d113fc4e`, ndh `45663850`). Motif : j'avais conclu qu'il n'existait
**aucune ressource de certificat** dans le provider Incus — conclusion tirée d'un `find sdks/incus`
sur **un chemin qui n'existe pas** (le SDK vendoré est sous
`host/pulumi/pulumi-generated-sdks/incus`). Le find rendait vide, j'ai lu une absence. ⚠️ **Leçon :
un `find`/`grep` vide sur un chemin non vérifié n'est pas une absence.**

**Correctif retenu** : le GROW déclare une ressource `incus.Certificate` (`name capn-provider`,
`type client`), adoptée par `importId` quand l'entrée est déjà là — clavée sur le **fingerprint**, la
vraie clé du trust store, donc aucune convention de nom à faire concorder. rke2lab `8f3236e97`.

Ce qui a exigé le **bump du provider à 1.2.0** (rke2lab `0085a4240`) : `getCertificate` y est
**nouveau** (absent en 1.1.1), et sans lui une entrée existante ne peut qu'être percutée, pas adoptée.
Le bump s'est révélé bon marché — **41 fichiers** touchés dans le SDK régénéré, pas les 179 que la
mémoire annonçait ; méthode : tag `recovery/incus-sdk-1.1.1-before-bump`, suppression du dossier,
régénération, récupération de `pom.xml`/`README.md`/`.gitattributes` depuis le tag.

Plomberie : l'hôte détient le certificat, donc seed-master lit **sa propre** ressource classpath et la
passe sur `IngressConfig` (un 6e composant). Le lecteur qu'il avait déjà pour les credentials CAPN est
hissé hors du stage `Given` plutôt que dupliqué.

**Ce qu'on a perdu, et qu'il faut savoir** : le script ndh se réassurait à *chaque démarrage du
daemon*, donc l'entrée se réparait sans `pulumi up`. La ressource Pulumi ne réconcilie que quand la
stack tourne. En pratique une re-matérialisation est toujours suivie d'un grow, donc l'écart est
étroit — mais réel, pas une équivalence.

## Pourquoi PAS le preseed, malgré `certificates:`

`virtualisation.incus.preseed` accepte bien `certificates:` (schéma vérifié en **v7.3.0 et
v7.4.0**), et ndh utilise déjà le preseed. Mais dans `ApplyServerPreseed`
(`client/incus_server.go`), cette section appelle `CreateCertificate` **en aveugle**, là où les
sections storage-pool / network / project font toutes get-puis-crée-ou-met-à-jour. Comme
`incus-preseed.service` est `bindsTo`/`partOf` d'`incus.service`, elle rejoue à chaque démarrage :
un second POST de la même empreinte ferait échouer l'unité à tous les boots sauf le premier. La
description de l'option nixpkgs promet pourtant « overwrite existing entities or create missing
ones » — promesse que cette section ne tient pas.

**Contribution amont évidente et courte** : ajouter la même garde get-then-create à cette section
retirerait le besoin du script. Non faite.

## Détails qui resservent

- `incus config trust list --format csv` : champ **4** = empreinte **tronquée à 12 caractères**.
- `incus config trust add-certificate <pem> --name <n> --type client` (`--type` défaut `client`) —
  la commande de dépannage manuel ; le chemin nominal est la ressource Pulumi.
- L'empreinte du trust store = SHA-256 sur le **DER**, hex minuscule (`certificateFingerprint()`
  dans `InstanceGrow`). C'est la clé de `getCertificate`, donc de l'adoption par `importId`.
- `IncusImportLookup.existingCertificateId(fingerprint)` suit le patron de `existingProfileId` :
  `existingXId(...).ifPresent(options::importId)`, celui qu'emploie déjà la ressource projet.

See [[capn-cert-ownership-incoherence]] (qui *détient* le fichier — autre question, déjà réglée)
[[destructive-gate-needs-three-valued-probe]] [[nerd-nixos-tart-vm-renew-procedure]].
