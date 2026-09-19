---
name: realm-duplication-gate-brainstorm-state
description: "2026-06-29 — the SEAM_PURITY idea became the DUPLICATE_REALM_CLASS static staging gate (SHIPPED green, cdk8s found + governed WARN). Brainstorm DECIDED the successor: an in-container diagnostic that drives the REAL BootPlanner (via BootPipeline.embedded) and observes the wiring, replacing this static gate for duplication while REALM_BOUNDARY stays. Design agreed, NOT yet built — implement next."
metadata: 
  node_type: memory
  type: project
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-09-19T07:09:33.942Z
---

## STATUS 2026-07-28 (verified against the tree)

The static gate is COMMITTED and live — `maven-embed-staging-ext/staging-extension/.../DuplicateRealmClass.java`
+ `DuplicateRealmClassTest`, wired into `StagingExecutionStrategy`, `DUPLICATE_REALM_CLASS` in both
`StagingGate` enums. So the "## The prototype (PARKED, not committed)" section at the bottom is
HISTORICAL — it no longer describes reality (nothing is stashed at `7389e973` / `/tmp`). The
`type=library → type=dual-realm` capability rename also shipped — **confirmed by substance, not by
that SHA**: `ebc8e19e` resolves in no repo of the fleet (rebased away, or a typo), while `dual-realm`
is present in 89 files of `feature/nixos-node-substrate` and no code carries `type=library` any more
(checked 2026-09-19). Trust the fact, not the citation.

STILL OPEN — the **governance chantier**, now DISENTANGLED into two DISTINCT static laws (the boot
experiment settled it — see `.claude/governance-realm-gate-handoff.md`):
(1) **duplication** stays the STATIC `DUPLICATE_REALM_CLASS` — its `seamSurface` filter IS the
precision. The booted-framework "faithful successor" idea (observe `BundleWiring` to detect duplication)
was tried and DROPPED: the boot only proves a resolution/disjointness invariant (`system ∩ installed ==
{}`), it does NOT buy duplication precision (`flat ∩ export` over-flags every staged lib). That boot is
salvaged as a SEPARATE smoke-test law, `REALM_WIRING_INTEGRITY` (Law A, built green).
(2) the real chantier — the **static host-import-count rule**: flag a `type=dual-realm` `-contract-host`
module that ZERO host files reference, so it folds OSGi-only (worktree was folded by hand this round;
`incus-ingress-contract` must NOT flag — the pulumi edge imports it). The original "false-flagged
genuine both-realm bundles" came from the wrong signal (`flat ∩ export`); the host-import count is the
right one, and needs no boot (it must see unexercised imports a runtime cannot).

## OUTCOME (2026-06-29) — static gate SHIPPED, in-container successor DESIGNED

The brainstorm converged. Two results:

