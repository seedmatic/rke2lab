---
name: config-restructuring-state
description: "Config refactor on branch refactor/config — Increment 1 (migration) DONE & verified; Increment 2 (doctor) not started"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ea4e055-08e0-405d-a509-7a32cda44c3e
---

Restructuring rke2lab configuration. Increment 1 merged to `main` (fast-forward, `wip/` then
removed from main per convention). The design spec + migration plan + Increment 2 continue as
WIP on branch `feature/runbook-doctor` (NOT on main):
- `docs/architecture/config/config-restructuring-spec.adoc` (on feature/runbook-doctor) — diagram-first design (C4 + UML)
- TDD/BDD migration plan (executed; removed at merge — the code is the record)
Durable docs/ relocation is deferred until the whole config feature (incl. Increment 2) lands.

**Increment 1 (full config migration) — DONE & VERIFIED (2026-06-06).** Implemented + committed:
MissingRequiredConfiguration, ConfigLoader (section-map reads over injectable SectionReader),
InfraDomain enum (each constant contributes its sealed InfraConfigFragment — values() = the
registration list), InfraConfigRegistry, Rke2labConfig DTO, ConfigEntryGate BDD (src/main nested
stages, played-at-runtime, asserts ready-vs-missing OUTCOME only). Migrated BootstrapConfig /
ControlplanePolicy / BootstrapOptions to derive from the DTO via EnvironmentStage (reads DTO once);
DELETED BootstrapConfig.Builder/Defaults/applyConfig + env/JGit/user.home detection,
ControlplanePolicy.EnvironmentValues, and the whole ConfigResolver. Pulumi.dev.yaml rewritten to
nested sections. **Verified:** 18 seed-master tests green + `pulumi preview` converges (1 to create,
23 unchanged, no config errors). Only com.pulumi.Config readers left: ConfigLoader + Rke2labConfig.
Commits e1fcc06e → 3765e56d.

KNOWN UNRELATED: `netplan` module's `ClusterNetworkBlueprintTest` fails pre-existing (network
addressing, expected<1>was<7>, last touched 5a17dcfc7) — NOT this topic, do not fix here. It blocks
a bare `-am test`; exclude with `-Dtest='!ClusterNetworkBlueprintTest'` to run seed-master tests.

**Increment 2 (NOT started) = the doctor pattern's first use case:** build minimal doctor core
(Generalist, specialist interface, Prescription, RemediationPlan); InfraDomain gains a per-constant
specialist(); the ConfigEntryGate missing-inputs outcome routes by domain to specialists for
operator prescriptions. Open question (spec Review): doctor-core ownership (config builds it vs
shared neutral module).

**Preview-only branch:** nothing deploys from refactor/config; `pulumi preview` is the only test.
Combined with [[hub:sequential-no-compat-workflow]]: no green-between-commits machinery, old code
deleted in the same change that supersedes it.

**Goal:** kill the fragmented config (3 independent Pulumi `Config` readers —
`BootstrapConfig.Builder`, `ControlplanePolicy`, `ConfigResolver` — with divergent
`EnvironmentValues` wrappers and mixed env/git/user.home fallbacks). Replace with a single
source of truth + nested YAML. Motivated by Nix `nix run/exec` where git worktree + `$HOME`
don't exist.

**Locked design decisions (all user-approved):**
- One reader: `Rke2labConfig.from(Config)`. Everything else consumes the immutable DTO.
- All inputs in Pulumi config — no `System.getenv`, no `user.home`. `incus.configDir`
  mandatory (it's the Incus client-cert dir for the Pulumi Incus provider). `worktree.dir`
  mandatory (git detection removed; GitHub-clone fallback documented as future).
- Nested YAML (`rke2lab:incus: {project, configDir}`) not flat dotted keys.
- DTO field contract: **mandatory fields = plain types**, throw dedicated
  `MissingRequiredConfiguration` at load time; **optional fields = `Optional<T>`**, defaults
  applied LATER in the derivation layer (`BootstrapConfig.from`/`ControlplanePolicy.from`),
  never in the DTO.
- Fluent `ConfigLoader` reads scalars key-by-key (sidesteps Jackson's inability to bind
  `Optional`; only nested maps `policy.readiness.override`/`simulate` use `getObject`).
- Fluent derivation reuses the `ApplicationPipeline` grammar (`.during(label).then()`).
- **Two aligned catalogs:** keep `ManifestDomainCatalog` (Stage B); add `InfraDomainCatalog`
  (Stage A: incus, image, network, worktree, systemd, host). NOT merged — different layers.
- **Full registrar-style contribution** (user chose the costly path for extensibility):
  each infra domain = an `InfraDomainRegistrar` owning {config fragment + doctor specialist},
  assembled via `InfraConfigRegistryBuilder` → `InfraConfigRegistry`, mirroring
  `ManifestsDomainRegistrar`/`Registry`. Heterogeneous fragments → `sealed InfraConfigFragment`
  + one centralized cast in typed accessors (`config.incus()`).
- `policy.link.*` flags ARE manifest-domain IDs → `isLinkEnabled` MUST key off
  `ManifestDomainCatalog` (the clusterApi-vs-cluster-api discipline), not a hardcoded switch.
- **Doctor integration:** missing mandatory key = a domain-tagged *symptom*. NO config doctor,
  NO `ConfigSpecialist` — the config layer only knows structural facts; the existing per-domain
  specialist (e.g. IncusExecSpecialist) owns the meaning (why/example). Single shared
  `Generalist` routes by domain. Loader ACCUMULATES all missing keys, consults ONCE. Reuses the
  runbook-doctor subsystem (see that branch's `docs/architecture/doctor/runbook-doctor.adoc`).

**Plan structure (MERGED, preview-only):** the spec's A/B/C collapse into ONE increment
(`config-migration-plan.adoc`, 9 tasks): ConfigLoader+DTO+catalog+sealed-fragment+registry+6
registrars → derive BootstrapConfig (delete Builder/Defaults/env-git) → derive ControlplanePolicy
(delete EnvironmentValues, catalog-keyed links) → delete ConfigResolver + converge EnvironmentStage
(reads DTO once) → nested Pulumi.dev.yaml + BDD helper. The doctor (spec's state D) is **Increment 2**,
a separate plan = the doctor pattern's FIRST use case.

Plan refinements vs spec: ConfigLoader uses **section-map reads** (`getObject(section)` → walk
dotted names like `policy.link`) over an injectable `SectionReader`, so the DTO is unit-testable
offline (in-memory maps) — NOT scalar key-by-key as the spec loosely said. DTO has a `defaults()`
offline path (no mandatory validation, for EnvironmentStage's null-context branch). `BootstrapOptions`
(readiness/cleanWorktree/bbox, ex-ConfigResolver) absorbed as cross-cutting DTO records.

**Deferred:** [[domain-registry-abstraction]] — unify the two registry pairs later.

**Next step when resuming:** start Increment 2 (doctor) on `feature/runbook-doctor` — see that
branch's `docs/architecture/doctor/runbook-doctor.adoc`. Build commands: `flox activate -- ./mvnw -pl :seed-master -am test
-DskipTests=false` (reactor; tests skipped by default — see [[hub:sequential-no-compat-workflow]] and
CLAUDE.md build conventions; Claude may run compile/test/preview, not live-system mutations).
