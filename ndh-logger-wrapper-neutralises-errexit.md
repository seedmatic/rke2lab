---
name: ndh-logger-wrapper-neutralises-errexit
description: "Règle ndh (mesurée 2026-09-20) — ndh::logger:command:run appelle la fonction depuis une condition `if`, ce qui rend `set -e` INERTE dans tout le corps ; et le statut rapporté est celui de la DERNIÈRE commande, donc un script peut échouer au milieu et se déclarer « completed successfully »"
metadata:
  node_type: memory
  type: reference
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-20T20:20:15.288Z
---

`modules/.common.d/shell.d/logger.sh` (~l.325-336) enveloppe ainsi :

```sh
local rc=0
if "$@"; then … else rc=$?; echo "[$tag] ndh::logger:command:run failed (rc=$rc)" >&2; fi
return "$rc"
```

Deux conséquences, et aucune n'est évidente à la lecture du script enveloppé :

1. **`errexit` est inerte.** Une commande évaluée dans une condition `if` ne déclenche pas la sortie
   sur erreur, et ça vaut **récursivement** pour tout ce que la fonction exécute. Donc le
   `set -euo pipefail` en tête d'un script ndh ne protège **pas** le corps de `main` — chaque échec
   doit être testé explicitement.
2. **Le statut rapporté est celui de la DERNIÈRE commande de la fonction.** Un script peut échouer
   en son milieu et rendre 0 parce que sa dernière ligne réussit ; le logger annonce alors
   « completed successfully ».

**Cas réel qui a coûté une soirée** (corrigé dans ndh `0cb40b57`) :
`modules/home-manager/incus-remote.d/ensure-incus-operator-remote.sh` faisait
`incus remote remove … || true` puis `incus remote add …`. Le `remove` échouait (incus refuse de
supprimer le remote **par défaut**, que la dernière ligne du script met par défaut — il se piégeait
lui-même dès sa première exécution réussie), le `|| true` masquait l'échec, le `add` échouait avec
« Remote … already exists », le certificat serveur n'était **jamais** épinglé, et le script se
déclarait réussi. Symptôme à l'autre bout : pulumi en `x509: certificate signed by unknown
authority` sur `https://bioskop-nixos:8443`, et **ré-activer ne changeait rien** puisque ça rejouait
la même séquence. Diagnostic impossible sans le journal unifié : les messages du script n'atterrissent
pas dans le log de `darwin-rebuild` mais sous le tag du logger —
`/usr/bin/log show --last 2h --predicate 'eventMessage CONTAINS "[<tag>]"'` (chemin absolu
obligatoire, zsh a un builtin `log` qui le masque).

**How to apply :** dans un script enveloppé par ce logger, tester chaque commande qui compte
(`if ! cmd; then echo … >&2; return 1; fi`), ne jamais écrire `|| true` sur une commande dont
l'échec change le résultat, et **terminer par une vérification de l'état voulu** plutôt que par la
dernière action — ce qu'on doit à l'appelant est un effet obtenu, pas une commande lancée. C'est la
même leçon que [[destructive-gate-needs-three-valued-probe]] transposée au succès : une réponse vide
ne doit pas se lire comme un succès.

See [[unmanaged-mac-ssh-material-chain]].
