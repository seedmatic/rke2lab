---
name: nix-store-and-etc-can-both-lie
description: "★★★ Quand `nix eval` et `cat` se contredisent, soupçonner le DISQUE : un `tee` à travers une chaîne de symlinks écrit DANS le store, nix ne rebâtit jamais un chemin existant, et `rm` sur un chemin désynchronise sa base. Réflexe : `nix-store --verify-path`. Vécu le 2026-09-27, ~4 h perdues."
metadata:
  node_type: memory
  type: project
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-27T12:57:27.979Z
---

Journée du **2026-09-27**, en réparant le câblage du builder aarch64-linux de bioskop.

## ★★★ Le réflexe à acquérir

**Quand une évaluation nix et le contenu d'un fichier se contredisent, le disque est suspect avant le
code.** `nix-store --verify-path <chemin>` tranche en dix secondes. J'ai passé des heures à chercher un
conflit de définitions de modules qui **n'existait pas** : `config.nix.buildMachines` rendait
`[bioskop-nixos]`, `environment.etc."nix/machines".text` rendait la bonne ligne, `nix.buildMachines`
n'avait **qu'un** définisseur (`options…definitionsWithLocations` le prouve) — et le fichier servi
disait autre chose.

```text
nix-store --verify-path /nix/store/xfvak436…-etc-machines
→ was modified! expected sha256:0v0a9hvj… got sha256:03670l2b…
```

## La cause : un `tee` qui traverse une chaîne de liens

`/etc/nix/machines` → `/etc/static/nix/machines` → `/nix/store/…-etc-machines`. `sudo tee` **suit les
liens**, donc l'écriture atterrit **dans le store**. Le store est ici en APFS **inscriptible** et
**dédupliqué** (`/nix/store/.links`), donc une telle écriture peut atteindre tout chemin partageant le
contenu. Corrigé dans ndh (`9f4c38f9`, `d474ce09`) : `rm -f` avant le `tee`, et le teardown restaure le
**genre** (`symlink <cible>` / `file` / `absent`) et non les octets — remettre un fichier réel là où
nix-darwin gère un lien masque le déclaratif pour tous les switches suivants.

## ★★ Quatre comportements de nix qui rendent ça difficile à réparer

1. **Nix ne rebâtit JAMAIS un chemin existant.** Un contenu falsifié est donc servi indéfiniment ;
   aucun `darwin-rebuild switch` n'y change rien.
2. **`nix-store --realise` se court-circuite** si la base dit le chemin valide — il imprime le chemin
   et ne fait rien. Or `rm` sur un chemin du store **désynchronise la base** : `nix path-info` réussit
   alors que le fichier n'existe pas.
3. **`--repair-path` ne fait que SUBSTITUER**, il ne rebâtit pas. Sur la sortie d'un `writeText` local
   (jamais poussée dans un cache) il vérifie la clôture du constructeur, ne trouve rien à télécharger,
   et s'arrête **sans message**. C'est pourquoi il a semblé inopérant deux fois.
4. ★ **La réparation fidèle** : reconstituer les octets depuis l'évaluation, vérifier leur hash NAR
   (`nix hash path --type sha256 --base32`) contre celui qu'annonce l'erreur, puis les remettre en
   place. Ce n'est pas un contournement — le hash correspondait exactement (le détail que la main
   aurait raté : la ligne finit par `- - \n`, espace compris).

Le balayage `nix-store --verify --check-contents` (lecture seule, sans `--repair`) a ensuite montré que
la corruption **ne s'était pas propagée** : une seule autre divergence dans tout le store,
`vscode-insiders`, qui **se met à jour tout seul** dans son propre chemin — motif classique et sans
rapport.

## ★★ Une propriété de nix-darwin qui n'était PAS la cause, mais qui trompe

`/etc/nix-darwin/flake.nix` est un wrapper **généré** (`modules/darwin/core.nix`) qui épingle
`self.outPath`, une **copie figée dans le store**. Donc un `darwin-rebuild switch` **sans `--flake`**
reconstruit cette copie et est un **no-op vis-à-vis de l'arbre de travail** — l'activation annonce un
succès et la génération avance alors que la configuration est identique. Il faut nommer l'arbre
(`darwin-rebuild --flake /chemin#<hôte> switch`), ce qui re-épingle le wrapper.
⚠️ J'ai d'abord imputé l'incident à ça, à tort. La propriété est réelle et documentée (ndh `70504a02`,
corrigée par `d474ce09`), mais **`/etc/static` retardait aussi d'une génération** juste après le switch,
ce qui a brouillé mes lectures. Deux leurres, une seule cause.

⚠️ Autre leurre rencontré : **`.source` et `.text` d'une même entrée `environment.etc` peuvent rendre
des contenus différents** (quand un module pose l'un, l'autre est vide ou par défaut), et le **cache
d'évaluation** peut servir l'un de travers — j'ai eu un `eval-cache … is busy` en pleine séquence.
Mesurer avec `--no-eval-cache`, et lire le fichier **par le chemin du toplevel** plutôt que par `/etc`.

## Contexte : pourquoi ce fichier était en jeu

`ndh.hostBuilder` vaut `steady` sur les deux Macs depuis le 2026-09-23, ce qui déporte les builds
aarch64-linux sur `<host>-nixos` par ssh root. L'app `bootstrap-linux-builder` est l'échappatoire de
démarrage à froid (une VM vz temporaire) ; c'est son câblage impératif qui a corrompu le chemin.
★ Et `sudo darwin-rebuild` échoue par ailleurs sur `claude-hub` (dépôt **privé**, 404 = absence
d'identifiant) parce que `$HOME` bascule sur `/var/root` : root ne voit pas l'`access-tokens` de
l'opérateur. ndh a déjà le motif `NIX_CONFIG="!include <fichier root-only>"` pour le nœud ; il reste à
l'appliquer à l'opérateur. Contournement immédiat mesuré : `--builders '…'` en ligne de commande, qui
ignore `/etc/nix/machines` entièrement.

See [[tailnet-routing-owned-by-hosts-not-pods]] [[rke2-rejects-a-loopback-resolv-conf]]
