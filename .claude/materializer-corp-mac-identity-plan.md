# Chantier — materializer identity + gcroots sur Mac corp (nikopol-vzhost)

**Statut** : DIAGNOSTIQUÉ + workaround live appliqué (2026-09-17). Code PAS corrigé.
**Repo concerné** : `ndh` (branche `develop`) — `modules/darwin/tart-config*`.

## Symptôme

Lancer le materializer nerd-nixos directement sur le Mac corp qui héberge la VM
nikopol (`nikopol-vzhost.lan`, hostname `APL-g4xfl7qv06`, compte `stephane.lacoin`) :

```
/nix/store/…-nerd-tart-vm-materialize/bin/nerd-tart-vm-materialize
```

La VM se matérialise, MAIS **aucun gcroot n'est créé** → le run wrapper
`~/.tart/vms/nerd-nixos/nerd-nixos.sh -> …-tart-nerd-tart-run.sh` pointe un store
path que `nix-collect-garbage` supprime (perte de la cible = symptôme récurrent
« la dernière fois j'ai perdu la cible »).

## Racine

L'image/materialize est figée sur une **identité qui n'existe pas sur ce Mac corp** :

| champ (manifeste nikopol)   | valeur gravée        | réalité Mac corp                 |
|-----------------------------|----------------------|----------------------------------|
| `profile_user_default`      | `nxmatic`            | `stephane.lacoin` (uid 502, admin) — **pas de compte nxmatic** |
| `profile_home_default`      | `/Volumes/user-home` | **inexistant** ; HOME=`/Users/stephane.lacoin` |

Ces valeurs viennent du `hostProfile` nikopol dans ndh (`profile.user.name` /
`profile.user.home`).

La création du gcroot est **gatée sur root** puis **re-exec `sudo -u <profile_user>`**
(`ndh/modules/darwin/tart-config.d/activation.sh:1044-1050`) :
- lancé en `stephane.lacoin` (pas root) → branche root sautée → `tart:raw-images:gcroot:materialize`
  (`activation.sh:532-547`) tente d'écrire sous `/nix/var/nix/gcroots/per-user/nxmatic`
  (root-owned, nxmatic inexistant) → échoue silencieusement → pas de gcroot.
- lancé en `sudo` nu → `exec sudo -u nxmatic …` → **échoue** (compte inexistant).

Aggravant (bootstrap sur Mac NON-managé) : APL-g4xfl7qv06 ne tourne **aucune config
nix-darwin** → l'auto-install du profil bringup-runtime (`autoInstallOnActivation`)
n'a jamais lieu, et le hint tape `APL-g4xfl7qv06-bringup-install` (hostname), attribut
de flake inexistant. De plus la closure copiée portait l'installer **aarch64-linux**
(bash ELF Linux → « Exec format error » sur Darwin) — il faut l'installer **darwin**.

## Modèle gcroot (rappel)

`tart:raw-images:gcroot:materialize` pose **UN** symlink
`raw_image_target_path` (= `…/gcroots/per-user/<user>/tart-nerd-nixos-materialize`)
→ le **bundle d'activation** (`…-io.seedmatic.ndh-tart-nerd-nixos-materialize`).
Ce bundle garde toute la closure vivante (activate.sh → run.sh, `bringup-manifest`
→ images, `runtime-system` → nikopol-nixos). Pas de `nix-store --add-root` : simple
symlink dans l'arbre gcroots (suivi par le GC). Les per-user gcroots sous `<user>/`
sont écrivables par `<user>` (pas besoin de sudo pour poser à la main).

## Workaround appliqué le 2026-09-17 (live, non codé)

1. Profil bringup-runtime **darwin** installé à la main (l'installer/holder darwin
   `gzx2n2xx…` / `pmw9w36l…` poussés depuis bioskop via `nix copy --substitute-on-destination`,
   puis `sudo …/nerd-bringup-install` → profil `io-seedmatic-ndh-bringup-runtime`
   à `/nix/var/nix/profiles/per-user/root/`, bash Mach-O ✓).
2. gcroot posé à la main sous per-user/stephane.lacoin :
   `ln -sfn <bundle 1nd23y4…> /nix/var/nix/gcroots/per-user/stephane.lacoin/tart-nerd-nixos-materialize`
   → `nix-store --query --roots <run.sh>` renvoie enfin une racine.
3. gcroots morts purgés (lima ×2, ancien per-image `tart-nerd-nixos.raw.img`, profils
   orphelins io-nxmatic + `nerd-nixos-bringup-runtime`) + `sudo nix store gc` → 14,8 GiB.

⚠️ Le gcroot manuel épingle **ce** hash de bundle ; un re-materialize changeant le hash
laissera pendre l'ancien → re-pointer (ou fix code ci-dessous).

## Options de fix durable (option 1 tranchée pour débloquer)

1. **[CHOISIE, runtime]** Lancer toujours avec override :
   `sudo env PROFILE_USER=stephane.lacoin PROFILE_HOME=/Users/stephane.lacoin \
     /nix/store/…-nerd-tart-vm-materialize/bin/nerd-tart-vm-materialize`
   (env vars lues en priorité, `activation.sh:974-975`). Le script re-exec en
   stephane.lacoin, gcroots sous per-user/stephane.lacoin, VM sous `~/.tart`. Rien à rebuild.
2. **[durable]** Corriger le `hostProfile` nikopol dans ndh (`profile.user` →
   stephane.lacoin / /Users/stephane.lacoin), rebuild le materialize, re-copier.
3. Provisionner `nxmatic` + monter `/Volumes/user-home` sur le Mac corp (si nxmatic/@user-home
   est bien le modèle infra voulu).

## Question ouverte à trancher avant de coder

Pourquoi le hostProfile nikopol grave `nxmatic` + `/Volumes/user-home` ? Est-ce un
montage corp planifié (home réseau de l'opérateur) ou une hypothèse périmée ? La réponse
départage option 2 (le Mac corp = compte local stephane.lacoin) vs option 3 (identité
opérateur nxmatic montée). Le Mac corp EST géré par le compte corp `stephane.lacoin`
(cf. `ndh/modules/home-manager/ssh-tailnet-hosts.nix` : « User stephane.lacoin is the
corp account on the bare metal »), ce qui penche pour **option 2** (ou rendre l'override
option-1 permanent via le deploy helper).

## Piste code (option 2 / rendre l'override 1 permanent)

- `nerd-tart-nikopol-deploy` (le helper prévu pour ce cas, `nix run .#nerd-tart-nikopol-deploy -- nikopol-vzhost.lan`)
  devrait injecter PROFILE_USER/PROFILE_HOME du compte corp, ET installer le profil
  bringup-runtime **darwin** en amont (le Mac cible n'étant pas managé). Vérifier ce que
  fait `mkDeployHelper` (`ndh/modules/darwin/tart-config.nix` / flake.nix ~1083) vs
  l'invocation manuelle — c'est probablement là qu'il manque le bootstrap profil + l'identité.
- Sinon dériver le profil nikopol pour matérialiser sur le compte corp.

## Vérification post-fix

```bash
ssh nikopol-vzhost.lan '
  file /nix/var/nix/profiles/per-user/root/io-seedmatic-ndh-bringup-runtime/bin/bash   # Mach-O arm64
  nix-store --query --roots /nix/store/…-io.seedmatic.ndh-tart-nerd-tart-run.sh        # doit renvoyer une racine
  ls -la /nix/var/nix/gcroots/per-user/stephane.lacoin/tart-nerd-nixos-materialize
'
```
