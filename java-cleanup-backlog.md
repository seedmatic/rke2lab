---
name: java-cleanup-backlog
description: "The running backlog of Java/build cleanup items for rke2lab — code-hygiene debt, deferred decisions, and follow-ups that don't have their own chantier note. MAINTAINED AS A LIVE VIEW: add an item the moment something is pushed to the backlog (a deferred fix, a 'later pass', a TODO raised while doing other work), and tick/strike it the same increment it's done. Distinct from the design chantiers (extract-bridge-api, R4, …) which have their own state notes — this is the catch-all for the smaller stuff. Started 2026-06-19."
metadata:
  node_type: memory
  type: project
---

## How to use this note

This is the **live backlog view** the user asked to keep. Discipline:

- When you push something to the backlog — a deferred fix, a "we'll do this later",
  a TODO raised mid-work, a decision postponed — **add a line here in the same
  increment**, don't trust memory.
- When you finish a backlog item, **mark it DONE (with the commit) in the same
  increment** — keep the view honest, never let it drift from reality.
- Big multi-session efforts get their OWN state note (e.g. [[extract-bridge-api-state]],
  [[osgi-runtime-r4-boot-seam-state]]); link them from here rather than duplicating.

Status keys: `[ ]` open · `[~]` in progress · `[x]` done (keep a few recent done
items for context, prune old ones).

## Open

- [ ] **Machine-enforce the existing non-null rule (decision owed).** rke2lab already
  HAS the rule "no input is ever nullable" ([[non-null-input-rule]]) — the code is
  non-null-by-default by DISCIPLINE, just not yet by annotation. So this isn't "adopt a
  policy from scratch", it's "lock in the rule we already follow". `.vscode` had
  `java.compile.nullAnalysis.mode: automatic`, but with no `@NonNullByDefault` declared
  the IDE couldn't see the rule and instead surfaced friction at THIRD-PARTY boundaries
  (`Optional`, `Framework`, JUnit callbacks) — unresolvable, not our debt. **Interim:**
  mode set to `disabled` (2026-06-19). **Decision owed:** declare `@NonNullByDefault`
  (jspecify or `org.eclipse.jdt.annotation`) at package scope so the rule becomes
  machine-checked, leaving only the third-party edges to annotate locally; then flip
  `.vscode` back to `automatic`. A slice of its own.
- [ ] **`-Werror` once warnings stay at zero.** `-Xlint:all` is on by default in
  build-parent (2026-06-19) and the reactor currently builds with ZERO javac warnings.
  Lock it in with `-Werror` so any new warning fails the build — deferred until we're
  confident it stays at zero (don't want a surprise red on the next unrelated change).
- [ ] **R4 boot seam + the 3 wrong-direction inversions.** Not cleanup — a real
  chantier; tracked in [[osgi-runtime-r4-boot-seam-state]]. The inversions
  (`ManifestYaml`, `NodeEnvContributorRegistry`, `FloxRuntimeAssets` — host currently
  reaches into these impl types) ride with R4, per [[api-extraction-tri-carto-state]].
  Listed here only as a pointer so the backlog is complete.
- [ ] **Delete the `realgraph` standalone-resolver fixture AT R4.** `exec/seed-master` test package
  `io.seedmatic.rke2lab.unitrepo.realgraph` (7 files, all now `@Deprecated(forRemoval = true)`) hand-builds
  a fake `UnitResource` universe to feed `UnitResolver` — a duplicated source of truth that already
  drifted at the `-core`/`-port` split (`ReactorModuleCatalog` transcribes reactor module ids by hand;
  `systemd-contract`/`manifests`/`netplan` ids left stale, NOT re-synced). Superseded once Felix boots
  for real (R4) → delete the whole package then, don't repair. KEEP `UnitResolver` (wraps Felix
  `ResolverImpl`, stays in production). Tombstoned in the osgi-cleanup slice; removal tracked in
  [[osgi-runtime-r4-boot-seam-state]] + [[rename-contract-to-port-state]].
- [ ] **jdtls per-worktree slug + memory-symlink generator fix.** Non-main worktrees get
  main's memory slug in the system prompt → announced `projects/<slug>/memory` path is
  absent, and (the sibling defect) a window reload can lose the conversation list.
  Fix lives in the `.code-workspace` generator (main workspace). Detail in
  [[claude-memory-cascade-state]] and [[jdtls-heap-workspace-generation]].
- [ ] **Align the stale bench Bundle-SymbolicNames (osgibench → bench/testkit).** The
  `osgi-bench`→`bench` + `osgi-testkit`→`testkit` rename (e58a44e3) moved the artifacts/dirs
  but left the BSN **and** the Java packages at `io.seedmatic.rke2lab.osgibench.*` — so e.g.
  module `bench-config` has BSN `…osgibench.config`, `bench-scr-api` has `…osgibench.scr.api`.
  The BSN no longer reflects the module name. Disposable scaffolding → low stakes, but it is
  the naming-drift the user spotted 2026-06-19. A slice of its own (touches the BSN, the
  Export-Package, and the `osgibench` Java package across bench-* and the testkit). NOT in the bridge→contract
  slice's scope (that one is manifests/netplan only). Sibling check: confirm no other module's
  BSN drifted from its dir (audit all `bnd.bnd` at the time).
