---
name: options-always-as-c4-diagrams
description: "Feedback (2026-06-26): whenever I present the user options/forks to choose between, ALWAYS figure them as C4 / UML diagrams (Mermaid), not just prose tables. The user reflects best on diagrams. Applies to every option-presentation, not only the framing of a problem."
metadata:
  node_type: memory
  type: feedback
---

The user, after I posed the A/B/C practitioner-instantiation fork (and earlier the médecin-conseil
problem): **"il faut toujours quand tu me présentes des options les figurer dans un diagramme C4
UML."** First said for the *problématique* ("c'est là où je réfléchis le mieux"), then GENERALISED to
any option set he must choose between.

**Why:** the user is a visual/structural thinker; a side-by-side diagram of options lets him compare
shapes at a glance, where prose tables bury the difference. This is also how the project's specs are
authored (C4 + Mermaid per CLAUDE.md's documentation standard), so option diagrams graduate straight
into the `.adoc` specs.

**How to apply:**
- Any time I offer a choice (fork A/B/C, two designs, alternative placements), draw each option as a
  C4 / Mermaid diagram BEFORE asking him to pick — not a prose-only table.
- Prefer true C4 structure (containers/components/relationships) over ad-hoc flowcharts when the
  options are about where things live or how they relate. Code snippets MAY accompany but do not
  REPLACE the diagram.
- The whiteboard is a **Claude artifact** now (default — self-contained HTML + mermaid, per
  [[hub:diagram-preview-file]]; `.claude/claude-preview.adoc` + kroki is the offline fallback); the chosen
  option then graduates into the permanent spec.
- Pair with [[specs-current-at-brainstorm-end]] (the diagrams end up in the specs) and the brainstorm
  ritual.
