---
name: renamed-words-translate-before-searching
description: "Les mots renommés du périmètre : une mémoire antérieure à un renommage garde l'ancien mot, donc une recherche littérale ne trouve rien dans le code. Table de traduction."
metadata:
  node_type: memory
  type: reference
  originSessionId: eef830de-9690-4fc8-ac3d-71fec81b1988
  modified: 2026-10-03T07:15:15.752Z
---

Déplacé depuis `MEMORY.md` le 2026-10-03 (l'index doit rester des pointeurs ;
cette table est du contenu). Vérifié contre `feature/nixos-node-substrate` le
2026-09-19. **Une mémoire antérieure à un renommage garde l'ancien mot, donc une
recherche littérale ne trouve rien dans le code — traduire avant de chercher.**

- **`seed-master` → `seed-outcluster`** (`17de9ca95`, 2026-10-01, poussé sur
  `feature/viewpoint-separation`). 161 fichiers ; **aucun identifiant Java déplacé** (le
  package reste `io.seedmatic.rke2lab.controlplane.*`), donc seules les coordonnées et la
  prose changent. À traduire en cherchant : le sélecteur Maven est désormais
  `-pl :seed-outcluster`, la sortie de flake `.#seed-outcluster`, le binding nix
  `seedOutclusterJar`, le jar `share/java/seed-outcluster.jar`, le log
  `.local.d/seed-outcluster.log`. La mémoire et `.claude/` n'ont **délibérément pas** été
  balayés (un enregistrement réécrit pour coller à un renommage postérieur le falsifie) —
  `incontainer-test-not-in-seedmaster-reactor.md` garde l'ancien mot dans son NOM DE
  FICHIER, et `docrepo-dag-state.md` porte ~30 mentions historiques. ★ Pourquoi le mot a
  changé : la paire ne contrastait pas sur un seul axe — la chaîne est
  **`operator -> pulumi -> outcluster -> incluster`**, où le maillon 1 est une PERSONNE
  (celle qui lance `pulumi`), donc `master` nommait un rang dans une suite de LIEUX.
  L'orthographe (sans trait d'union interne) est ce qui a gardé la BRANCHE
  `seed-incluster` hors périmètre : aucun renommage de ref, aucun déplacement d'URL
  d'input, aucun remous de lock. See [[runmode-livegate-pulumi-abstraction]].
- **`flox-catalogue` → `flox-catalog`** (`e18039c37`, 2026-10-02). L'américain `catalog` est
  la convention mesurée du périmètre (~1386 contre ~94) ; la branche en était le seul
  britannique et il fuyait dans le `GitRepository` Flux, la CR `FloxCatalog` (`catalogue` →
  `catalog`) et chaque ref `floxcatalog:catalogue#…`. Ancien nom **supprimé** sur `origin`.
  NON balayés, exprès : le français, le scratch `.claude/`, le hub (3 mentions, autre dépôt).
  See [[flox-envs-vendored-as-subtree]].
- **`world-gateway` → `seed-broker-port`** (`acd68a510`). Branche vivante : `seed-broker`
  dans 392 fichiers, `world-gateway` dans 14 (docs seulement). La mémoire dit encore
  `world-gateway` dans 49 fichiers / 27 entrées d'index — les entrées
  `world-gateway-2a…2e` nomment des *phases de chantier*, donc leurs titres restent ;
  seul le vocabulaire a bougé.
- Chaîne de specs, les trois désormais un seul fichier
  `docs/architecture/osgi/seed-broker-spec.adoc` : `multiplexor-spec.adoc` →
  `world-exchange-spec.adoc` (`7e289e236`) → renommé encore avec le mot (`adc113f61`,
  `acd68a510`).
- Doc re-découpée par nature (`e0945ec71`) : `osgi/pipeline-spec.adoc` →
  `docs/architecture/bdd/bdd.adoc` ; `atlas/host-pipeline.adoc` →
  `docs/architecture/atlas/seed.adoc`. Les citations pleinement qualifiées ont été
  corrigées le 09-19 ; les mentions nues de `pipeline-spec.adoc` laissées telles quelles —
  plusieurs *parlent* du re-découpage, et `pipeline-spec-recut-plan.md` porte même la
  commande `git mv`.
- `osgi/two-gates-spec.adoc` est cité par deux entrées mais **n'a jamais existé** dans
  aucun commit — une doc promise, jamais écrite.
- `docs/manifests-architecture.adoc` → `docs/architecture/manifests/manifests-architecture.adoc`.
- **Pas périmé, inter-dépôts :** `docs/host-builder-phases.adoc`,
  `docs/operator-commands.adoc`, `docs/vm-operator-runbook.adoc` vivent dans **ndh**.
  22 des 76 chemins cités paraissaient manquants ; un tiers appartenaient simplement à un
  autre dépôt.
