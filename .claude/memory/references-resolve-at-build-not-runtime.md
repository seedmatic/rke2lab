---
name: references-resolve-at-build-not-runtime
description: "Décision de l'utilisateur (2026-09-23) : les références se résolvent au BUILD, jamais au runtime — le lock doit être dans git ; exception admise seulement via un pipeline Tekton explicite"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T13:48:00.929Z
---

> « c'est au build que doivent être résolues les références pas au runtime, sauf exception si
> besoin est via un pipeline tekton. »

**Why:** un lock est une **décision**, et une décision se prend une fois, se commite, et se
relit. Aujourd'hui le flox-controller **re-dérive** in-cluster un lock déjà décidé dans git : la
`FloxEnv` ne porte pas le lock mais une annotation `flox.seedmatic.io/relock:
flox-catalogue@sha1:<rev>`, et le contrôleur ré-évalue le flake du catalogue (mesuré : son
`status.lock` porte `locked-url = tarball+http://<source-controller>/…/flox-catalogue/<rev>.tar.gz`).

Trois conséquences mesurées de cette résolution au runtime :

1. **La course d'ordre.** Le rendu, déclenché par le push de `feature/nixos-node-substrate`
   (`.tekton/render.yaml` : `on-target-branch`), émet la `FloxEnv` épinglée sur la révision de
   catalogue *précédente* — donc l'ancien binaire tourne quelques minutes, pile quand il crée
   le CR-set create-only. Voir [[seed-incluster-crd-and-binary-travel-by-two-pins]].
2. **Le nœud va chercher github** à chaque relock, pour recalculer une réponse déjà connue.
3. **Un input local est impossible** : une référence `path:` / `git+file:` ne résout pas sur le
   nœud, alors que tous les worktrees partagent le même dépôt
   (`--git-common-dir = rke2lab.d/main/.git`) et qu'une référence locale serait la façon
   naturelle de découpler le bump du push.

**How to apply:** faire voyager le lock **committé** avec le rendu. ⚠️ **L'annotation
`relock` se GARDE** — précision de l'utilisateur, je l'avais d'abord opposée au lock : c'est
« la version commande du bump », l'échappatoire impérative. Ce qu'il faut, c'est la
**spécialiser** pour nommer les inputs à re-locker, **tous par défaut**. Les deux coexistent :
le lock est l'état déclaratif décidé au build, l'annotation est l'ordre ponctuel d'avancer.
Si une résolution tardive est vraiment nécessaire, elle doit être un **pipeline Tekton**
explicite qui produit un lock committé — un *build* auditable — jamais un contrôleur qui
résout au moment de réconcilier.

## ★★★ Ce design est DÉJÀ écrit dans l'API du flox-controller — c'est le code qui a dérivé

`flox-controller` `api/v1alpha1/floxenv_types.go` le dit noir sur blanc :

- « The lock is **NEVER** produced by the controller (no per-node re-locking → determinism) »
- `status.Lock` : « Produced by a lock-producer (the operator env-bumper, or an in-cluster
  lock-controller reacting to `spec.update`) — **NEVER by the node-agent, which realises it
  verbatim and never re-locks** »
- `status.RelockToken` : l'annotation y est décrite comme la commande d'opérateur, « to force a
  re-lock without editing spec or restarting ».

⚠️ **Le coupable est `propagateRelock`** (`internal/controller/floxcatalog_controller.go`
~101-135) : il estampille la **révision résolue du catalogue** comme token de relock sur
**chaque** `FloxEnv` que ce catalogue sert. Donc tout changement de révision du catalogue force
un relock COMPLET de tous les envs, et l'échappatoire devient le chemin normal — d'où la
résolution au runtime, la course d'ordre, et le fetch github sur le nœud.

Donc on ne conçoit rien : **on restaure une intention déjà écrite.** Forme visée :

