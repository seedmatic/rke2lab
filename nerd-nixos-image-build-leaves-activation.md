---
name: nerd-nixos-image-build-leaves-activation
description: "DÉCIDÉ 2026-10-03, PAS COMMENCÉ : sortir la construction de l'image Tart nerd-nixos de l'activation des hôtes darwin (~31 GiB par switch) ; et le couplage n'est PAS un `nix build` dans le script d'activation."
metadata:
  node_type: memory
  type: project
  originSessionId: eef830de-9690-4fc8-ac3d-71fec81b1988
  modified: 2026-10-03T07:13:42.619Z
---

**Décision de l'utilisateur, 2026-10-03** : il a lui-même câblé le provisioning de
l'image Tart `nerd-nixos` de l'hôte **dans l'activation des hôtes darwin**, et veut
**revenir en arrière** — « le build de l'image est trop lourd ». Incrément à part
(son avis, confirmé par la session d'intégration). Brief d'intégration :
`.claude/nerd-nixos-image-activation-handoff.md`.

## ★★★ Le mécanisme n'est pas celui qu'on suppose

Ce n'est **pas** un appel de build dans le script d'activation :
`modules/darwin/tart-config.d/activation.sh` fait 1370 lignes et un grep
`nix build|nix run|nix-build|nixos-generate` y renvoie **vide**.

C'est une **interpolation de dérivation** dans la config darwin — `flake.nix:1819-1822`,
sous `lib.optionalAttrs withBringupImages` — qui met `"${nixosDiskImageBringupSystemdZfs}/boot.img"`
et `nixosOutputs.runtimeSystem` **dans la closure du système**. `darwin-rebuild switch`
construit donc l'image parce qu'il **ne peut pas réaliser le toplevel sans elle**.

⚠️ **Plus lourd ET plus invisible qu'un appel explicite : un audit textuel l'aurait
déclaré innocent.** Et `runtimeSystem` étant `system.build.toplevel`, l'activation traîne
**un système NixOS entier**, pas seulement l'image.

## Le coût est chiffré par le dépôt lui-même

`modules/darwin/tart-config.nix:112-135` : `includeRuntimeClosure=false` économise
« ~16 GiB and ~1800 store paths » ; `includeBringupSymlink` fait « ~15 GiB of raw bytes
scanned as store refs ». Et `tartActivationBundle` — celui que le hook de postActivation
invoque — met **les deux à true** (`:179-180`). Soit **~31 GiB par activation** sur un
hôte de build. Rien à estimer.

## ✅ La couture a déjà un nom dans le code — c'est un DÉPLACEMENT, pas une invention

`tartActivationBundle` (« build host ») contre `tartDeployBundle` (« vz hosts that only
consume artifacts », ~50-100 MiB). L'interrupteur existe aussi, mais **inversé** :
`withBringupImages ? true` (`flake.nix:1715`), donc **ON par défaut pour tout hôte
darwin**, avec `hosts/bioskop/default.nix:37` comme seul poseur explicite… qui le met à
`true`, donc redondant.

⚠️ **Premier maillon non mesuré** : `modules/home-manager/default.nix:309` consomme
`materializer_binary="nerd-tart-vm-materialize"` alors que la branche OFF met
`vmConfigMaterializerPackage = null`.

## Ce que cet incrément doit aussi réécrire

La section de flux étagé de `README-bootstrap.md` (`run.sh vm:reset`,
`phase:bootstrap:all`, `vm:nixos:boot:*`) a été **supprimée** le 2026-10-03 parce
qu'aucun script ne l'implémentait — voir [[ndh-normalized-and-flox-envs-vendored]]. Elle
n'a **pas** été remplacée exprès : ce qui remplace `vm:reset` **est** la frontière
image-vs-activation que cet incrément redessine. La réécrire avant aurait été inventer
une procédure opérateur.

⚠️ **Trois incréments briefés sur UN seul worktree ndh** (celui-ci, `one-true-path` sur
`hosts/*/nixos.nix`, et celui qui a atterri) — l'intégration les séquence.
