---
name: formatter-strips-imports-added-before-usage
description: "Piège outillage : le hook PostToolUse google-java-format retire les imports inutilisés AU MOMENT où il tourne — un import ajouté dans un Edit AVANT que son usage n'existe (Edit suivant) est strippé entre les deux → build cannot-find-symbol. Ajouter l'usage d'abord (ou import+usage dans le MÊME Edit), et vérifier les imports avant de builder."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 718e49f8-20da-47bc-97da-58c26b893740
  modified: 2026-09-09T14:46:08.484Z
---

**Le hook `PostToolUse` (google-java-format) réécrit chaque fichier Java après CHAQUE Edit/Write et RETIRE les imports inutilisés à cet instant.**

**Why :** si tu ajoutes un import dans un Edit, puis son usage (nouvelle méthode/champ) dans un Edit ULTÉRIEUR, le formateur tourne entre les deux, voit l'import comme unused, et le supprime. Tes Edits suivants ciblent d'autres `old_string` → ils ne ré-ajoutent pas l'import. Résultat : `cannot find symbol` / `package X does not exist` au build, alors que le code source « semble » correct dans ton historique d'Edits. Ça a coûté 2 builds ratés au chantier C2 (2026-09-09) : imports strippés dans `ClusterSeal` et surtout `ClusterPkiSealScenario` (les 6 imports partis).

**How to apply :**
- Préfère ajouter l'USAGE d'abord (le symbole non résolu force l'auto-import ou signale), OU import+usage dans le **même** Edit.
- Ne te fie PAS aux diagnostics IDE « import never used » juste après avoir ajouté un import en avance : le formateur va le supprimer, pas juste l'avertir.
- Après une séquence multi-Edit sur un fichier, VÉRIFIE les imports (`grep -n "import ..."`) AVANT de lancer un build coûteux — un `grep` vaut mieux qu'un cycle Maven de plusieurs minutes.
- Corollaire NullAway (même chantier) : `Optional.ofNullable(map.get(k))` est rejeté (`Optional<@Nullable String>`); un **témoin de type** `Optional.<String>ofNullable(...)` le corrige. Et pour un « présent/absent » déterministe, tester la présence de CLÉ (`ofNullable(get)`), pas `isBlank()` sur la valeur (mauvais signal, non-déterministe). See [[caprke2-byo-ca-secret-contract]].
