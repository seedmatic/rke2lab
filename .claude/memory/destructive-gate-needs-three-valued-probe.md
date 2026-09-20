---
name: destructive-gate-needs-three-valued-probe
description: "Règle — une sonde qui autorise un acte destructeur doit distinguer « absent » de « pas pu regarder » ; sinon toute panne d'introspection devient un ordre de suppression (cas réel : tart/diskutil, 2026-09-19)"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T13:43:01.816Z
---

Toute sonde dont la réponse **négative** déclenche une destruction doit renvoyer
**trois** valeurs : présent · absent · **non introspectable**. Sinon un échec d'outil,
un fichier verrouillé ou une expression fautive se présentent comme « il n'y a rien
là » — c'est-à-dire, pour l'appelant, comme un ordre de suppression.

**Why :** deux instances le même jour dans le même fichier `ndh`
(`modules/darwin/tart-config.d/activation.sh`), et la seconde **a réellement détruit les
pools de nikopol-nixos**.

*Instance 1 (latente, corrigée par `47943f0b`).* `tart:root-disk:zfs:contains` décidait si
un disque ASIF portait un pool ZFS vivant ; réponse négative ⇒ `rm -f` puis
re-matérialisation. Elle lisait le plist de `diskutil image info` en XML, ce qui force à
aligner par indice des `<key>` et des valeurs entrelacés. Avec **une seule** partition,
`yq` rend une map au lieu d'une séquence, l'expression part en erreur, l'erreur est avalée
par `2>/dev/null`, le résultat est vide — et vide se lisait « pas de ZFS ». À une partition
près de la perte.

*Instance 2 (celle qui a frappé, corrigée par `6213a553`).* `tart:vm:exists` scannait
`tart list` et lisait la **première colonne** comme le nom de la VM — or c'est `Source`,
donc il comparait `local` au nom et ne pouvait jamais répondre oui. Et `tart` **2.36**
échoue le listing *entier* dès qu'une VM tourne (il stat les images de toutes les VMs pour
les tailles ; mesuré **10/10** sur l'hôte où une VM tourne en permanence — 2.30 le tolérait
et rendait 0). Les deux fautes se présentaient pareil, « cette VM n'est pas enregistrée », et
`tart:vm:ensure` y répondait par `tart create` — qui recrée le répertoire de la VM et
emporte les disques de données, puis les trouve absents et les blanchit. Les pools étaient
morts **avant** que la logique de préservation ne soit consultée. C'est aussi pourquoi cette
branche était « jamais exercée » : elle était **inatteignable**, et toute matérialisation
sans `VM_FACTORY_RESET` détruisait les pools sur n'importe quel hôte.

**La bonne primitive, trouvée par l'utilisateur** : `tart get --format=json <vm>` interroge
**une seule** VM (donc une autre VM en marche ne peut pas la casser) et encode la réponse
dans son code de sortie — `0` enregistrée · `2` *the specified VM does not exist* · `1` VM en
marche. Et son observation décisive : ce `1` est une **preuve positive d'existence**, pas un
inconnu — pour atteindre le disque, `tart` a dû résoudre la VM, et il cite le chemin résolu
dans l'erreur. Donc seul un `2` formel autorise la création.

Deux corollaires mesurés le même jour :

- `diskutil image info` **échoue** sur une image tenue par une VM en marche
  (*Resource temporarily unavailable*). C'est donc un cas « pas pu regarder » qui arrive
  en exploitation normale, pas une hypothèse. Rendre ce cas fatal impose d'**attendre** le
  relâchement après `tart stop` (qui rend la main avant que Virtualization.framework ait
  fermé les images) plutôt que de courir contre lui.
- **La taille n'est pas un marqueur.** La même porte préservait le disque root si sa
  taille virtuelle égalait celle de la source — vrai par coïncidence (600 MiB des deux
  côtés). Changer la taille de l'image de boot aurait basculé la porte en
  « re-matérialise » et effacé silencieusement l'ESP et les générations NixOS de chaque
  nœud. Remplacé par un marqueur `.source` (le store path, dont l'identité EST le contenu)
  plus un test **positif** de contenu (une partition `EFI` au plist).

**How to apply :** quand une branche supprime, elle ne s'exécute que sur une réponse
**positive**. Jamais sur une réponse vide, jamais sur une réponse inconclusive. Et pour
sonder un disque à froid sans arrêter sa VM : `cp -c` (clonefile APFS, instantané et sans
occuper d'espace) puis interroger le clone.

Asymétrie à garder en tête pour les images de disque : une image **prebuilt** est en
lecture seule et content-addressed, donc la remplacer est gratuit ; un disque **root** est
mutable et porte l'état du nœud, donc le remplacer reste un acte explicite de l'opérateur
(`VM_FACTORY_RESET`). Deux disciplines différentes pour deux natures différentes.

## 2026-09-20 — corollaire : deux portes justes séparément peuvent être fausses ensemble

L'asymétrie ci-dessus est vraie **et insuffisante**. « Remplacer une image prebuilt est
gratuit » ne vaut que si rien d'autre ne nomme son contenu. Or l'**ESP nomme un toplevel**
que les couches doivent porter. Les deux portes décidaient indépendamment — marqueur +
sonde EFI pour le root, marqueur pour chaque couche — donc une matérialisation pouvait
légitimement **préserver l'ESP et remplacer le contenu d'une couche**, et personne ne
comparait les deux décisions.

Résultat mesuré sur nikopol-nixos : la couche 002 recoupée du runtime de l'hôte vers la
closure générique, ESP et pools préservés, et le nœud est monté en **shell d'urgence
initrd** — `initrd-find-nixos-closure` ne trouvait plus le toplevel de la génération 2.
Chacune des trois décisions était défendable ; **conjointement** elles étaient incohérentes.

**Why :** une porte destructrice ne se valide pas seule. Sa correction dépend de ce que les
*autres* portes de la même exécution ont décidé.

**How to apply :** quand N portes décident du sort de N artefacts **couplés par une
référence** (ici : l'ESP nomme, les couches portent), il faut une vérification jointe, et
elle doit **refuser** plutôt que deviner — d'autant que le materializer ne *peut pas*
réparer cet état : les pools préservés gardent l'ancien profil nix, et seul l'installateur
de la VM imbriquée écrit un profil, dans les pools du bundle et jamais dans les vivants.
Implémenté dans `ndh` `e3451f13` : `tart:vm:store-layers:replacement:guard`, qui distingue
**ajout** (sûr, une génération antérieure ne monte pas la couche neuve et n'en a pas besoin)
de **remplacement** (interdit sous ESP préservé) et nomme `VM_FACTORY_RESET=true` dans
l'erreur.

See [[erofs-store-layer-stack-vision]] [[nerd-nixos-tart-vm-renew-procedure]]
[[materializer-corp-mac-identity-gcroots]].
