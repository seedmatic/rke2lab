---
name: incus-trust-store-is-destroyable-state
description: "Le trust store Incus est de l'état daemon : re-minter les certificats d'un nœud efface capn-provider et CAPN répond `not authorized` en silence pendant des heures — corrigé le 2026-09-21 (rke2lab publie, ndh assère à chaque boot)"
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

## Le correctif (livré)

Le certificat client est du **PEM public** (versionné dans rke2lab depuis `68e9f7413`) ; seule sa
moitié privée est dans le Secret d'identité. Donc il voyage comme un fait ordinaire, sur l'arête
que ndh consomme déjà (`lib.networkBlueprint`, `lib.dataplan`) :

- rke2lab expose `lib.capnProviderCert` (`72290388f`).
- ndh le lit via `catalog.incus.trustedClients` — même nature que `caches` (matériau de confiance
  public, nommé, à l'échelle de la flotte) — et `modules/nixos/incus.d/ensure-incus-trust.sh`
  l'assère à chaque démarrage du daemon (`78249644`).

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
- `incus config trust add-certificate <pem> --name <n> --type client` (`--type` défaut `client`).
- L'unité est `wantedBy` d'`incus.service` **sans** `partOf`/`bindsTo` : un échec doit rester
  visible dans `systemctl --failed` sans coucher le daemon.
- Le script n'utilise **pas** `ndh::logger:command:run` — voir [[ndh-logger-wrapper-neutralises-errexit]].

See [[capn-cert-ownership-incoherence]] (qui *détient* le fichier — autre question, déjà réglée)
[[destructive-gate-needs-three-valued-probe]] [[nerd-nixos-tart-vm-renew-procedure]].
