---
name: non-null-input-rule
description: "rke2lab CODE RULE (user, 2026-06-19): never accept a parameter that can be null as input — every method input is non-null by convention. So the codebase is non-null-by-default by discipline (not yet machine-enforced by annotations). Consequence: a null check on an INPUT is a smell (the contract says it can't be null); guard at construction/boundary instead. The only legitimate null friction is at THIRD-PARTY API boundaries (JDK Optional, OSGi Framework, JUnit callbacks) which aren't annotated — that's where IDE null-analysis noise comes from, not from our code."
metadata:
  node_type: memory
  type: feedback
---

The rule: **no input parameter is ever nullable.** Every method assumes its inputs are
non-null; callers must satisfy that. The codebase is therefore non-null-by-default — by
discipline today, not yet by annotation.

**Why:** it removes a whole class of defensive `if (x == null)` noise and pushes the
"can this be absent?" question to the boundary, where it's modelled explicitly
(`Optional` as a RETURN type, a builder default, a factory) rather than smeared across
every callee. Absence is represented, never passed as a bare null.

**How to apply:**
- Don't write null-guards on inputs — the contract already forbids null. If you feel the
  urge, the real fix is upstream (the producer should never hand you null).
- Represent optionality at the boundary: `Optional<T>` returns, `with*`/builder defaults,
  factory methods — never a nullable parameter.
- The genuine exception is **third-party APIs** that aren't null-annotated (JDK
  `Optional`, OSGi `Framework`, JUnit `ExtensionContext` callbacks). IDE null-analysis
  warnings almost always point THERE, not at a rule violation in our code — see
  [[java-cleanup-backlog]] (the null-analysis item) for why `.vscode` null-analysis is
  parked at `disabled` and what honoring the rule machine-side (`@NonNullByDefault`)
  would take.

Making the rule machine-enforced (jspecify or `org.eclipse.jdt.annotation` +
`@NonNullByDefault` at package scope) is the proper way to lock it in — a backlog
decision, not done yet.
