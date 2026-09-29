---
name: manifest-registrars-enum-refactor
description: "NEXT-TOPIC candidate: refactor the 11 manifest domain registrars to an enum (same pattern as config's InfraDomain) — do AFTER refactor/config lands"
metadata:
  type: project
---

CANDIDATE next topic (NOT for the refactor/config branch — see [[hub:sequential-no-compat-workflow]]):
apply the enum-implements-interface pattern to the manifest domain registrars, the same way
[[config-restructuring-state]]'s `InfraDomain` enum does for Stage-A infra domains.

**Why it fits:** the 11 manifest registrars (`ClusterDomainRegistrar` … `PlatformDomainRegistrar`
in `manifests/.../domain/`) are a CLOSED, explicitly-listed set — hand-chained as 11
`new XxxDomainRegistrar()` calls in `DefaultManifestSynthesisService:612-621`. They are NOT
ServiceLoader-discovered (the ServiceLoader usage in that module is for `ManifestSynthesisService`
+ `NodeEnvContributor`, a different SPI). Closed set → enum applies. Wins: self-enumerating
`values()` replaces the 11-line register chain, impossible to forget a domain, one file, uniqueness
free.

**Why DEFER (not in config branch):**
- Different module (`manifests/`) and different topic than the config migration — violates
  one-topic-at-a-time.
- Heavier transform than config's 6 one-liner registrars: manifest `domain(ManifestDomainPolicy)`
  bodies build CONDITIONAL unit lists (`if policy.isEnabled(...) units.add(...)`) — bigger
  constant-specific bodies across 11 domains.
- Touches `DefaultManifestSynthesisService` (600+ lines, core) and interacts with the KNOWN-DORMANT
  `buildDomainRegistry` (throws UnsupportedOperationException mid layers→components migration, per
  [[bdd-jgiven-test-strategy]] Point A). Risk of colliding with that in-progress work.

**When:** after `refactor/config` merges. Own branch (e.g. `refactor/manifest-domain-enum`).
Carry each registrar's conditional logic into its enum constant's `domain(policy)` override verbatim
(don't invent). Keep keying off `ManifestDomainCatalog` ids.
