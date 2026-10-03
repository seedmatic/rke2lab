---
name: worktree-dag-derivable-from-lock
description: "★★ Le DAG de worktrees EST dérivable depuis l'arbre de develop (11/11 entrées, 3 sources) ; les coupures `follows = \"\"` n'y sont PAS l'obstacle — l'obstacle est qu'un ref de flake est une BRANCHE, pas un slot."
metadata:
  node_type: memory
  type: project
  originSessionId: 13780c4b-126d-4e5a-90a2-87dd9187d136
  modified: 2026-10-02T22:22:51.249Z
---

**Mesuré le 2026-10-03** depuis `rke2lab.d/develop` (principal), en lecture seule. Question posée :
la liste de `worktrees.yaml` (branche `workspace`, siégée par `feature/ssot-manifest`) peut-elle être
**dérivée** au lieu d'être énumérée ? L'atlas de cette branche la classait en
« Deliberately deferred — deriving the closure instead of enumerating it ». Verdict : **oui, 11/11**,
par trois sources complémentaires. Relayé à la session d'intégration (`develop-60`) le même jour comme
**nouvel incrément, pas commencé, rien d'écrit**.

## Les trois sources

. **`flake.lock` de develop**, BFS depuis `root` avec `follows` résolus, filtré `owner == "seedmatic"`
  → **7** : profondeur 1 = `nix-flake-commons/develop`, `flox-controller/develop`,
  `flox-nri-plugin/develop` (id local `flox-runtime`), `ndh/develop`, `rke2lab/seed-incluster` ;
  profondeur 2 = `claude-hub/main` et `nnh/main`, tous deux inputs de **ndh**.
. **`git worktree list`** → `memory`, `workspace`, `flox-catalog`, `seed-incluster`, + les 4
  `manifests/*` de render que le manifeste omet exprès.
. **le trailer `git-subtree-split` de `.flox-envs.d`** → `refs/remotes/fleet/flox-subtree`, donc le
  dépôt `fleet` (seul membre sans `flake.nix`). ⚠️ `.claude/hub` n'a **pas** de trailer exploitable
  (le skill `hub-subtree-sync` utilise `--ignore-joins`) — il n'est couvert que parce que le lock
  l'atteint en profondeur 2.

★ **Le filtre `owner == seedmatic` est porteur, pas cosmétique** : sans lui la clôture passe de 7 à
**822 nœuds** (sur 12099 au total) et fait entrer `nxmatic/nix-darwin-home` en profondeur 4 — le seul
dépôt que l'INVARIANT de `flake.nix` interdit comme input. Tout au-delà de la profondeur 2 est l'org
de forks `nxmatic`.

## ★★ Les coupures `follows = ""` ne sont PAS l'obstacle

Le lock entier n'en contient que **quatre** : `flake-commons.inputs.{cachix,devenv,nix} = []` et
`ndh.inputs.rke2lab = []`. Les trois premières coupent du tiers, et le `flake.lock` de
nix-flake-commons contient **ZÉRO** nœud `owner == seedmatic` → aucune coupure ne peut cacher un
membre du workspace. La quatrième résout vers **ROOT = le principal**, qui est déjà folder 0. Le
*cut idiom* est donc **transparent** au DAG de worktrees.

⚠️ **Piège d'implémentation, tombé dedans moi-même** : dans un `flake.lock` un follows vide est un
**tableau vide**, et un résolveur qui fait `last` sur le chemin renvoie `null` et **avale l'arête**.
Seule règle nécessaire : *tableau vide ⇒ le nœud racine*. C'est ce que la spec appelle `follows`-ROOT,
not removal.

## L'obstacle réel : un ref de flake est une BRANCHE, pas un slot

Le modèle découple les deux exprès (slot `develop` → `feature/claude-session-guards` ; slot
`workspace` → `feature/ssot-manifest`). Les 7 refs du lock tombent aujourd'hui sur un slot homonyme
par **convention** (on épingle les branches d'intégration), pas par construction — et c'est **déjà
cassé dans l'autre sens** : `ndh/flake.nix` épingle
`github:seedmatic/rke2lab/feature/viewpoint-separation`, et **aucun slot** ne porte ce nom (le travail
siège dans `develop`). Inverser branche → slot exige de lire le `git worktree list` de l'autre dépôt,
donc que son worktree existe déjà : **la dérivation peut VÉRIFIER un workspace, pas l'AMORCER.** C'est
l'option (b) et son objection dans `flake-lock-propagation.adoc` — voir
[[flake-lock-ownership-and-relock]].

Mesure annexe : `flox-controller` et `flox-nri-plugin` ont **deux** worktrees chacun (`develop` et
`main`). Un scan de disque sur-collecte ; c'est le **lock** qui désambiguïse le slot consommé. Les
deux sources sont complémentaires, pas redondantes.

## ⚠️ Conflit d'ordonnancement avec l'incrément suivant

L'atlas prévoit de convertir les inputs en refs indirects `flake:{id}` pour que le registre généré les
redirige. Après ça, l'`original` d'un nœud vaut `{"id":"ndh","type":"indirect"}` — **plus d'owner, de
repo ni de ref** : la source de dérivation s'évapore. Il reste `locked.url`
(`git+file:///…/ndh.d/develop`), qui donne le **slot** directement plutôt qu'une branche (mieux), mais
qui est machine-local et serait **commité** dans le lock — ce qui contredit « nothing absolute is ever
committed ». **Donc la décision dérivation-vs-énumération doit être prise AVANT la conversion en refs
indirects.** Les deux incréments sont ordonnés, pas seulement séquencés.

## Recommandation transmise

Ne **pas** remplacer le manifeste par une clôture dérivée : ajouter la dérivation comme **assertion**
dans `workspace verify` (dériver, échouer si ça diverge de l'énumération). Ça attrape exactement la
pourriture que l'atlas mesure (un ref nommant une branche fusionnée, un worktree oublié) sans rendre
le générateur dépendant de l'état de travail des voisins. Deux champs restent déclarés quoi qu'il
arrive : le **`slot`** (non dérivable d'un ref) et le **`why`** par entrée.
