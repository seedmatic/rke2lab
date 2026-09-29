---
name: coherence-rules-coordinator
description: "Walker-retirement Task 4 decision — cross-domain rule is option B (explicit pure check), NOT option A (encode in universe filter); resolve() is the single coherence-rule coordinator; a rule = pure fn of AssembledRegistry; CoherenceRule interface DEFERRED to rule-of-three."
metadata: 
  node_type: memory
  type: project
  originSessionId: 08cc57e2-031f-46fa-a5b1-6454d74079ad
---

Settled during step-1 walker-retirement (manifests resolver, branch
`design/step1-walker-retirement-spec`). See [[hub:docrepo-dag-state]] STEP 1 section.

## The decision

The **cross-domain rule** (a unit in domain A may depend on a unit in domain B only
if A transitively dependsOn B) is enforced by **option B**: an explicit, pure check
`CrossDomainRule.check(AssembledRegistry)` that **throws a diagnosable error**, invoked
by `resolve()` (NOT by the constructor). We rejected **option A** (encode the rule into
the universe so an illegal cross-domain `require` becomes unsatisfiable).

## Why option A was rejected (the concern — verified empirically by TDD, not guessed)

Option A does **not** throw. Because each unit enters the closure only via its domain's
`requireAll(NS_UNIT,"(domain=id)")` containment, which carries `cardinality:=multiple`,
Felix **silently prunes** an offending unit from the match set when its own mandatory
`require` can't be satisfied. Observed closure for the illegal case = `{synthesis-root,
b/v, b}` — `a/u` and domain `a` just vanished, resolve "succeeded". A misconfigured
manifest unit would silently disappear from synthesis with **no error** — strictly worse
than the old walker's loud throw.

Deeper reason it can't live in the resolver at all: in the illegal case `a/u` requires
`(unit=b/v)` and **b/v genuinely exists**, so under plain filters `a/u` resolves to it
happily — the closure is *complete and coherent*. The cross-domain violation is therefore
**invisible to the resolver and to any completeness/head-count check**. The rule is a
**policy ABOVE resolution**, not derivable from it.

## The governing principle (user)

**The system must REPORT an error condition for a responsible person to fix — it must
NOT resolve/auto-correct it silently.** The silent prune was the system quietly
auto-resolving a misconfiguration; that is exactly what we forbid. [[validate-at-the-boundary]]

## The concern, in the health-system mirror (the metaphor we reasoned with)

Map the manifests case onto the shipped doctor/health system to see why the silent
prune is the unacceptable outcome (specialist↔unit, referral-policy↔cross-domain rule,
medical record↔AssembledRegistry, admitting gate↔resolve()):

```mermaid
flowchart TB
  subgraph CTX["AssembledRegistry  ≈  Patient medical record (the dossier every rule reads)"]
    REC["units + domain graph + membership\n≈ history, referrals, treatable-symptom baselines"]
  end

  GATE["resolve()  ≈  the admitting / case-review gate\n(the SINGLE coordinator: every coherence rule runs here)"]
  REC --> GATE

  GATE --> RULE{"CrossDomainRule.check\n≈ 'may this specialist treat across a\nspecialty it has no referral to?'"}

  RULE -->|"legal: A dependsOn B\n≈ referral on file"| OK["CoherentRegistry / stamped permit\n≈ care plan admitted, visit order set"]

  RULE -->|"ILLEGAL cross-domain dep\n≈ cardiac order, no cardiology referral"| FORK{"how is the violation handled?"}

  FORK -->|"OPTION A — REJECTED\nFelix cardinality:=multiple prune"| SILENT["unit silently dropped from closure\n≈ clinic quietly DISCHARGES the patient,\ncardiac treatment never happens,\nchart says 'resolved' — NO ONE TOLD"]
  FORK -->|"OPTION B — CHOSEN\nexplicit check throws, diagnosable"| LOUD["unmet-need-with-WHY surfaced\n≈ Referral / Assessment raised:\n'crosses domains a→b, no a→b dependency'\nclinician fixes the config"]

  SILENT -.->|"the footgun we forbid"| X(("✗ auto-resolved\nin silence"))
  LOUD -.->|"the principle"| Y(("✓ reported\nfor a human"))
