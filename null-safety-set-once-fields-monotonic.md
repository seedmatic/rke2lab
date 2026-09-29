---
name: null-safety-set-once-fields-monotonic
description: "The project convention for NullAway 'field not initialized' on SET-ONCE fields (builder fields, lazy caches): annotate them @MonotonicNonNull (checker-qual, pinned in BOM, provided scope) and keep an Objects.requireNonNull(x, \"x\") guard at build()/read time. Chosen 2026-07-02 over @Nullable+requireNonNull and over a home-grown @BuilderField exclusion; validated empirically (NullAway 0.13.7 in JSpecify mode recognises it, the init warning disappears)."
metadata:
  node_type: memory
  type: feedback
---

**The rule.** A field that starts null and is assigned exactly once before use — a *builder field*
(set by a fluent setter, read in `build()`) or a *lazy cache* (`if (x == null) x = compute(); return
x;`) — is annotated **`@MonotonicNonNull`** (`org.checkerframework.checker.nullness.qual`), NOT
`@Nullable`. Keep a real `Objects.requireNonNull(x, "x")` guard where it is read (the `build()` call, or
the cache accessor) so a forgotten set fails fast with the field name instead of a distant NPE.

**Why `@MonotonicNonNull` and not the alternatives** (decided with the user, 2026-07-02):

- vs **`@Nullable` + requireNonNull** (the older pattern still in `SystemdSynthesisContext` etc.):
  `@Nullable` *lies* — it says "may be null anytime", but a builder field is set-once, never nulled.
  `@MonotonicNonNull` states the truth ("starts null, rises once to non-null, never falls"). Both
  silence the init warning and both still need `requireNonNull` at the read (NullAway sees the read as
  nullable in both cases), so the code is nearly identical — `@MonotonicNonNull` just names the intent
  honestly.
- vs a **home-grown `@BuilderField` added to NullAway's `ExcludedFieldAnnotations`** (like `@Mock`,
  `@Reference`, `@ScenarioStage`): that makes the field read as `@NonNull` → a *premature read inside
  the builder* would go UNDETECTED (blinded field, distant NPE). `@MonotonicNonNull` keeps premature
  reads caught. It also needs no build-parent edit and no maintained custom annotation, and it covers
  lazy caches too (a `@BuilderField` name would be meaningless on a cache).
- vs **an annotation processor / forking NullAway / Immutables-AutoValue**: disproportionate for ~4
  builders. An APT can't rewrite an existing `build()` body (it only generates files); NullAway is a
  deliberately *local* checker and won't do the cross-field "annotate build(), infer required =
  non-Optional fields" analysis. Immutables/AutoValue *would* apply exactly that rule natively (non-
  `Optional` accessor ⇒ required) by GENERATING the builder — but that is a transverse architecture
  decision (new dep, rewrite value types as abstract specs, competes with the project's record-first
  idiom), parked as a separate horizon, NOT part of a null-cleanup.

**Wiring (done 2026-07-02).** `checker-qual` version pinned in `bom/pom.xml`
(`<checker-qual.version>4.2.1`) under `dependencyManagement`; declared once in `build-parent/pom.xml`
as a `provided` dependency (analysis-only, mirrors how `jspecify` is declared — nothing resolves it at
runtime). So every module inherits it.

**Also settled in the same session** — orthogonal jgiven fix: `@ScenarioStage` (nested-stage injection)
was missing from NullAway's `ExcludedFieldAnnotations` in `build-parent/pom.xml` (only
`ProvidedScenarioState`/`ExpectedScenarioState`/`ScenarioState` were listed). Added it — resolves the
`ClusterReadinessScenario$When` init warnings at the source, uniformly for any future nested scenario.

**How to apply going forward.** New builder or lazy cache under `@NullMarked` → `@MonotonicNonNull` on
the set-once field + `requireNonNull` at the read. Do NOT reach for `@Nullable` on these (that annotation
is for genuinely-optional values, and per the user's rule genuinely-optional in OUR API should be
`Optional<T>` anyway — see [[null-safety-optional-from-source-to-resolver]]). Related null-safety arc:
the seed-master exec null-cleanup chantier (2026-07-02).
