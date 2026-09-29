---
name: fix-debt-when-passing-through
description: "When editing a class for another reason and you spot debt (null-in-our-API, dead code, a broken discipline), fix it in the same pass — that is the signal the work was done properly."
metadata:
  type: feedback
---

**User rule (2026-07-08):** "quand on passe sur des classes et qu'on voit de la dette on fix.
là on voit qu'on a fait notre travail." — when a task takes you through a class and you see
debt there, fix it as you pass; the fixed debt is the evidence the job was done properly.

**Why:** debt left behind while you had the file open and understood it is debt that will cost
a full re-understanding later. Fixing it in the pass is nearly free (context is loaded) and is
what distinguishes finished work from work that merely compiles. Aligns with the CLAUDE.md
"never leave dead code behind" / code-hygiene discipline — this generalises it to ALL debt
noticed in passing, not only dead code.

**How to apply:**
- Editing a class and you spot a null-in-our-API (a raw nullable field/return), a dead constant,
  a `!= null` guard chain, a broken instance-passing/uniformity discipline → fix it now, in the
  same session, not "a later pass."
- If the fix is large enough to blur the current commit, split it into its own commit (the user
  is relaxed about merging commits — [[gateway-is-rest-in-jvm-insight]] session — so prefer
  clarity but don't agonise over the boundary).
- Exemplar this session: while converting doctor verbs to SeedHandler + adding type=runtime, spotted
  ResolvedBundle's three raw-nullable fields (embed/symbolicName/file) → lifted all three to Optional
  in a dedicated commit before continuing. See [[null-safety-optional-from-source-to-resolver]].
