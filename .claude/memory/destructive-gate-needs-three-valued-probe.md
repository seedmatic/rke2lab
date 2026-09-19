---
name: destructive-gate-needs-three-valued-probe
description: "Règle — une sonde qui autorise un acte destructeur doit distinguer « absent » de « pas pu regarder » ; sinon toute panne d'introspection devient un ordre de suppression (cas réel : tart/diskutil, 2026-09-19)"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-19T13:39:20.927Z
---

Toute sonde dont la réponse **négative** déclenche une destruction doit renvoyer
**trois** valeurs : présent · absent · **non introspectable**. Sinon un échec d'outil,
un fichier verrouillé ou une expression fautive se présentent comme « il n'y a rien
là » — c'est-à-dire, pour l'appelant, comme un ordre de suppression.

**Why :** cas réel dans `ndh` (`modules/darwin/tart-config.d/activation.sh`, corrigé par
`47943f0b`). `tart:root-disk:zfs:contains` décidait si un disque ASIF portait un pool ZFS
vivant ; réponse négative ⇒ `rm -f` puis re-matérialisation, donc **pools perdus**. Elle
lisait le plist de `diskutil image info` en XML, ce qui force à aligner par indice des
`<key>` et des valeurs entrelacés. Avec **une seule** partition, `yq` rend une map au lieu
d'une séquence, l'expression part en erreur, l'erreur est avalée par `2>/dev/null`, le
résultat est vide — et vide se lisait « pas de ZFS ». Le garde-fou anti-perte-de-pools
était à une partition près de causer la perte.

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

See [[erofs-store-layer-stack-vision]] [[nerd-nixos-tart-vm-renew-procedure]]
[[materializer-corp-mac-identity-gcroots]].
