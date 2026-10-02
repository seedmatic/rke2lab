---
name: leading-dash-argument-reads-as-an-option
description: "Les slugs de transcription Claude commencent par `-` (le `/` du chemin encodé). basename, ls et un case bash les lisent comme des OPTIONS et répondent « rien » au lieu de refuser — trois outils piégés en une minute le 2026-10-02, avec déplacement d'un répertoire de transcriptions vivant."
metadata:
  node_type: memory
  type: reference
  originSessionId: 7cfc7caf-f565-414c-9bdd-a69b03f9151b
  modified: 2026-10-02T12:19:27.863Z
---

**Le fait, et il est structurel :** un dossier de transcriptions Claude est nommé d'après le **cwd
encodé**, chaque caractère non alphanumérique devenant `-`. Comme un chemin absolu commence par `/`,
**tout slug commence par `-`** :

```
/Volumes/git-worktree-store/seedmatic/rke2lab.d/develop
-> -Volumes-git-worktree-store-seedmatic-rke2lab-d-develop
```

Donc chaque fois qu'un slug est passé en argument, il ressemble à un drapeau. Et les outils Unix ne
disent pas « je ne peux pas lire cet argument » : ils répondent **moins**, silencieusement. C'est la
famille de [[measure-the-derived-value-not-the-assumed-one]], appliquée à un seul caractère.

## Trois outils, une minute, et un dégât réel (2026-10-02)

| Outil | Ce qu'il a fait | Conséquence |
|---|---|---|
| `basename "$slug"` | lu `-Volumes-…` comme une option, imprimé **rien** | la cible du `mv` est devenue le répertoire `projects/` lui-même → la boucle a parcouru tous ses frères et **déplacé un répertoire de transcriptions VIVANT** |
| `ls -l "$slug"/*.jsonl` | mêmes options avalées | a répondu « aucune transcription », ce qui m'a fait croire une seconde à une perte de données (les 9 étaient intactes) |
| `case "$a" in -*) …` | tout slug classé « drapeau inconnu » | `relink <slug> --apply` répondait `unknown flag: -Volumes-…` |

Rien n'a été perdu — vérifié transcription par transcription — mais seul le fait d'**exécuter** l'a
révélé. C'est l'argument qui a fait introduire `bats` dans l'env flox de la branche.

## Les trois parades

1. **`${v##*/}` plutôt que `basename`.** L'expansion de paramètre n'a aucune analyse d'options, donc
   rien à tromper. Vrai aussi pour `${v%/*}` au lieu de `dirname`.
2. **`--` ou un préfixe `./` pour tout outil externe** : `ls -- "$d"`, `rm -rf -- "$d"`,
   `find ./"$d"`. Sans ça, la commande de diagnostic ment comme le code.
3. **Seul un DOUBLE tiret marque un drapeau** dans un analyseur maison : `--apply)` et `--*)`, jamais
   `-*)`, sinon on rejette tous les arguments légitimes.

## Le réflexe

Quand une commande répond « rien » sur un argument qui commence par `-`, ce n'est presque jamais un
ensemble vide : c'est l'argument qui a été mangé. Le discriminateur tient en une commande — rejouer
avec `--` et comparer.

See [[measure-the-derived-value-not-the-assumed-one]] [[claude-memory-cascade-state]]
