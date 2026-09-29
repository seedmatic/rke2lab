---
name: handoff-prompt-opens-on-progress-narration
description: Every handoff prompt I write for a next-conversation/next-workspace must OPEN with the obligation to read the "Progress narration" note and apply it all session.
metadata:
  node_type: memory
  type: feedback
---

When I write a handoff prompt to seed a fresh conversation or a newly-created workspace, its
FIRST line must be an imperative to read `.claude/hub/instructions.md` § "Progress narration"
(the user's "tiens-moi au courant") and apply it for the whole session: one line before each
tool batch, running progress on multi-step work ("3/5 done, now the 4th"), never a silent chain
of tool calls, say so if stuck. The spinner words carry no signal — the prose is the only one.

**Why:** narration drifted repeatedly within a single session (this one included); the note is
auto-loaded via `CLAUDE.md → @.claude/hub/instructions.md`, but auto-loaded ≠ heeded. Putting the
obligation at the TOP of the handoff prompt — ahead of even the memory-read step — makes the new
agent treat it as a framing rule, not fine print it skims past.

**How to apply:** lead the handoff with a bold line like — "AVANT TOUT : lis
`.claude/hub/instructions.md` § Progress narration et applique-la toute la session (une ligne
avant chaque batch, progrès au fil de l'eau, jamais d'outils en silence)." — then the chantier
brief. See [[external-edges-chantier-handoff]].
