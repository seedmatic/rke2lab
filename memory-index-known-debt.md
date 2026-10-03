---
name: memory-index-known-debt
description: "La dette connue de cette mémoire : entrées trop longues, INDEX-osgi-pipeline dormant, et l'audit de [[liens]] morts — dont 7 fausses morts causées par DEUX arbres de mémoire divergents."
metadata:
  node_type: memory
  type: reference
  originSessionId: eef830de-9690-4fc8-ac3d-71fec81b1988
  modified: 2026-10-03T07:15:37.298Z
---

Déplacé depuis `MEMORY.md` le 2026-10-03 (l'index doit rester des pointeurs).

- **201 des 224 entrées dépassent la règle des 200 caractères** (moyenne 609). Les
  compresser est sûr — chaque fichier de sujet fait déjà 3-10× sa ligne d'index — mais
  **vérifier entrée par entrée d'abord** : `cluster-seed-execution-state` avait sa
  décision la plus récente (fork B, 2026-07-08) vivant **uniquement** dans sa ligne
  d'index alors que le fichier s'arrêtait au 07-07. Raccourcir à l'aveugle perd des
  faits ; celle-là a été rapatriée dans le fichier le 09-19.
- `INDEX-osgi-pipeline.md` fait 77 entrées / 62 Ko, rien de commité après le
  2026-08-14. Il porte une liste `## À trancher` de 16 candidats dormants —
  **proposés, rien déplacé** : une heuristique mot-clé+date en a mal jugé trois, donc
  l'arbitrage demande un humain qui connaît le chantier.
- **Audit des `[[liens]]` morts, lancé le 2026-09-19** (296 refs uniques / 1657
  occurrences) : 252 résolues, 20 étaient des fichiers du hub sans leur préfixe `hub:` —
  l'étape 4 de migration de la spec de structure, jamais faite — désormais corrigées sur
  87 occurrences. Toutes les cibles d'entrées d'index existent sauf une, qui n'a jamais
  existé dans git et dont le contenu ne vivait que dans sa ligne d'index : reconstruite
  en `checkpoint-identity-to-seam-backlog.md`.
  ⚠️ **Cette reconstruction n'a jamais pu être commitée, pendant 10 jours.**
  `.claude/.gitignore` portait un `checkpoint-*.md` **non ancré** (destiné aux
  checkpoints de session), qui matchait aussi le fichier de mémoire dont le nom commence
  par le même mot. Ancré en `/checkpoint-*.md` le 2026-09-29. **Une règle en forme de nom
  doit dire OÙ elle s'applique.**
- **★ Corrigé le 2026-09-29 — 7 de ces 16 refs « mortes » ne l'ont jamais été.**
  L'audit du 09-19 a tourné contre UN arbre de mémoire pendant que les fichiers étaient
  dans l'autre : la mémoire était écrite dans le checkout `main` tandis que le travail
  était commité depuis le worktree de chaque session, donc deux arbres ont divergé (248
  fichiers contre 325). La fusion à 3 voies qui les a réconciliés a récupéré
  `caprke2-byo-ca-secret-contract`,
  `cold-start-cleanup-and-funnel-cert-persistence`, `flox-carrier-containerize-spike`,
  `flox-controller-build-deploy-state`, `flox-envs-runtime-crd-delivery`,
  `flox-gate-secret-flow-devlxd-then-certmanager` et
  `ghapp-webhook-reconcile-and-funnel-rename`.
  **9 refs restent vraiment mortes**, re-vérifiées contre l'arbre réconcilié ET le hub le
  2026-09-29 : `realm-boundary-gate` (7×), `spec-figure-first-reading-loop` (4×),
  `c4-diagrams-flowchart-not-native-dsl` (3×), `port-edge-domain-ownership`,
  `thread-manifestations`, `systematic-debugging`, `runtime-view`, `render-config`,
  `pipeline-spec-legibility-cleanup-post-go`. Chacune est un fait que quelqu'un a voulu
  écrire et n'a pas écrit ; les entrées citantes en gardent l'essentiel.
  Pour relancer : extraire `\[\[name\]\]` des `*.md`, résoudre les noms nus contre ce
  répertoire et les `hub:` contre le répertoire de mémoire du hub. Ignorer
  `name`/`x`/`links`/`link` — ce sont des exemples de syntaxe de l'en-tête, pas des refs.
  **Et le lancer contre UN SEUL arbre réconcilié** — un audit sur une mémoire forkée
  rapporte de fausses morts.
