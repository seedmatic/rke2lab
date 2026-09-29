---
name: test-tag-taxonomy-by-zone
description: "seed-master tests are tagged on 4 orthogonal JUnit5 dimensions composed by boolean tag expressions: host / osgi (the integration-atlas two SPACES) + live (drives a real Pulumi inline up()) + spike (throwaway proof). Default run excludes 'live | spike'. The zone tags (host/osgi) are also a FACTORING axis: each zone will grow its own common test logic + its own JUnit5 extensions. Introduced 2026-06-17 on refactor/bootstrap-config-relocate."
metadata:
  node_type: memory
  type: project
---

**The taxonomy (4 orthogonal tags, JUnit5 1.10.5 boolean tag expressions — `&` `|` `!`):**
- `host` — exercises HOST space (the Pulumi/gRPC engine side). The atlas's host space, made a test
  dimension. (Renamed from an initial `pulumi` — `host` matches the zone and survives an engine change.)
- `osgi` — exercises OSGi space (the resolver / pure model). The atlas's OSGi space.
- `live` — drives a real Pulumi inline `up()` (seconds each, spawns gRPC channels GC'd without shutdown).
- `spike` — throwaway proof, not a regression test (e.g. `ConfigExtenderResolutionSpikeTest`).

**Default run** (root pom property `surefire.excludedGroups`): `live | spike` excluded. The slow
deployments and the throwaway spikes stay out of the default loop; the fast host-readers and all the
osgi-resolver regressions stay IN. Profiles in the root pom: `-Pall-worlds` (nothing excluded), `-Plive`
(adds live back), `-Pspike` / `-Phost` / `-Posgi` (run ONLY that group). Ad hoc:
`-Dgroups='host & live'`, `-DexcludedGroups=…`.

**Current assignments:**
- `host`+`live` (3 deploying classes, the heavies 3–9s, each extracted clean of fast tests):
  `PulumiInterventionLedgerWriterLiveTest`, `InterventionLedgerRoundTripLiveTest` (extracted from
  `InterventionLedgerSourceTest`), `DriftReviewReconstructionLiveTest` (extracted from
  `DriftReviewWiringTest`). Each registers `GrpcChannelNoiseCapture`.

**Naming convention (settled 2026-06-17):** UNIFORM — the kind goes BEFORE the `Test` suffix:
`*LiveTest` (deploying) and `*SpikeTest` (throwaway proof). The kind word (`Live`/`Spike`) makes them
greppable/findable (the human signal); `Test` keeps them unambiguous and surefire-pickable, so the
`@Tag` does ALL the default-exclusion. We rejected bare `*Live`/`*Spike` suffixes: (1) `Live` is already
a PRODUCTION semantic prefix (`LiveMedicalRecordRegistry` = the live registry vs a fake), so `*Live`
would collide; (2) a non-`Test` suffix is excluded by NAME (surefire's include patterns skip it),
splitting the exclusion mechanism in two — tag for some, name for others. Keeping everything `*Test` +
tag-driven is one rule. So no `<includes>` hack in any profile; the `@Tag` filter alone selects.
- `osgi` (7 resolver test classes, fast regressions, NOT excluded): `UnitResolverTest`,
  `ManifestsUniverseTest` (×2: manifests + seed-master), `ManifestsVisitOrderTest`,
  `RealGraphResolutionTest`, `UniverseBuilderTest`, `ReactorModuleCatalogTest`.
- `spike`: `ConfigExtenderResolutionSpikeTest`.

**★ Zone = a FACTORING axis, not just a filter (user, 2026-06-17).** Tagging by zone will let us pull
out each zone's COMMON test logic and its OWN extensions. `GrpcChannelNoiseCapture` (swallows the benign
host-engine gRPC noise) is already the first HOST-space extension; the OSGi space will grow its own
(resolver fixtures, capability/requirement builders, etc.). When a second host-space need appears, factor
a shared base/extension for `@Tag("host")` tests; same for `@Tag("osgi")`. The tags make these families
explicit. See [[step2-decomposition-state]] (the two-spaces frame), [[seedlog-logback-migration-backlog]]
(host-space logging, why the gRPC noise is JUL not logback), [[bdd-jgiven-test-strategy]].
