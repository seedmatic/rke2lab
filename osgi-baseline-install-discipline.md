---
name: osgi-baseline-install-discipline
description: "DECISION + RATIONALE (user, 2026-06-19, settled in the osgi-cleanup slice): how OSGi per-package versioning + bnd-baseline reconcile with the 'never install to ~/.m2' rule. The two are NOT in tension once you separate the SNAPSHOT work-artifact (NEVER installed — siblings always resolve from sources via -am; this is what prevents a dev silently building against a stale frozen jar and masking workspace incoherence) from the BASELINE jar (a RELEASE coordinate ≠ SNAPSHOT, installed by a DELIBERATE rare act to freeze a stable reference, consumed ONLY by bnd-baseline as a comparison, a build-dependency of NOTHING). @Version graved in package-info is the source of truth; bnd-baseline VERIFIES it against the last frozen point. For THIS slice: graved @Version + bnd-baseline failOnMissing=false (no-op until a release exists, bites the moment one does). The git-tag/release-driven baseline flow is a FUTURE slice (needs CI + a baseline repo, NOT ~/.m2)."
metadata:
  node_type: memory
  type: feedback
---

## The decision (user, 2026-06-19)

Per-package OSGi versioning ([[osgi-package-versioning-carto]]) and bnd-baseline enforcement
coexist with the long-standing **"never install project jars to `~/.m2`"** rule — once you see
that the rule and the baseline mechanism act on **two different coordinates** that never meet.

## Why the no-install rule exists (the user's real concern — name it precisely)

The danger is NOT "a jar sits in `~/.m2`". It is: **a module resolves a BUILD DEPENDENCY from a
frozen jar instead of rebuilding the sibling from workspace sources.** The developer doesn't
notice the sibling is stale, so the incoherence of his in-flight changes is **masked** — the build
goes green against yesterday's frozen frère. The rule forbids installing the work-artifact so that
siblings ALWAYS resolve through the reactor from `target/` (always `-am`), never from a frozen jar.
(The documented failure mode: `NodeEnvContributor not found` from a `-pl` build *without* `-am`
pulling a stale sibling — that hazard is the install of the **SNAPSHOT** coordinate, the one
siblings declare as a dependency.)

## Why baseline-install does NOT reintroduce that danger (the key insight)

Two **orthogonal** mechanisms, different coordinates, different consumers:

| | Coordinate | Installed? | Consumed as |
|---|---|---|---|
| **Work artifact** | `0.1.0-SNAPSHOT` | **NEVER** (rule intact) | build dependency → ALWAYS reactor via `-am` |
| **Baseline jar** | a **release** (≠ SNAPSHOT) | deliberate, rare | bnd-baseline **comparison ONLY**, build-dependency of NOTHING |

- bnd-baseline does **not** read `<dependencies>`. It fetches a comparison artifact out-of-band by
  coordinate and puts it on **no compilation classpath**. No module "depends on" the baseline.
- The masking hazard comes ONLY from installing the **SNAPSHOT** (the coordinate siblings depend
  on). As long as that coordinate is never installed, **no build can resolve a sibling from
  `~/.m2`** → incoherence cannot be masked. The protection stays **entire**.
- The only jar ever installed is a **release** coordinate that nobody declares as a dependency →
  invisible to reactor resolution.

## bnd works on @Version, not the GAV (why SNAPSHOT-vs-SNAPSHOT was a red herring)

bnd-baseline diffs the **package `@Version`** (graved in `package-info.java`), NOT the Maven GAV.
GAV staying `0.1.0-SNAPSHOT` on both sides is irrelevant — the contract is carried by `@Version`.
What bnd enforces, against the last frozen baseline:
- exported API **additive** (new exported method/class) → demands **minor** → FAIL otherwise.
- exported API **breaking** (removed/changed signature) → demands **major** → FAIL otherwise.
- purely **internal** change (private body, non-exported class) → bnd says **nothing** (exported
  surface unchanged). Micro-on-internal-change is **discipline**, NOT machine-enforced (bnd only
  sees the exported surface). So "bump as soon as you touch the package" is too strong: Maven only
  *demands* the bump when the **exported API** moved.

