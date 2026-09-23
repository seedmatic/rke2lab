---
name: references-resolve-at-build-not-runtime
description: "Décision de l'utilisateur (2026-09-23) : les références se résolvent au BUILD, jamais au runtime — le lock doit être dans git ; exception admise seulement via un pipeline Tekton explicite"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-23T13:25:05.544Z
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

**How to apply:** faire voyager le lock **committé** avec le rendu (la `FloxEnv` porte le
`manifest.lock`, pas un ordre de relock). Si une résolution tardive est vraiment nécessaire,
elle doit être un **pipeline Tekton** explicite qui produit un lock committé — un *build*
auditable et rejouable — jamais un contrôleur qui résout au moment de réconcilier.

★ Le test à appliquer à toute nouvelle référence : « est-ce que quelque chose, à l'exécution,
va devoir aller chercher dehors pour savoir quoi faire ? » Si oui, la décision manque dans git.

⚠️ Ne pas confondre avec `airGapped: true` de l'`agentConfig` CAPRKE2 : ce flag dit seulement
que rke2 ne télécharge pas SES artefacts (ils sont dans l'image), pas que le nœud est sans
réseau. Les nœuds ont bien du réseau (ils récupèrent github pour `install-rke2-config`, ils
joignent le tailnet). J'ai fait ce contresens une fois.

See [[seed-incluster-crd-and-binary-travel-by-two-pins]]
[[node-env-gated-oneshots-skip-capn-nodes]].