**A. The static `DUPLICATE_REALM_CLASS` gate SHIPPED (green).** The user said: commit what we have —
it's not wrong, it proves the cdk8s duplication, and it's a real build-time net while we build the
faithful version; the history showing static→in-container is GOOD work, not churn. So:
- `DuplicateRealmClass` (intersection flat ∩ staged-bundle-export, with an `org.slf4j` exemption — the
  pax R1 shared provider), wired into `StagingExecutionStrategy.enforceGates`, attributed to the EXEC
  (a duplication is a property of THIS assembly, like REALM_BOUNDARY's flat leaks), `DUPLICATE_REALM_CLASS`
  added to both StagingGate enums, `DuplicateRealmClassTest` (3 cases). 27 extension tests green.
- It found cdk8s (org.cdk8s + software.constructs) duplicated in seed-master AND manifests-cli (both
  stage the manifests-cdk8s carrier flat AND as a bundle). Governed WARN via @GovernedBy on each exec's
  package-info (seed-master stacks it with REALM_BOUNDARY via @Repeatable). netplan-cli has no cdk8s →
  no pose. Full reactor all-worlds BUILD SUCCESS: realm-boundary 0 error/41 warn, duplicate-realm-class
  0 error/4 warn.

**B. The in-container successor DESIGN (agreed, NOT built).** The static rule only APPROXIMATES the
boot's dynamic derivation (deriveSystemExports: mirror-then-remove). The faithful replacement, agreed
with the user:
- For each embed exec, an **in-container test** that boots the REAL install-set via
  `BootPipeline.embedded().launch()` (the true BootPlanner, deployed topology, NO hand-kept systemPackages
  list — the user's "option 2": it's an integration test reading what the assembly really stages).
  `EmbeddedBundlesBootTest` (seed-master + netplan-cli) ALREADY does this and its typed-service casts
  ALREADY catch splits as a side effect (ClassCastException) — the design makes it explicit + exhaustive.
- **Verification level = wiring (structural), not just behavioural casts**: after resolve, introspect the
  BundleWiring PACKAGE_NAMESPACE capabilities and assert no package is provided by BOTH the system bundle
  (flat) AND an installed bundle. Observed, not inferred. (The testkit already reads these capabilities,
  OutOfContainerFrameworkExtension lines 542-548.)
- **Gaps to close** (the user picked all): (1) explicit duplication diagnostic (not a fortuitous
  ClassCastException); (2) full seam coverage (resolve+cast ALL -port seams the exec embeds, not just
  manifests/cluster); (3) generalize into a reusable testkit diagnostic played by every exec (today the
  pattern is re-written per exec — seed-master + netplan-cli each have their own EmbeddedBundlesBootTest);
  runs in `test` phase, BEFORE the uber-jar shade, so failure blocks before a broken artifact exists.
- **REALM_BOUNDARY stays** (re-discussed on the design): it is a DIFFERENT law — a flat class that
  REFERENCES a bundle-only package (reference leak), caught exhaustively by static bytecode scan AND it
  IS the world-gateway migration worklist (41 warn, shrinking). A booted test can't catch an unexercised
  reference leak. So: keep REALM_BOUNDARY static; the static DUPLICATE gate becomes redundant ONCE the
  in-container diagnostic lands → remove it THEN (not yet).
- **cdk8s is real debt to FIX** (user: "je vais vouloir corriger aussi, mais avant il nous faut les bons
  outils de diagnostic") — correcting the carrier topology is its own later increment, once the faithful
  in-container tool exists. Governed WARN until then.

## (historical) how we got here — see below

## How we got here

After Option B ([[document-seam-cannot-expose-jackson-jsonnode]]) made gateway-port jackson-free, the
plan was a `SEAM_PURITY` staging gate to freeze the invariant. The design evolved twice via dialogue:

1. **Seam-centric → realm-wide (reversed).** Instead of "a type=seam may import only JDK/OSGi/seams"
   (which would have false-flagged slf4j/inet.ipaddr/doctor.records on the green build, AND missed
   jackson — jackson is host-flat in live, see below), the user chose to REVERSE it: walk the realm
   and detect any package present in BOTH realms — flat (host uber-jar) AND exported by a staged
   bundle. That is the exact LinkageError condition (one class, two classloaders), and exhaustive
   (a seam import is only one path to duplication). Gate named `DUPLICATE_REALM_CLASS`.

2. **Static-manifest → maybe boot-the-framework (OPEN QUESTION).** The user then asked: shouldn't the
   gates BOOT a real Felix and play diagnostics on the LOADED system, rather than statically analyse
   manifests? Better role separation (well-formed-bundle checks vs assembled-system checks) and more
   faithful to the real live system. This is now the architecture decision to brainstorm.

## Key VERIFIED facts (not assumptions)

- **jackson in LIVE is flat, never a bundle.** seed-master-exec.jar: 1269 jackson classes flat at the
  root, ZERO under META-INF/bundles/. doctor-core (a staged bundle) imports jackson →
  `BootPlanner.deriveSystemExports` mirrors it into system.packages.extra → the bundle delegates to
  the ONE flat copy. No collision in live. The LinkageError existed ONLY in the test harness, which
  installed jackson-databind as bundle [28] (a second realm).
- **cdk8s IS a real duplication (the prototype found it).** `org.cdk8s` (76 classes) + `software.constructs`
  live flat in the uber-jar AND are exported by 3 staged bundles (manifests-core, manifests-cdk8s,
  systemd-cdk8s-manifests). The flat host references org.cdk8s (IncusResourceBootstrap, HostSlotManifest).
  `deriveSystemExports` line 154 REMOVES a package an installed bundle exports from system.packages.extra
  → so cdk8s is genuinely two copies, unlike jackson. Pre-existing debt (build was green before).
- **The static rule is fragile.** Safety depends on the boot's DYNAMIC derivation (mirror-then-remove),
  which a static `flat ∩ staged-export` test only approximates. This is the core doubt — exactly what
  the user's boot-the-framework idea would resolve (observe the real resolution, not infer it).

## The prototype (historical — SUPERSEDED, now committed; see STATUS 2026-07-28 above)

- Built: `DuplicateRealmClass.java` (the gate logic, intersection flat ∩ staged-export, with an
  ALLOWED_SHARED_ROOTS exemption for org.slf4j — the R1 pax scar), wired into
  `StagingExecutionStrategy.enforceGates`, plus `DUPLICATE_REALM_CLASS` added to BOTH StagingGate enums
  (annotation-module + extension mirror). It RAN and correctly flagged cdk8s at ERROR.
- PARKED so as not to ship a rule we doubt: the 2 tracked extension files are in a `git stash`
  ("WIP on feature/cluster-edge: 7389e973"); the untracked `DuplicateRealmClass.java` is at
  `/tmp/DuplicateRealmClass.java.proto`; the annotation-module StagingGate enum was reverted; the
  extension was REINSTALLED gate-free to ~/.m2 so the build is un-broken. Working tree = clean on 7389e973.

## Resume

Brainstorm the realm-gate architecture (superpowers:brainstorming): static-manifest analysis (fast,
build-time, infers) vs boot-the-framework diagnostic (faithful, observes the real LinkageError, reuses
OutOfContainerFrameworkExtension + the S1 slf4j diagnostic) vs hybrid (belt at build + braces at boot).
The user leans toward the boot diagnostic for fidelity + role separation. Decide, THEN implement. The
cdk8s duplication is real debt to govern WARN whichever mechanism wins. See [[world-gateway-2a-execution-state]].