1. `spec.lock` — le `manifest.lock` committé, rendu depuis git ; le nœud le réalise verbatim.
   (Aujourd'hui `Lock` est en **status**, produit à l'arrivée.)
2. `flox.seedmatic.io/relock` — la commande, dont la valeur gagne une **portée** : les inputs à
   re-locker, `*`/vide = tous. Garder la sémantique de token (valeur ≠ `status.relockToken`
   ⇒ agir une fois) : un impératif dans un objet déclaratif a besoin d'une clé d'idempotence.
3. `propagateRelock` cesse d'estampiller en bloc — c'est LA régression par rapport à l'intention
   documentée.

## ★★ Le producteur manquant a déjà un nom dans le code : le « leader-elected cluster manager »

Il n'y a **aucune élection de leader** dans flox-controller, et c'est assumé comme provisoire :

- `cmd/flox-controller/main.go:132` : « the resolution is idempotent; leader election is a future
  optimisation, not required »
- `internal/controller/floxcatalog_controller.go:36` : « node-agent (idempotent) **until split
  into a leader-elected cluster manager** »

★ Donc le « lock producer » à extraire **est** ce cluster manager déjà promis. Les trois rôles à
séparer : **produire** (leader, sortie = un commit git) / **livrer** (Flux → `spec.lock`) /
**réaliser** (agent par nœud, verbatim, sans jamais résoudre).

⚠️ La justification « idempotent » est ce qui cède : résoudre contre une branche qui bouge n'est
pas idempotent *dans le temps*. Et surtout — flox-controller est un **DaemonSet**, donc chaque
pod écrit `status.Lock` / `status.RelockToken` du MÊME objet (`floxenv_controller.go:152`) :
N écrivains sur un champ, le dernier gagne, pendant que chaque nœud a réalisé sa propre closure
(`alreadyRealized` teste « **this node** has realised »). Le statut ne décrit alors la réalité
d'aucun nœud. Masqué aujourd'hui parce que chaque cluster est mono-nœud ; sortira au premier
pool à deux nœuds — et le roster canonique prévoit master + peer1 + peer2.

## Écrire dans git : oui, mais par le producteur seul

Question de l'utilisateur, juste : un contrôleur qui **produit** un lock détient une décision que
git n'a pas, et le prochain rendu la remplacera silencieusement par le lock committé plus ancien.
Donc soit il commite, soit il ne produit pas. Précédent de premier rang : l'
`image-automation-controller` de Flux commite les tags qu'il résout.

- **Cible du commit** : la branche `flox-catalogue` (le lock vit en
  `environment.d/<folder>/<env>/manifest.lock`). Vérifié : Flux la surveille, mais le rendu se
  déclenche sur `feature/nixos-node-substrate` (`.tekton/render.yaml`) — donc **pas de boucle**.
- ⚠️ **Droit d'écriture** : le nœud n'a qu'un token `contents:read`. Un producteur qui commite
  écrit sur une branche que Flux réconcilie, donc un agent compromis se fait livrer ce qu'il
  veut. Ça plaide pour le **pipeline Tekton** plutôt qu'un contrôleur : le droit vit dans un
  build isolé, pas dans un agent présent sur chaque nœud.

★ Le test à appliquer à toute nouvelle référence : « est-ce que quelque chose, à l'exécution,
va devoir aller chercher dehors pour savoir quoi faire ? » Si oui, la décision manque dans git.

⚠️ Ne pas confondre avec `airGapped: true` de l'`agentConfig` CAPRKE2 : ce flag dit seulement
que rke2 ne télécharge pas SES artefacts (ils sont dans l'image), pas que le nœud est sans
réseau. Les nœuds ont bien du réseau (ils récupèrent github pour `install-rke2-config`, ils
joignent le tailnet). J'ai fait ce contresens une fois.

See [[seed-incluster-crd-and-binary-travel-by-two-pins]]
[[node-env-gated-oneshots-skip-capn-nodes]].
