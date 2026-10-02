---
name: command-output-to-stable-file-not-tail
description: "FEEDBACK (2026-10-02) — rediriger la sortie COMPLÈTE d'une commande longue vers un fichier à nom stable ($TMPDIR=/tmp/.nxmatic, ou .local.d/), puis n'extraire que le nécessaire ; ne jamais piper dans `tail` en ne gardant que la fin"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 90ef886d-f66b-4b38-8d3d-ec4dc838147c
  modified: 2026-10-02T09:43:06.344Z
---

L'utilisateur veut pouvoir **surveiller de son côté** la sortie des commandes que je lance. Donc :

```bash
LOG="$TMPDIR/<nom-stable>.log"        # $TMPDIR = /tmp/.nxmatic (mesuré 2026-10-02)
<commande> > "$LOG" 2>&1
grep -E '<ce qui compte>' "$LOG"      # je n'extrais que le nécessaire dans mon contexte
```

**Un nom stable, réutilisé** pour les commandes que je lance en séquence — pas un nom par
invocation : c'est ce qui lui permet de garder un `tail -f` ouvert dessus. `.local.d/` du worktree
est l'autre emplacement admis (il est ignoré par git, `.gitignore:25`).

**Pourquoi :** la sortie des outils ne lui est pas montrée de façon fiable, donc un `| tail -N`
lui cache tout *et* détruit la preuve. Mesuré le même jour : j'avais pipé un build Maven dans
`tail -60`, et le fichier résultant n'avait **que le récapitulatif du réacteur** — donc zéro ligne
`Tests run:`. J'ai alors conclu « 0 test exécuté », qui était un artefact de ma propre troncature,
pas une mesure. Il a fallu relancer le build pour prouver les 121 lignes `Tests run:`.

**Comment l'appliquer :** rediriger d'abord, extraire ensuite. Un `tee` ne convient pas pour une
sortie volumineuse — il la déverse aussi dans mon contexte, ce qui annule le bénéfice.

C'est une instance de [[measure-the-derived-value-not-the-assumed-one]] : la valeur que je lisais
était dérivée de mon propre filtre, pas de la commande. Et ça se combine avec
[[rke2lab-canonical-maven-invocation]], qui dit *quoi* lancer là où cette note dit *comment en
capturer la sortie*.
