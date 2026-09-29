---
name: build-gates-over-review-reminders
description: The user's standing preference — turn a discipline he keeps re-asking for (instance-passing, spec-coverage, record-purity) into a BUILD-TIME gate in the staging-extension, not a convention re-checked at review. A gate is seen at every build (impossible to forget) and, once the debt is cleared, its default-ERROR level locks the rule so the anti-pattern can never reappear.
metadata:
  type: feedback
---

When a discipline is one the user keeps re-requesting at review time (instance-passing over static
helpers, specs-current, record-purity), the durable fix is a **build-time gate** in the
staging-extension — a twin of `RecordPurity`/`SpecCoverage` — not a note or a convention.

**Why:** a convention is re-litigated every review and silently rots between them. A gate is *seen at
every build*: "la ca sera visible, tu le verras a chaque build, impossible d'oublier. et une fois la
dette epuree, impossible de recommencer." It makes the debt an inventory that shrinks visibly, and the
default-ERROR level locks the rule the moment a bundle is clean, so the anti-pattern cannot regress.

**How to apply:**
- The drift-report level is graduated, not binary: `@SpecGoverned(DriftLevel)` with
  `IGNORE | WARN | ERROR`, **default ERROR**. ERROR breaks the build, WARN lists the real drift types
  (green build, visible backlog), IGNORE is silent (build infra only).
- A freshly-introduced law runs fail-AT-end (collect ALL violations across ALL bundles, fail once) so
  the whole debt shows in one run — then dirty bundles are set to WARN, clean ones keep default ERROR.
- New laws are INSTANCES reached from their subject `ResolvedBundle` (`recordPurity()`,
  `specCoverage()`, `instanceDiscipline()`), never static helpers — they obey the very discipline they
  enforce ([[object-graph-navigability-principle]]).
- Built in Java with a test (the user rejected shell: "on refactor et boom ca casse").

See [[spec-coverage-gate-state]] [[specs-current-at-brainstorm-end]].
