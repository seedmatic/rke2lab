---
name: ndh-logger-wrapper-disables-errexit
description: "★ `set -e` ne fait RIEN dans un script ndh lancé par ndh::logger:command:run — il invoque `if \"$@\"; then`, et POSIX supprime errexit dans une condition. Toute commande non vérifiée échoue en SILENCE. Et ndh::logger:error n'existe pas."
metadata:
  node_type: memory
  type: reference
  originSessionId: c2c2a472-f982-468d-98de-1b23bbf4e274
  modified: 2026-09-26T08:54:45.413Z
---

Mesuré le **2026-09-26**, au prix d'un faux succès sur une opération de cluster.

## Le piège

`modules/.common.d/shell.d/logger.sh` (~ligne 325) exécute la fonction enveloppée ainsi :

```bash
set -x
local rc=0
if "$@"; then   # ← ICI
  echo "... completed successfully" >&2
else
  rc=$?
  ...
fi
return "$rc"
```

Exécuter `main` **dans la condition d'un `if`** supprime `errexit` pour **tout l'arbre d'appel** — c'est
la sémantique POSIX (« the -e setting shall be ignored when executing … any command of an AND-OR list
other than the last »), et **ré-affirmer `set -e` à l'intérieur de la fonction n'y change rien**. Donc
tout script en `ndh::logger:command:run "$tag" main "$@"` — l'idiome maison — n'a **aucune** gestion
d'erreur implicite, quel que soit son `set -euo pipefail` d'en-tête.

**Symptôme vécu** : `pkgs/incus-cluster-join.d/join.sh` a imprimé « token minted », « pinning out of
the raft » puis « joined and out of the raft » alors que `incus cluster list` n'avait qu'un membre et
que le joignant se déclarait standalone. Le préseed avait échoué, le `role add` aussi, et le script a
continué. Corrigé dans ndh `77b23284` en vérifiant explicitement chaque étape mutante.

## Deux corollaires qui mordent aussi

★ **`ndh::logger:error` N'EXISTE PAS.** Le logger n'expose que `ndh::logger:notice` (+ ses internes :
`command:run`, `command:resolve`, `hints:resolve`, `lines:tag`, `stderr:redirect`, `streams:redirect`,
`run:marker:*`). Dix appels à `error` dans join.sh imprimaient `command not found` au lieu de leur
message. Vérifier l'API avant d'inventer un nom.

★ **stderr ne va PAS à la console.** `command:run` redirige fd2 vers le sink (journald / unified log)
après avoir préservé l'original en **fd3**. `ndh::logger:notice` écrit sur fd3 — c'est le SEUL chemin
vers le terminal de l'opérateur. Un `echo … >&2` dans une app interactive est donc invisible : c'est
pourquoi un échec ressemblait à un silence. Pour lire ce qui est parti dans le sink, le wrapper imprime
lui-même la commande `log show … CONTAINS "[<tag>]"` au démarrage.

## La règle qui en découle

1. **Vérifier explicitement** chaque commande dont l'échec compte : `if ! cmd; then … return 1; fi`.
   Ne jamais compter sur `set -e` sous cet idiome.
2. **Assertions POSITIVES.** Le join assertait une *absence* (« `roles` ne contient pas `database` »),
   ce qui passe quand la commande productrice échoue et que `roles` est **vide**. Une assertion qui
   réussit sur une entrée manquante n'est pas une assertion. Même famille que le
   `grep '"server_clustered":true'` qui ne matchait jamais (incus écrit une espace après le `:`).
3. **Erreurs par `ndh::logger:notice`**, jamais par `>&2`, dans tout ce qu'un opérateur lance à la main.

See [[bridges-leave-incus-forced-not-chosen]] [[tailnet-node-identity-ephemeral-ghosts]]