```

Why the resolver structurally CAN'T own this rule (the non-obvious crux): in the illegal
case the dependency unit `b/v` genuinely EXISTS, so a plain `(unit=b/v)` require resolves
happily — the closure is coherent AND complete, the violation is invisible to resolution
and to any head-count. It's a **policy above resolution** (≈ the referral rule is hospital
policy, not something the scheduler can infer from the roster). Hence an explicit rule
that reports. [[hub:specialist-as-ledger-northstar]] [[referral-roundtrip-state]]

## C4 view — the coordinator and the rule-context contract

**C4 L2/L3 (component) — who reads what, who throws, where the order is born.**
The type-state is the spine: an *assembled* registry (build-only, never throws on a
malformed graph) → `resolve()` (the coordinator, the single gate) → a *coherent*
registry (the only type carrying a visit order). Every coherence rule reads the SAME
context (the assembled registry / the resolved wiring) and REPORTS by throwing; none
auto-resolves.

```mermaid
flowchart TB
  subgraph ASMBOX["«assembled state» ManifestsDomainRegistry — build-only, never throws"]
    REG["domains() + manifestUnits()\n+ requireDomainIdForManifestsUnit()\n(structural invariants only:\nno dup id / no multi-domain unit / no empty domain)"]
  end

  subgraph GATEBOX["«coordinator» resolve() — the SINGLE coherence gate"]
    direction TB
    UNIV["ManifestsUniverse(this)\nbuild UnitResource universe (fine layer)"]
    XDOM{"CrossDomainRule.check(this)\nPOLICY rule — reads assembled registry\nthrows: 'crosses a→b, no a→b dep'"}
    RES{"UnitResolver.resolve(root)\nSATISFIABILITY — unknown ref\n→ ResolutionException (wrapped)"}
    ORD{"ManifestsVisitOrder.order()\nACYCLICITY — cycle → throws\n(carries the deleted acyclic check)"}
    UNIV --> XDOM --> RES --> ORD
  end

  subgraph COHBOX["«coherent state» CoherentManifestsDomainRegistry — the ONLY type with an order"]
    VO["visitOrder() : List<ManifestsUnit>\n(no re-resolve; pkg-private ctor)"]
  end

  SYN["DefaultManifestSynthesisService\nloops visitOrder() → unchanged visitor seam\nManifestsUnitContext(chart,domainId,unitId,resolver)"]

  REG -->|"resolve()"| GATEBOX
  ORD -->|"all rules passed"| COHBOX
  COHBOX --> SYN

  ASMBOX -. "type-state: NO visitOrder() here\n⇒ cannot synthesize without resolving (compile-time)" .- COHBOX
```

**The 3 coherence checks the gate coordinates (one context, three owners — rule-of-three not yet reached):**

```mermaid
flowchart LR
  CTX["context read by every rule:\nthe assembled registry / resolved wiring"]
  CTX --> R1["satisfiability\nowner: UnitResolver\nunknown ref → ResolutionException"]
  CTX --> R2["acyclicity\nowner: ManifestsVisitOrder topo-sort\ncycle → IllegalStateException"]
  CTX --> R3["cross-domain POLICY\nowner: CrossDomainRule (the exemplar)\nillegal cross-dep → IllegalStateException"]
  R3 -. "only R3 has the pure-fn-of-AssembledRegistry shape;\nR1/R2 are structural side-effects with different owners\n⇒ DEFER a CoherenceRule interface until a 3rd policy rule" .- R3
```

## The coordinator + the rule-context contract (the generalization)

Metaphor: a coherence rule is a code-compliance check run at **one permit gate**, where
every inspector is handed **the same dossier** and reads it (never re-surveys the site).

- **Coordinator = `resolve()`** — the spec's "single coherence gate", now with teeth: the
  ONE place every coherence rule runs.
- **Rule context = the `AssembledRegistry`** (the dossier): domain graph
  (`dependsOnDomainIds`) + unit graph (`dependsOnManifestsUnitIds`) + unit→domain
  membership. A coherence rule is a **pure function of the AssembledRegistry**.
  `CrossDomainRule` is the exemplar.
- **`CoherentRegistry`** = the stamped permit: proof all rules passed + carries the visit order.

The rule *family* already exists (3 checks, today scattered with different owners/shapes):
satisfiability (the resolver, unknown-ref → ResolutionException), acyclicity (the topo-sort
in `ManifestsVisitOrder`), cross-domain policy (`CrossDomainRule`).

## What we deferred and why

Only `CrossDomainRule` currently has the "pure predicate over AssembledRegistry" shape;
the other two have different shapes/owners. Per no-speculative-abstraction
([[refactor-pipeline-candidates]], CLAUDE.md "three similar lines beat a premature
helper"), we **DO NOT** introduce a `CoherenceRule` interface / rule-registry yet. We
**DEFER it to rule-of-three** — when a THIRD same-shaped *policy* rule appears, extraction
is mechanical because the context (`AssembledRegistry`) is already crystallized. For now:
name `resolve()` as the coordinator, document the "pure fn of AssembledRegistry" convention,
keep `CrossDomainRule` as one explicit step inside `resolve()`.

Task 5 wires `CrossDomainRule.check(registry)` into `resolve()` right after deleting the
old constructor `validate*`, so the rule moves constructor→resolve, stated and loud, never silent.
