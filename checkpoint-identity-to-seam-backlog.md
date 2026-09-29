---
name: checkpoint-identity-to-seam-backlog
description: "BACKLOG — Checkpoint est un enum d'identité partagée pure ; sa maison est le seam (seed-broker-port), pas doctor.records. Incrément dédié, hors périmètre 2B"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-19T08:03:35.967Z
---

**Créé le 2026-09-19 en récupérant une entrée d'index orpheline.** Ce nœud était référencé
5 fois — une entrée dans `INDEX-osgi-pipeline.md` et 4 liens `[[checkpoint-identity-to-seam-backlog]]` —
alors que le fichier **n'a jamais existé dans git**. Le fait ne vivait donc que dans la ligne
d'index, exactement l'inversion que la convention interdit (le détail appartient au fichier).
Contenu ci-dessous repris verbatim de cette ligne ; rien n'a été ajouté ni vérifié depuis.

`Checkpoint` (dans `doctor.records`) est un enum d'**identité partagée PURE** —
slug / scenarioTitle / resourceName, zéro référence à un record — consommé à la fois par le BDD et
Pulumi côté host ET par le doctor.

Par la logique du seam, sa maison est donc le **seam** (`gateway-port` / world-gateway, aujourd'hui
`seed-broker-port` — voir la table de renommage dans [MEMORY.md](MEMORY.md)), et non le
`doctor.records` réservé à un seul bundle.

C'est **hors du périmètre de 2B** (le consult-path), parce que `Checkpoint` sert aussi l'egress et le
verdict : le déplacement mérite un **incrément « identity-to-seam » dédié**.

See [[world-gateway-2a-execution-state]].