## The deliberate-install model (user's correction of my over-strict first read)

My first refutation targeted **automatic** install on every build — THAT is disqualifying (moving
reference → WIP churn, a build's own breakages get swallowed as the reference re-freezes each run).
The user's model is different and correct: **install is a deliberate, rare act that DECLARES "I
hold a stable version"** — it freezes a reference point. Between two installs the baseline stays
fixed, so bnd measures the **delta since the last STABLE point**, not since the last build. That is
the correct semver semantics. Since the previous frozen build passed the check, any new breakage
can only come from the new changes → you know exactly when to bump.

## Scoped rule (replaces the blunt "never install")

- **Never install the SNAPSHOT work-artifact.** Siblings always resolve from sources via `-am`.
  (This is the original rule, unchanged — it is what prevents masked workspace incoherence.)
- **Installing a baseline jar is a distinct, safe, deliberate act**: a **release** coordinate
  (≠ SNAPSHOT), consumed only by bnd-baseline as a comparison, a build-dependency of nothing. Do it
  only to freeze a stable reference, never automatically in a work/CI build.

## ★ SHIPPED 2026-06-19 — the no-SNAPSHOT-install guard is now machine-enforced (commit on integration)

The "never install the SNAPSHOT work-artifact" rule is no longer just discipline: a SECOND
maven-enforcer execution `no-snapshot-install` (built-in rule `requireReleaseVersion`) is bound to the
`install` PHASE in `build-parent/pom.xml`, beside `enforce-build-tooling`. Direct-to-branch (the user's
call: tiny change, big safety payoff for dev + both of us).

- **Why the `install` phase, not earlier.** `install` is reached only by `mvn install`/`deploy`, never
  by `package`/`verify` — so the guard is SILENT on the normal build and fires only when an install is
  attempted. Naming the intent matters: a guard hooked on `post-integration-test` (which also runs
  before `install`) would block in time but read as nonsense to a future reader. There is no
  `pre-install`/`post-install` phase — `install` is the idiomatic, legible hook. (Decided with the user.)
- **Intra-phase ordering verified — the guard fires BEFORE the install-plugin writes.** Within one
  phase, executions run in plugin declaration order; the enforcer (inherited early via build-parent)
  precedes `maven-install-plugin`. PROVEN: clean-`~/.m2` re-test twice → `mvn install` of a SNAPSHOT
  exits 1 AND writes NOTHING to `~/.m2`. (A first run left a stale pom that looked like a write — it was
  pre-existing residue, not the guard; the clean re-test settled it.) So the SNAPSHOT never lands.
- **The baseline install still works** — a RELEASE-versioned jar passes `requireReleaseVersion`, so the
  deliberate freeze act above is unaffected. `mvn package -Posgi` stays green (guard silent).

## What ships in THIS slice (osgi-cleanup) vs later

- **NOW:** grave `@Version` in `package-info` across osgi/ (source of truth) + wire
  `bnd-baseline-maven-plugin` with **`failOnMissing=false`** in `osgi/bundle-parent`. No stable
  version exists yet (we are introducing `@Version` for the first time) → first build no-ops
  cleanly. `failOnMissing=false` is the ACTIVATOR of the deliberate-install model: silent until a
  baseline is frozen, biting the moment one is.
- **The first deliberate install (freeze 1.0.0) is the USER's act, right after the merge.** Future
  work then bites against it.
- **FUTURE slice:** a release/tag-driven baseline flow (git tag → build → reference jar published
  to a dedicated **baseline repo**, NOT `~/.m2`) — needs CI, which does not exist yet. bnd diffs
  compiled **jars**, not git source, so a SHA/tag alone isn't a baseline; it needs the built jar of
  that tagged release. That's why it's its own slice.

See [[osgi-package-versioning-carto]] (the per-package @Version rule this enforces),
[[contract-placement-and-versioning-carto]] (first application — contracts at 1.0.0),
[[osgi-cleanup-slice-state]] (the slice wiring failOnMissing=false now),
[[build-verification-gotchas]] (the `-am`/reactor resolution discipline this protects).
