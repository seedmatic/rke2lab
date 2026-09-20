---
name: unmanaged-mac-ssh-material-chain
description: "Le Mac corp (laptop nikopol) porte un sshd_config FOSSILE d'avril qui référence 4 fichiers dont 3 manquaient ; la chaîne réelle est sops-nix → ssh-keys-enrichment → openssh, et comme ce Mac est destinataire sops + a un profil déclaré, le chemin le moins coûteux est de le rendre MANAGÉ, pas d'écrire un enrôleur"
metadata:
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T18:23:50.081Z
---

**Le symptôme** (2026-09-20) : `baremetal-link-deploy.service` sur `nikopol-nixos` échouait en
`Too many authentication failures` en joignant le Mac corp. Cause : le Mac porte
`/etc/ssh/sshd_config.d/999-ndh.conf` — **fichier ordinaire, `root:wheel`, daté du 8 avril 2026, pas
un symlink vers le store**, donc aucune génération ne le gouverne. Un fossile d'avant la
réorganisation seedmatic (mise en garde de l'utilisateur : « vzhost a une histoire qu'on ne maîtrise
pas »).

Il déclare cinq références, dont **quatre étaient mortes ou fausses** :
`TrustedUserCAKeys /etc/ssh/keys.d/trusted-user-ca.pub` (absent) ·
`AuthorizedPrincipalsCommand /etc/ssh/ssh-authorized-principals-command` (**toujours absent**) ·
`AuthorizedKeysCommand /etc/ssh/ssh-group-authorized-keys-command` (absent) ·
`HostCertificate /Users/nxmatic/.local/var/…` (**compte inexistant sur cette machine**). Seuls
`AuthorizedKeysFile %h/.ssh/authorized_keys` et l'`authorized_keys` (qui contient bien `rdp-host`,
`SHA256:l8ny66…`) fonctionnaient. Débloqué en posant à la main la CA
(`mammoth-skate-ca.pub` = `SHA256:xrPxeK/…`, la CA **utilisateur**, à ne pas confondre avec
`host-mammoth-skate-ca.pub` déjà présente) + `~/.ssh/authorized_principals` contenant `rdp-host`
⇒ unité passée `status=0/SUCCESS`.

⚠️ **Le chemin posé est un chemin FOSSILE.** Dans `develop`, `sshPaths.systemKeysDir` vaut
**`/var/lib/ndh/ssh-keys`** (`modules/.common.d/ssh-paths.nix:74`), pas `/etc/ssh/keys.d`. La pose
manuelle cimente donc une seconde ancre de confiance que personne ne fait tourner — à retirer quand
la machine sera alignée.

⚠️ L'anomalie `MaxAuthTries` (défaut 6, deux identités offertes) n'a **jamais été expliquée**, juste
rendue sans objet. Trois commandes/fichiers introuvables par connexion est l'hypothèse la plus
plausible, non démontrée. Si ce chemin recasse, ne pas le croire compris.

## La chaîne réelle, sur `develop`

1. **sops-nix** pose UN secret déchiffré, `ssh-keys.yaml` (cf. `sops.secrets."ssh-keys.yaml"`,
   `modules/nixos/bringup-minimal-system.nix:147`) — précision de l'utilisateur, ma lecture
   initiale s'arrêtait à l'étape 2 et laissait l'origine des `*-ca.pub` sans réponse.
2. **`ssh-keys-enrichment`** le découpe en clés/certs/`*-ca.pub` sous `systemKeysDir`, puis
   **agrège** `trusted-user-ca.pub` en concaténant toutes les `*-ca.pub` du répertoire
   (`modules/{darwin,nixos/systemd}/ssh-keys-enrichment.nix`, ~l.236-245 côté darwin), et génère
   `authorized-principals-command.yaml` par une expression `yq` sur le keys.yaml.
3. **le module openssh** y pointe sshd : drop-ins par `environment.etc` (darwin) et scripts de
   commande installés dans `/etc/ssh` par un **script d'activation** darwin
   (`modules/darwin/openssh.d/openssh-activation.sh:8-10`).

Donc **le propriétaire est l'activation de l'hôte**. Il n'existe aucun producteur pour une machine
non managée.

