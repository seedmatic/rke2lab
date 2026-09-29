---
name: pattern-gate-coverage-map
description: Coverage map — which documented project patterns/invariants are enforced by an automated StagingGate and which are only prose (regression risk). Several gatable invariants are scattered across docs/ (doctor, unit-repo, config) and are the SAME "do not leak across a frontier" family REALM_BOUNDARY generalizes. Names the not-yet-backlogged gate candidates to pose after REALM_BOUNDARY flips ERROR.
metadata:
  type: project
---

**Backlog/analysis (user, 2026-06-27): "we wrote many patterns, we put gates but not on all — it
would be a shame to REGRESS on points where we already made the code progress. And the docs
cross-reference a lot, an important pattern can be misplaced in the tree."**

A gate is an anti-regression RATCHET, not just a debt detector (the spec: "once a bundle's debt is
cleared, its default level LOCKS the rule so the anti-pattern can never silently reappear"). So the
patterns where the code is ALREADY clean but UNGATED are the real regression risk — worth locking
cheaply.

## Coverage today (mapped across the WHOLE docs/architecture/ tree)

GATED-ERROR (locked): RECORD_PURITY, SPEC_COVERAGE, INSTANCE_DISCIPLINE (osgi only), the enforcer
rules (`no-snapshot-install`, banned-deps `osgi.cmpn`), spotless + `-Xlint:all`. All in
`maven-embed-staging-ext/staging-extension` + `build-parent/pom.xml`.

GATED-WARN (shrinking backlog): the three staging gates opted-down per package via
`@GovernedBy(..., WARN)`; **REALM_BOUNDARY** in flight (Plan 1, WARN now → ERROR end of Plan 2).

PLANNED (memos already exist — do NOT re-create): [[instance-discipline-gate-misses-host-backlog]]
(extend INSTANCE_DISCIPLINE to exec/seed-master host space — it currently reads osgi bundles only and
is blind to ~25 host statics), [[system-exports-seam-gate-backlog]] (FRAMEWORK_SYSTEMPACKAGES_EXTRA =
seam only), [[dependency-analyze-gate-backlog]] (used/unused dep drift).

## The not-yet-backlogged gate candidates (the gaps this map adds)

1. **ManifestDomainCatalog discipline** — "Never hardcode domain ID strings"
   (`docs/architecture/manifests/manifest-domain-catalog-pattern.adoc:148`). A REAL bug (`clusterApi`
   vs `cluster-api`, May 2026) was fixed; the code is clean but nothing stops its return. Cheap gate:
   ASM/grep the string literals passed to `isEnabled(...)` / domain-map `put(...)` against the
   catalog's known keys. Twin: the other identifier catalogs (`BootstrapPaths.HostPathCatalog`,
   `SystemdUnitCatalog`) — same "single source of truth for identifiers" discipline (CLAUDE.md).

2. **config single-reader** — "one component reads Pulumi config; everything else consumes an
   immutable snapshot — never a hardcoded switch" (`docs/architecture/config/config-restructuring-spec.adoc:49,351`).
   A single-source-of-truth invariant; gatable by asserting only the one config component imports the
   Pulumi config API.

3. **frontier invariants of the REALM_BOUNDARY family (misplaced by cross-ref)** — the same "do not
   leak across a frontier" rule is stated in several places the initial survey missed:
   `doctor/runbook-doctor.adoc:592` ("[OSGi] side never imports a host or filesystem type" — already
   COVERED by REALM_BOUNDARY at the type level), `unit-repository/jgit-transposition.adoc:201`
   ("porcelain NEVER crosses the SPI" — a package-frontier cousin). Insight: **REALM_BOUNDARY
   generalizes a frontier-invariant family the docs had scattered.** When posing new frontier gates,
   consider them as parameterizations of the same two-realm reachability law rather than new engines.

REVIEW-ONLY (low gate value, leave as prose): builder-enforcement (private ctor), lazy-instantiation,
local-vs-inner classes, fluent-pipeline-grammar (the type-state already enforces structure),
port/edge/domain roles, uniformity.

## When

Do NOT inflate Plan 1 (the REALM_BOUNDARY gate — focused, mid-execution). Pose these after
REALM_BOUNDARY flips ERROR (end of Plan 2): same engine, same WARN→ERROR ritual, one menu. This memo
IS the menu. See [[world-gateway-document-design]] [[realm-boundary-gate]]
[[build-gates-over-review-reminders]].
