---
name: specs-current-at-brainstorm-end
description: "Feedback (2026-06-26): the architecture specs in docs/ must be left up to date at the END of every brainstorm or handoff — a record/component absent from the specs is DRIFT (code-out-of-spec), not a doc omission. Memory notes are NOT a substitute for the .adoc specs."
metadata:
  node_type: memory
  type: feedback
---

The user, after noticing the just-designed médecin-conseil existed only as a memory note and not in
`docs/`: **"il faut laisser les specs à jour à la fin d'un brainstorm ou d'un handoff"** — and the
sharper framing: **a record or component absent from the specs means we DRIFTED in the specs, code
out-of-spec.** Coverage spec↔code is a real invariant, not a nicety.

**Why:** the project is built as a navigable model (the clinical metaphor, the OSGi seams). A type
that exists in code but nowhere in `docs/` is a hole in that model — a place where you can no longer
reason from the specs because the specs lie by omission. Memory notes (dense, for my recall) are NOT
the same artifact as the `.adoc` specs (the shared, versioned source of truth, with C4 figures and
cross-refs, per CLAUDE.md's documentation standard). Both are needed; one does not replace the other.

**How to apply:**
- At the end of a brainstorm/handoff, BEFORE closing: update or add the `.adoc` spec(s) for whatever
  the brainstorm settled, register them in `docs/README.adoc`, and add bidirectional cross-refs.
- Then run a coverage check: every record/port/component in the touched domain should appear in some
  spec. What is absent is either drift to document, or a deliberate non-spec'd internal (impls in
  `.internal` are conventionally not spec'd — only ports/records/SPI are). Log what was dropped.
- A design that is settled-but-not-built is still spec'd — marked `Status: DESIGN, not built`. A
  direction whose mechanism is still open is spec'd as a DIRECTION with the fork named, not as
  build-ready.
- The audit can be a quick scripted pass: list the domain's exported types, `grep -rlw` each in
  `docs/`, the zero-hit set is the drift candidates. Done by hand 2026-06-26 for the doctor domain
  (45/57 covered; the gaps were the efficacy axis + a rename not propagated).

This is the same single-source-of-truth instinct as [[object-graph-navigability-principle]] applied
to docs: the spec must stay reachable-and-true, not orphaned from the code. Candidate to harden into
a real gate later (a build-time or CI coverage check), noted as backlog, not improvised.