## Pourquoi l'enrôleur a été ABANDONNÉ (décision de conception, pas d'abandon par fatigue)

Tous les obstacles avancés contre « rendre ce Mac managé » se sont dissous à mesure des faits :

| obstacle supposé | fait mesuré |
|---|---|
| pas d'accès pour piloter | **c'est le laptop de l'utilisateur** — `darwin-rebuild` en local, zéro ssh |
| pas de secrets | sa clé age publique est `age10ey0lcup4zpjqcknpxw7enpsagn674nm634f2u75trfr5t62uq5qdjuxzv`, **premier destinataire du `.sops.yaml` de ndh** |
| pas de politique déclarée | `darwinConfigurations.nikopol` **existe** |
| amorçage circulaire (l'ancre ne peut pas voyager par le canal qu'elle autorise) | vrai **seulement** si on pilote à distance |

Un enrôleur devrait réimplémenter les **trois** étapes ci-dessus, déchiffrement sops inclus — émuler
nix-darwin sur une machine qui a déjà tout pour le faire tourner.

**Le seul blocage restant : l'identité.** `darwinConfigurations.nikopol.config.profile.user` vaut
`name=nxmatic home=/Volumes/user-home` (vérifié par éval), or le compte est `stephane.lacoin` /
`/Users/stephane.lacoin`. Ça vient du `hostProfile` de `hosts/nikopol/default.nix` (confirmé par
l'utilisateur : « l'identité provient du profile dans ndh ») ; `hosts/nikopol/profile.nix` porte en
plus deux références en dur — le candidat sops `/Users/nxmatic/.config/sops/age/keys.txt` et
`nix.settings.trusted-users`. C'est la dette de [[materializer-corp-mac-identity-gcroots]], qui
devient **bloquante** dès qu'on veut activer plutôt que tolérer.

⚠️ Risque à nommer avant d'activer : nix-darwin prend la main sur `/etc/ssh`, les shells et d'autres
fichiers système d'un laptop **corp sous MDM**. Acte plus lourd que tout le reste ; la décision
appartient à l'utilisateur. Et le point fragile n'est pas sshd (accès physique) mais
`baremetal-link` + `/etc/resolver`, qui **portent la session screen-sharing par laquelle
l'utilisateur atteint bioskop**.

**Si un jour il faut vraiment un enrôleur** (machine sans clé age ni profil), le dessin retenu était :
dérivation **pure** + résolution de l'identité **à l'exécution** (`SUDO_USER` puis `id -un` ; home par
`dscl . -read /Users/<u> NFSHomeDirectory`, JAMAIS `$HOME` qui bascule sous sudo), gabarit rendu
depuis la politique réelle, validation `sshd -t` **avant** bascule, et cleanup par **manifeste
possédé** réconcilié — jamais une liste de chemins en dur, et un fichier hors manifeste est
*rapporté*, pas supprimé (sonde à trois valeurs appliquée au nettoyage ;
[[destructive-gate-needs-three-valued-probe]]). Ne PAS toucher : `keys.d/host-mammoth-skate-ca.pub`,
`keys.d/linux-builder-mammoth-skate-ca.pub`, les `github-signing*.pub`, `100-macos.conf`,
`/etc/ssh/nix_authorized_keys.d/`.

**Accès (corrige la mémoire antérieure)** : l'hôte est `vzhost.nikopol` **ou**
`nikopol-vzhost.lan` (utilisateur `stephane.lacoin`) — `nikopol-vzhost` **seul** ne matche aucun bloc.
Le guest est `root@nikopol-nixos.local` avec `-i ~/.local/share/ndh/ssh-keys/rdp-host` (le bloc
`nixos.nikopol` impose `nxmatic`, et le nom nu passe par le LAN qui peut expirer) — il répond via le
tailnet. `known_hosts` est géré par la **CA** (`KnownHostsCommand`) : rien à purger après un factory
reset, SAUF les entrées par IP qu'on y ajoute soi-même (`ssh-keygen -R 192.168.1.34`).

See [[materializer-corp-mac-identity-gcroots]] [[nerd-nixos-tart-vm-renew-procedure]]
[[common-d-is-a-directory-wide-nix-input]].
