---
name: ask-question-mark-recommended
description: "In AskUserQuestion, mark the option I recommend with '(Recommended)' in its label and put it FIRST — the user wants my opinion visible so he can challenge it against his, not guess it. Born 2026-06-18."
metadata:
  node_type: memory
  type: feedback
---

**When using AskUserQuestion, give my own recommendation explicitly:** put the option I'd choose
FIRST and append `(Recommended)` to its label (the tool description already supports this convention).

**Why (user, 2026-06-18):** "j'aime bien quand tu mets entre parenthèse (recommended) pour donner ton
propre avis. ça m'aide dans mon choix, je le challenge avec le(s) tien(s)." He uses my stated opinion
as a foil — a position to push against — not as a nudge to comply. A bare neutral list of options
hides the very thing he wants (my judgement), forcing him to infer it.

**How to apply:**
- *Single-select:* put the recommended option FIRST, append `(Recommended)`.
- *Multi-select (user, 2026-06-18: "des fois tu fais des choix multiples, et donc le (Recommended) peut
  être multiple aussi"):* `(Recommended)` marks the whole SET I'd pick — tag EVERY option I'd check,
  which may be several (or none, if I'd recommend leaving all unchecked — then say so in the question
  text). It is "my recommended selection", not "the single best", so it scales with the cardinality of
  the question.
- Mark exactly what I actually recommend; if I genuinely have no preference, say so in the question
  text rather than faking one.
- Keep giving the *reasoning* in my prose around the tool call (the chip only carries the label) — he
  challenges the reasoning, not just the pick.
- This complements [[standing-autonomy-except-runtime-config]]: reserve AskUserQuestion for genuine
  user-owned forks, but WHEN I do ask, lead with my recommendation rather than a flat menu.