- [~] **Non-deductible Bundle-SymbolicNames hors-bench — IN SCOPE of osgi-cleanup (user widened,
  2026-06-19).** The user's rule: a BSN must be MECHANICALLY DEDUCIBLE from the Maven artifactId
  (`io.seedmatic.rke2lab.<artifactId, dashes→dots>` verbatim). The netplan promotion FORCES it
  (`netplan`→`netplan-core` would turn a today-deducible BSN non-deducible), and uniformity pulls
  the rest of the hors-bench lot in. Fixing in THIS slice (safe: grep confirms NO
  `Require-Bundle`/`Fragment-Host` anywhere → no bundle references another by BSN):
  - `manifests-core`: BSN `…manifests` → `…manifests.core`
  - `netplan-core` (ex-`netplan`): BSN `…netplan` → `…netplan.core`
  - `unitrepo-handler-api`: BSN `…unitrepo.handler` → `…unitrepo.handler.api` — **SUPERSEDED:
    module renamed `unitrepo-handler-api`→`unitrepo-handler-spi`, package
    `…unitrepo.handler`→`…unitrepo.handler.spi`, BSN+Export-Package = `…unitrepo.handler.spi`
    (its nature is an extender SPI, [[rename-contract-to-port-state]]).**
  - `systemd-contract`: BSN `…systemdcontract` → `…systemd.contract` — **SUPERSEDED by the
    contract→port rename ([[rename-contract-to-port-state]]): the module is now `systemd-port`,
    its package was renamed `systemdcontract.api`→`systemd.port` (killing the collapsed token AND
    the parasitic `.api` on a port), so BSN = `…systemd.port` = Export-Package, both deducible.**
  The **BSN changes; the Export-Package does NOT** (BSN = bundle identity, Export-Package = Java
  package — independent). Already-deducible
  (leave): `cdk8s-systemd`→`…cdk8s.systemd`, `unitrepo-core`→`…unitrepo.core`. New contracts are
  deducible by construction. The **bench** family (`osgibench.*`) stays the SEPARATE backlog item
  above (disposable + entangled with a Java-package rename).
- [ ] **★ REVIEW GATE for the bridge→contract merge (mine, at squash).** The rename slice
  ([[rename-bridge-to-contract-state]]) MOVES `…manifests.bridge`→`.contract`, so it MUST make
  the impl bundles' manifests consistent or the build goes red. At MY squash-merge review,
  verify: (a) `manifests-core` `bnd.bnd` `Export-Package` no longer claims packages whose ports
  left (it should export only what it still CONTAINS — impl/util packages — and must NOT export
  the moved `.contract` ports, which now live in the host module); (b) same for `netplan`;
  (c) the host `manifests-contract`/`netplan-contract` modules export their `.contract` package
  cleanly (plain jar today; R4 will `system.packages.extra` it). This is the manifests/netplan
  half of the BSN-drift the user flagged — it falls inside the in-flight slice, so it is a
  REVIEW check, not a separate task. (The bench half is the open item above.)

## Done (recent)

- [x] **Machine-enforce no-SNAPSHOT-install + purge ~/.m2 + fix a relativePath bomb** (2026-06-19).
  Added the `no-snapshot-install` enforcer execution (`requireReleaseVersion` on the `install` phase) in
  build-parent — `mvn install` of a SNAPSHOT now fails BEFORE writing to ~/.m2 (intra-phase order proven).
  Then purged the 31 stale rke2lab SNAPSHOTs from ~/.m2 (many were ghost names from the renames:
  `osgi-bench-*`, `systemd-contract`, `unitrepo-handler-api`, `manifests`, `netplan`, `parent`). The
  purge IMMEDIATELY surfaced a latent bug it had been masking: `osgi/testkit/pom.xml` had
  `<relativePath>../build-parent/pom.xml` (one `../` short → resolves to the non-existent
  `osgi/build-parent`); Maven had been silently falling back to a FROZEN build-parent in ~/.m2, so the
  build "passed" on a wrong pom. Fixed to `../../build-parent/pom.xml`; audited ALL modules' relativePaths
  (testkit was the only break). This is exactly the masked-incoherence the no-install rule exists to
  prevent — the discipline found it the first time it was applied. See [[osgi-baseline-install-discipline]].
- [x] **this-escape ×4 eliminated** (commit 1bdb9a40, 2026-06-19). Not suppressed:
  SystemdChart now discovers units/drop-ins by walking the construct tree instead of
  constructor self-registration; `UpstreamYamlInclusion` made `final` with private
  static hooks (its overridable surface was never used — speculative).
- [x] **Dead code removed** (commit 467753f7, 2026-06-19). IncusResourceBootstrap dead
  Incus lookups + legacy checksum chain; `floxRuntimeAssets` and `StackHistoryFixture.project`
  dead fields. 176 lines.
- [x] **`-Xlint:all` on by default + 25 warnings cleared** (commit b08643bb, 2026-06-19).
  serial / unchecked (BDD tests) / try. 29 → 0 javac warnings.

See [[extract-bridge-api-state]] (the slice in flight), [[osgi-runtime-r4-boot-seam-state]]
(R4), [[build-verification-gotchas]].
