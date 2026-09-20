---
name: tailnet-node-identity-ephemeral-ghosts
description: "Cause racine MESURÉE (2026-09-20) — les auth-keys ndh sont ephemeral=false, donc chaque factory reset laisse un fantôme qui RETIENT le nom tailnet ; d'où les suffixes nerd-nixos-1..7 et des nœuds vivants mal nommés. Purge = manage-tailnet --prune-stale-devices"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T18:23:04.978Z
---

**Une seule cause, un seul levier.** `modules/.common.d/manage-tailnet.d/default.nix:62` construit le
corps de la requête `POST /keys` avec **`ephemeral = false`** (et `reusable = true`,
`preauthorized = true`). Un nœud non-éphémère n'est jamais retiré quand il disparaît ⇒ chaque
factory reset laisse un fantôme ⇒ le fantôme **retient le nom** ⇒ l'enregistrement suivant est
suffixé.

**Constaté sur la flotte le 2026-09-20** : 16 devices dont 13 fantômes, familles `nerd-nixos-1..7`
(nom du **bringup**, où `networking.hostName` vaut le littéral `nerd-nixos`),
`nikopol-nixos-1,2`, `bioskop-nixos-1,2`. Le nœud vivant `nikopol-nixos` s'affichait comme
**`nerd-nixos-7`**, et `bioskop-nixos` comme **`nerd-nixos-4`** — donc *toute* la flotte portait un
nom de bringup suffixé, pas seulement un nœud.

**Ce n'était PAS un second défaut.** J'avais diagnostiqué « la génération runtime ne réclame jamais
son nom » comme un défaut distinct. Faux : elle advertise bien le bon nom
(`--hostname=${cfg.hostname}` avec `defaultHostname = config.networking.hostName`,
`modules/nixos/headscale.nix:14,159`) ; elle ne pouvait pas l'**obtenir** tant qu'un fantôme le
détenait. Preuve : après la purge **+ un reboot**, le nœud s'est renommé tout seul (l'utilisateur a
précisé que le reboot était nécessaire — le renommage prend effet au ré-enregistrement, pas à chaud).

**Le bringup rejoint le tailnet DÉLIBÉRÉMENT** — `modules/nixos/bringup-minimal-system.nix:85-89` le
documente (accès opérateur pendant l'installation) et note que l'état vit dans `/var/lib/tailscale`
sur la racine ZFS persistante, donc l'enregistrement **survit** à la bascule. Ce n'est pas un bug
d'ordonnancement à corriger, c'est une fonctionnalité dont le nommage est l'effet de bord.

**La purge existe déjà** (découverte par l'utilisateur, pas par moi) :
`nix run .#manage-tailnet -- --prune-stale-devices --stale-after 1s --yes`. ⚠️ `1s` a un rayon
d'action large — elle a aussi emporté `tailscale-operator`, `flux-webhook`, `pipelines-webhook` et
`bioskop-mgmt-controlplane`, qui sont des devices créés côté Kubernetes et non des fantômes de
nœuds. Ils se ré-enregistrent, mais choisir `--stale-after` selon ce qu'on veut réellement élaguer.

**Remède automatique, NON appliqué (décision en attente)** : `ephemeral = true` sur la clé des
guests. Compromis à peser — une machine éteinte longtemps perdrait son identité de nœud ; sans
conséquence ici puisque les ACL passent par des **tags** et que
`modules/home-manager/ssh-tailnet-hosts.nix:161` documente déjà tolérer la rotation des clés d'hôte
des guests. Réserve non vérifiée : la fenêtre de grâce avant suppression d'un nœud éphémère (de
l'ordre de la demi-heure ?) — à mesurer, elle décide si un reboot normal provoque du churn.

**Dommage collatéral qui rend ça visible** : `ssh-tailnet-hosts.nix` **génère la config ssh de
l'opérateur depuis les hôtes du tailnet**, donc les fantômes la polluent.

**★ Question de fond ouverte, posée par l'utilisateur** (« ça laisse perplexe quant à la gestion des
identités dans le tailnet, on devrait pouvoir faire mieux ») : une identité de nœud a **trois
sources** — le nom vient du hostname de la *génération qui s'enregistre la première* (donc du
bringup), l'identité de nœud d'un état sur disque, et la *récupérabilité* du nom d'une purge
externe. Trois sources pour une seule identité, d'où l'imprévisibilité. Chantier à ouvrir.

See [[erofs-store-layer-stack-vision]] [[nerd-nixos-tart-vm-renew-procedure]].
