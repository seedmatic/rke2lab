---
name: null-safety-optional-from-source-to-resolver
description: "The project convention for a genuinely-optional value in OUR API (param, field, return): model it Optional<T>, NOT @Nullable. Wrap the null at the exact frontier where a foreign API returns it (Optional.ofNullable(...)), then carry the Optional all the way to the maillon that knows how to resolve the empty case — never let null traverse an intermediate link. Dead null-carriers are deleted, not dressed up. @Nullable is a last resort and every use is submitted to the user one by one with the why."
metadata:
  node_type: memory
  type: feedback
---

**The rule (user, 2026-07-02).** Null that is *meant* in our own API is expressed with `Optional<T>`,
never `@Nullable`. Concretely:

- **At the source that produces the null** — typically a foreign API (`Throwable.getMessage()`,
  `System.getenv(...)`, a Pulumi getter) — wrap it *at that frontier*: `Optional.ofNullable(x)`. The
  null never travels as a bare reference past the boundary.
- **Carry the Optional from that source to the consumer that knows how to resolve the empty case** —
  the maillon that has a default, an alternative branch, or an empty-collection answer. That consumer
  does the `.map/.orElse/.ifPresent/.isEmpty`. Do NOT let null cross an intermediate link that cannot
  handle it (the anti-pattern we removed: `backendDir.orElse(null)` feeding a nullable ctor that then
  re-checks `== null`).
- **Push it to the *real* source, not just the first caller** — e.g. a nullable `Path backendDir`
  turned out to originate from `StackMedicalRecordJournal.backendDir()` which already returned
  `Optional<Path>`; the fix threaded that Optional straight through instead of collapsing and
  re-wrapping.
- **A dead null-carrier is deleted, not wrapped** — e.g. bbox's `dependsOn Resource` (only ever passed
  null through the whole chain) and `ReconcileResult.resource` (never read) were removed as dead code;
  `Optional` would have dressed up something that had no caller.
- **Correlated optionals travel as one type** — two params only ever used together (`ownerName` +
  `registry` in `TargetChecksumPipeline`) became one `Optional<ChecksumScope>` record, not two
  `Optional<>`.
- **Unboxing of a pre-filled map** (`counts.get(FAILED)` on an EnumMap filled for every key) →
  `getOrDefault(FAILED, 0)`, pure, no annotation.

**`@Nullable` is the last resort.** Only when `Optional` genuinely does not fit (and set-once fields go
to `@MonotonicNonNull` instead — see [[null-safety-set-once-fields-monotonic]]). **Every `@Nullable`
addition is submitted to the user one by one, with the reason, BEFORE adding it** — a hard rule for this
codebase. When the user is asked, the recurring preference is: push the null out to the frontier as an
`Optional` rather than absorb it inside a helper with `@Nullable`.

Context: the seed-master exec null-cleanup chantier (2026-07-02), reducing ~79 NullAway warnings to
zero without scattering `@Nullable`. Related: [[null-safety-set-once-fields-monotonic]].
