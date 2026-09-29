---
name: atlas-before-after-shift-at-merge
description: "Atlas before/after figures shift forward one notch once the 'before' is realized (collapse the consumed transition, keep before/after only for the still-live next evolution); and this doc cleanup belongs at EACH merge, not as a retroactive sweep"
metadata:
  node_type: memory
  type: feedback
---

Two rules the user gave (2026-07-05) while cleaning the integration atlas after the engine-lifecycle
socle + world-gateway consolidation landed.

**1. The before/after SHIFT.** An atlas before/after figure exists to prove a model change is
ADDITIVE. Once the "before" no longer exists — the transition is realized, nobody lives in the old
state anymore — the pair COLLAPSES: the "after" becomes the plain current state, and if a figure is
still warranted it is the NEXT "before" (the before of the next planned evolution). Their words:
"quand avant n'existe plus il ne reste plus que la vue apres, qui devient le future avant, donc shift
en avant si on a besoin de garder la figure." So do NOT keep the before/after of a consumed
transition forever — the atlas would silt up with ghost layers. Collapse consumed transitions to
current-state; reserve before/after for what is STILL LIVE (e.g. world-gateway 2026-07: the
records→Document seam change is consumed → collapses; the host-side migration of the ~51
Severity.parse/toOutputMap sites is still live → that is the new before/after).

**2. Cleanup belongs at EACH merge, incrementally — not as a retroactive sweep.** Their words: "on
devrait faire ce travail de maniere incremental, a chaque merge. je devais etre trop presse de passer
a la suite." The doc cruft accumulated (increment/stage/SHIPPED/2A-2D narration, stale before/afters)
BECAUSE the shift-and-collapse was skipped at merge time in the rush to the next thing. The lesson:
**a merge's Definition of Done includes the doc shift** — when a feature lands, collapse the
transition it just consumed in the atlas and strip the increment narration from its specs, right
then, same as running the tests. This is the timing refinement of [[tidy-on-drift-widens-scope]]
(that says "widen to tidy on drift"; this says "and the natural moment is the merge").

**How to apply at a merge:** after integrating a feature, ask — (a) did this consume a transition an
atlas before/after was tracking? If so, collapse it to current-state (shift the "after" forward), and
only draw a new before/after if a NEXT evolution is now the live one. (b) do the feature's specs still
carry "increment N / stage / SHIPPED / NOT-yet-built" scaffolding now that it is built? Strip it.
Related: [[tidy-on-drift-widens-scope]] [[hub:docs-diagrams-not-java]] [[spec-figure-first-reading-loop]].
