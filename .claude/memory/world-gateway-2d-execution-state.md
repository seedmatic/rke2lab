---
name: world-gateway-2d-execution-state
description: World-gateway 2D IN EXECUTION (feature/cluster-edge, subagent-driven). Zone-0 DONE (networknt pinned, gateway-document-codec built, SCHEMA_CONCORD gate wired). BUT a code-structure carto INVALIDATED the ASM concord approach — PIVOT (user-chosen): records-as-contract per Document, JSON schema GENERATED from the record (not hand-written, not ASM-reconciled). NEXT = write the records-as-contract DESIGN before resuming execution. Plan: wip/plans/2026-06-30-world-gateway-2d-schema-contract.md (zone-0 part now superseded).
metadata:
  type: project
---

## Where we are (2026-06-30, mid-2D, context compaction)

Executing the 2D plan subagent-driven. Commits on feature/cluster-edge after the jsr310 fix arc:
- `7e704838`+`33fc1944` Task 1 — networknt pinned in BOM + added to **maven-embed-staging-ext** (build-tooling only; NOT doctor-core — nothing imports it at runtime in 2D, validate is OFF until the capstone).
- `3183a79e`+`f71205b2`+`e690a72a` Task 2 — `gateway-document-codec`: ONE flat-jar module in osgi/foundation, shaded flat host-side + nested into doctor-core's Bundle-ClassPath (`-includeresource;lib:=true`). New pattern [[nesting-our-own-flat-module-per-realm]]. Package `io.seedmatic.rke2lab.world.gateway.codec`, sibling of the seam (NOT in it — seam shares one String copy; codec carries jackson, two realm copies).
- `7448daae`+`8d483652` Task 3 — `SCHEMA_CONCORD` gate: added to BOTH StagingGate enums (annotation + ASM mirror), `SchemaConcord` (meta-schema validity via networknt + concord) + `CoordinateFieldUsage` (ASM). Slug map DERIVED from `Coordinate.class` `<clinit>` bytecode (not hardcoded). Gate dormant (no schemas) → schema-concord 0/0, reactor green.
- `308da3ad` memory: the FIELD_* elimination backlog.

## THE PIVOT (user decision — do NOT revert to ASM concord)

A 6-coordinate structure carto (Explore agent, this session) proved the ASM part-(b) concord engine
is a DEAD END:
1. **4 translators** read-coord-A-write-coord-B in one method (DefaultReadinessAuthority,
   DefaultInterventionIntake, Generalist, +MedicalRecordReader) → class-granularity FIELD_* attribution
   over-assigns (e.g. readiness-verdict would get scenarioId+override which belong to the checkpoint).
2. **`intervention` uses NO FIELD_***  — its contract is already `Intervention.toOutputMap()` (the
   record IS the model). ASM finds nothing to attribute.
3. Documents **NEST** (observations[], consultationReport{}, expectations[], FIELD_CONSULTATION_REPORT[])
   — a flat FIELD_* set cannot describe them.

User's call (both questions answered): **monter d'un niveau — record-per-Document is the contract, the
JSON schema is GENERATED from the record** (intervention already does this; generalize to all 6). AND
**automate it generically** — scan the records, generate schemas, no hand-written schema, no FIELD_*.
This FUSES the [[eliminate-field-constants-via-schema-binding-backlog]] INTO 2D.

Consequence: Task 3's `CoordinateFieldUsage` (read/write ASM concord) becomes OBSOLETE. The gate's
MEANING changes: not FIELD_*↔properties, but "every Document of coordinate X is built/read via X's
record, never via parallel put(String,…)". Tasks 4-9 become "define 6 records + migrate
producers/consumers onto them", not "write 6 schemas".

## THE CARTO (authoritative — do NOT re-scan; producer WRITES are the true shape)

- **readiness-checkpoint** (host produces: SystemdAdapterStage, ClusterReadinessStage) — writes
  scenarioId, recordedAt, observations[] (each obs: status, summary, symptom, …details). Consumed by
  Generalist.consult/observationsFrom, DefaultReadinessAuthority.assess (reads scenarioId/failed/override).
- **readiness-verdict** (DefaultReadinessAuthority.assess; DefaultInterventionIntake.error) — writes
  action, reason. Host reads action. FLAT.
- **consultation** (Generalist.consult) — writes scenarioId, narration, diagnosisAdoc, + nested
  consultationReport{checkpointId,observations[],plan{}} + expectations[]. Host reads narration.
- **intervention-request** (host RecordInterventionCommand) — writes problem, what, provenance?,
  prescriptionRef?, when. Consumed by DefaultInterventionIntake.canonicalize. FLAT.
- **intervention** (InterventionDocuments.of → Intervention.toOutputMap) — provenance, when, what,
  problem, prescriptionRef? (+open `details`). NO FIELD_* — the record is the contract already.
- **visit** (host StackMedicalRecordJournal.visitDocument) — writes version, when,
  consultationReport[] (blobs), expectations[] (list-of-lists). Consumed by MedicalRecordReader.read.

## DESIGN WRITTEN + USER GO (2026-06-30) — now EXECUTING

Design doc: `wip/plans/2026-06-30-world-gateway-2d-records-as-contract-design.md`.
User said GO. The 5 decisions are SETTLED there; the 3 open points are resolved (user accepted the leanings):
- (a) wire-records live in the **`world-gateway` seam** (jackson-free, system-exported, one shared
  class — same status as Document/Coordinate; the TYPE never crosses, only the String, via the
  per-realm DocumentCodec). Confirmed against 2C: stricter than today, does not reopen the JsonNode leak.
- (b) schema GENERATED build-time from `RecordComponents`, generator in `maven-embed-staging-ext`.
- (c) nesting via nested wire-records (Consultation→ConsultationReportWire+List<ExpectationWire>).
- (d) gate invariant REWRITTEN: "every Document of coordinate X built/read via X's wire-record, never
  parallel put(String,…)". Old `CoordinateFieldUsage` ASM read/write engine = DELETED (obsolete).
- (e) isolation respected + strengthened.
- Open-1 PACKAGE: flat in `world.gateway.port` (not a `.wire` sub-pkg).
- Open-2 WIRE vs RICH: option (ii) — wire-record is schema-source AND serialization unit; rich
  `doctor-records` types stay the domain model and gain `toWire()` where they're the producer.
- Open-3 OPAQUE blobs (visit, host intervention pass-through): `Map<String,Object>`/`List<Object>`,
  generator emits `{}`.

### Re-planned tasks (replace old 4-9), gate stays WARN until T10 flip:
- T4 scaffolding: wire-record base + opaque pass-through type, schema generator in extension, gate
  new invariant WARN, 0 schemas yet (0/0 green). Subsumes old Task 3 rewrite.
- T5 readiness-verdict (flat, OSGi-only) — proves the loop end-to-end.
- T6 intervention-request (flat, HOST producer) — first host migration via shared seam record.
- T7 intervention (OSGi producer already record-based; host pass-through opaque).
- T8 readiness-checkpoint (host producers, nested observations[]).
- T9 consultation + visit (nesting + opaque blobs).
- T10 delete WorldGatewayCatalog FIELD_* block; FLIP SCHEMA_CONCORD WARN→ERROR; full reactor green = lock.

EXECUTION POSTURE: T4 done inline (design-sensitive, full context). T5-T9 subagent-driven (repetitive
per-coordinate migration). Build: `flox activate -- ./mvnw package -Pall-worlds
-Dmaven.build.cache.skipCache=true -DskipTests=false`; extension = two-phase dance.

## T4 + T5 DONE (2026-07-01)

- **T4** (commit `63622488`): `@DocumentContract(Coordinate)` seam annotation, `RecordSchemaProjector`
  (RecordComponents→JSON schema by ASM), `DocumentContractScan` (replaced CoordinateFieldUsage),
  `SchemaConcord` rewritten (every Coordinate must have a wire-record whose projected schema is
  meta-schema-valid; missing = WARN worklist). Gate WARN, 6-coordinate worklist, reactor green.
- **T5** (readiness-verdict, first coordinate): `ReadinessVerdict(Action, reason)` wire-record —
  holds the Action enum TYPED (the FIELD_* typing goal). `WireEnum` seam marker (Action implements
  it) + `WireEnumModule` (jackson glue, codec `.internal`, non-exported) maps any WireEnum↔slug
  generically. `DocumentCodec` gained typed `encode(record)`/`decode(String,Class)`. All 6
  producers/consumers migrated (DefaultReadinessAuthority, DefaultInterventionIntake.error host
  SystemdAdapterStage + RecordInterventionCommand + 5 tests); `FIELD_ACTION`/`FIELD_REASON` DELETED.
  SCHEMA_CONCORD now 5 warn. NOT yet committed as of this note.

### THE BIG T5 DESIGN SHIFT — codec is now a `type=library` bundle (user-driven)
The codec was a flat-jar nested-private in doctor-core. The user pushed to resolve the
"doctor-core owns a foundation concern" tension NOW: promoted it to an **autonomous dual-realm
bundle** via a NEW staging category `embed; type=library` (jackson's own treatment, for our code):
- `EmbedCapability.TYPE_LIBRARY` + `isLibrary()`; `INSTALL_FILTER` includes it (documentary).
- `StagingClosure.isRealmLibrary` returns `b.embed().isLibrary()` for ours → staged bundle AND kept
  flat (in realmLibraryGas, so NOT shade-excluded). The ONLY embed type in both realms.
- codec bnd: BSN `io.seedmatic.rke2lab.gateway.document.codec`, Export `world.gateway.codec`, Import
  jackson+seam, `Provide-Capability embed; type=library`. doctor-core IMPORTS it (no more nesting).
- Verified DUAL in the uber-jar: `META-INF/bundles/gateway-document-codec.jar` AND flat
  `world/gateway/codec/DocumentCodec.class`. Pattern memory rewritten: type=library is now PREFERRED
  over nesting ([[nesting-our-own-flat-module-per-realm]] [[codec-foundation-single-exporter-when-needed-backlog]]).
- Naming settled: KEEP `gateway-document-codec` (role, not jackson mechanism); it is NOT a jackson
  fragment (depends on seam, dual-realm, owns its export) — only `WireEnumModule` is a jackson extension.
- Dep-scope/version centralization DEFERRED by user ([[centralize-seam-dep-scope-version-backlog]]).

## T6 DONE (2026-07-01) — intervention-request + codec modules matured

- `InterventionRequest(problem, what, Optional<String> provenance, Optional<String> prescriptionRef,
  Instant when)` wire-record, `@DocumentContract(INTERVENTION_REQUEST)`. Compact ctor normalizes a
  null Optional → empty (the codebase's Optional idiom, guards direct construction). Producer host
  `RecordInterventionCommand` (its `Args` record also refactored to Optional per user), consumer OSGi
  `DefaultInterventionIntake.canonicalize` (decodes to the record, keeps the graceful error-verdict
  flow), + 2 tests migrated. `FIELD_PROBLEM/WHAT/PROVENANCE/PRESCRIPTION_REF` deleted; `FIELD_WHEN`
  KEPT (visit/T9 still uses it). SCHEMA_CONCORD now 4 warn.
- Codec gained `InstantModule` (Instant↔ISO-8601, home-made, 2 lines) + jackson's own **`Jdk8Module`**
  for Optional. KEY LESSON (user-driven): a home-made OptionalModule was a dead end (fought jackson's
  ReferenceType/REQUIRE_HANDLERS internals) — jackson's Module system is the right tool; registered
  EXPLICITLY (`registerModule`), never `findAndRegisterModules`, so no ServiceLoader regression.
- jdk8 is NOT nested (unlike jsr310 in manifests-cdk8s): nesting is needed ONLY when a ServiceLoader
  discovers the module off a classloader (jsii's findAndRegisterModules drives jsr310). We register
  explicitly, so jdk8 is imported bundle-to-bundle like databind. Declared ONCE in the codec pom (the
  owner), NOT duplicated in seed-master.
- STAGING FIX (extension, two-phase): `type=library` now feeds `indexHostFlatPackages` +
  `seedRealmLibraries` (was `isDomain()` only). Without it, a library's imported jackson jars (jdk8)
  were staged but NOT kept flat → host copy missing. Now jdk8 is dual (staged bundle + 23 flat
  classes), exactly like databind. General fix for any future dual-lib.
- New backlogs graved: [[sweep-objectmapper-onto-codec-backlog]] (remove residual `new ObjectMapper()`
  after T9), [[jspecify-nullmarked-default-backlog]] (@NullMarked package default, post-2D).
- NOT yet committed as of this note.

## T7 IN PROGRESS (2026-07-01) — intervention, NOT built, NOT committed

BIG MODEL FIX (user-confirmed "c'est un bug"): the `intervention` coordinate had TWO wire shapes
under one slug — a bug (1 coordinate MUST = 1 schema). Root cause: the host read path wrapped
`{interventions:[blob]}`, but that `[...]` was Pulumi's `outputsNamed`→List transport framing LEAKING
into the payload (InterventionResource registers ONE intervention via `Output.of(data)`; outputsNamed
collects it as a 1-element list). The write path already wrote ONE flat intervention. TRUE contract =
ONE intervention. Fix = UNWRAP the Pulumi framing at the host, one Document per blob.

Changes DONE (files edited, not yet built):
- NEW `InterventionWire(provenance, when:Instant, what, problem, Optional<String> prescriptionRef,
  Map<String,Object> details)` in world-gateway seam, `@DocumentContract(INTERVENTION)`. Refs stay
  RAW strings (doctor vocab parsed OSGi-side). `details` is now an EXPLICIT nested Map field (was
  putAll-flattened at root by Intervention.toOutputMap).
- `InterventionDocuments.of` (OSGi producer): builds InterventionWire from Intervention (provenance.id,
  problem.toRef, prescriptionRef.map(id)) + `codec.encode`. No more toOutputMap.
- `InterventionReader`: `fromOutputMap(Object)` → `fromWire(InterventionWire)` — TYPED, no more Object
  + instanceof guards (user praised this: "on laisse pas passer n'importe quoi"). Keeps tolerance
  (unparseable required ref → empty).
- `InterventionLedgerReader`: decodes ONE InterventionWire per Document (was payload→FIELD_INTERVENTIONS
  list→blobs→fromOutputMap). Uses codec.
- `StackInterventionJournal` (host): `interventionDocuments()` emits one `intervention` Document PER
  blob from `outputsNamed` (was one Document wrapping the list). serialize(Object) now.
- `InterventionLedgerLayout.OUTPUT_KEY`: decoupled from WorldGatewayCatalog → literal "interventions"
  (a host-internal Pulumi transport key, NOT a seam wire field). Import of catalog dropped.
- `WorldGatewayCatalog.FIELD_INTERVENTIONS` DELETED.

STILL TODO for T7 (resume here):
1. Add `InterventionWire` to docs/architecture/osgi/seed-broker-spec.adoc (SPEC_COVERAGE names it,
   like ReadinessVerdict/InterventionRequest — else ERROR).
2. Migrate tests: `InterventionReaderTest` (fromOutputMap→fromWire, build InterventionWire not Map),
   `InterventionLedgerReaderTest` (rewrite: one InterventionWire per Document, drop FIELD_INTERVENTIONS
   list shape — uses it at lines ~110/123/178), any others referencing the old shapes. Grep
   FIELD_INTERVENTIONS + `.fromOutputMap` under intervention tests.
3. Build: PLAIN reactor (no extension change in T7): `flox activate -- ./mvnw package -Pall-worlds
   -Dmaven.build.cache.skipCache=true -DskipTests=false`. Expect SCHEMA_CONCORD 3 warn (readiness-
   checkpoint, consultation, visit). Commit.

## T7 DONE (2026-07-01) — all done, reactor + tests GREEN, SCHEMA_CONCORD 3 warn

All 3 gestes done. Spec names InterventionWire (+ fixed a stale mention: the para said the deleted
home-made OptionalModule; now says jackson's Jdk8Module). Tests migrated:
- `InterventionReaderTest`: fromOutputMap(Object/Map)→fromWire(InterventionWire); dropped the
  null/non-map/unparseable-when cases (the TYPE eliminates them — the hardening).
- `InterventionLedgerReaderTest`: rewritten off the `{interventions:[...]}` envelope (deleted) — now
  one InterventionWire per Document; malformed = undecodable payload or unparseable wire.
- `RecordInterventionCommandTest`: fakeIntake builds InterventionWire; and (user push) the test's
  residual `new ObjectMapper()` REMOVED — `wireOf(doc)` decodes via CODEC, assertions on the
  contract (wire.provenance()/when()/prescriptionRef()) not on string map keys. Tests-at-the-contract.
- Fix: RemediationProgramRef wire id is "restart-systemd-unit" (not "restart-unit").
User praised: typed door not open door; test at contract not implementation. NOT yet committed.

## SCOPE DECISION (user 2026-07-01): ALL reliability work stays IN THIS BRANCH

The user wants the whole reliability arc (design+build) DONE on feature/cluster-edge, NOT deferred
post-merge ("je me connais" — a deferred backlog won't happen). So after the 2D coordinate arc
(T7 done → T8, T9, T10 flip), do these IN THIS BRANCH, each its own commit:
- 2E: harden internal fromOutputMap(Object)→typed (doctor-records internal, non-wire) —
  [[harden-internal-fromoutputmap-backlog]]. User wants it right after the wire arc, not last.
- 2F: sweep residual `new ObjectMapper()` onto the codec — [[sweep-objectmapper-onto-codec-backlog]].
- 2G: JSpecify @NullMarked package default (non-null by default) —
  [[jspecify-nullmarked-default-backlog]] (will delete many now-dead null guards — synergy with typing).
- 2H: centralize seam dep scope/version in dependencyManagement/BOM —
  [[centralize-seam-dep-scope-version-backlog]].
(Naming caveat: 2E-2H are shorthand; @NullMarked + dep-centralization are repo-transverse, not strictly
world-gateway. Order TBD with user; capstone remote-validation still after.)

## T8 IN PROGRESS (2026-07-01) — readiness-checkpoint, NOT built, NOT committed

Commits so far: T5 5aa8b7e4, T6 20ffc7c2, T7 de30368d. T8 edits ON DISK, not built/committed.
readiness-checkpoint is the NESTING coordinate (observations[]), 2 host producers + 2 OSGi consumers.
Design decision (settled, consistent with T7): the two producer variants (verdict: failed/override;
consult: observations[]/recordedAt) UNION into ONE ReadinessCheckpoint with optionals — legitimate
here (same object type, unlike T7's structurally-incompatible shapes; carto already settled 1 coord).
details becomes an EXPLICIT nested field (not root-flattened), both realms move together.

DONE (edited, not built):
- SymptomKind now `implements WireEnum` (@Override slug()).
- NEW ObservationWire(status, summary, Optional<SymptomKind> symptom, Map details) — nested wire-record.
- NEW ReadinessCheckpoint(scenarioId, Optional<Boolean> failed, Optional<String> override,
  Optional<Instant> recordedAt, List<ObservationWire> observations), @DocumentContract(READINESS_CHECKPOINT),
  compact ctor normalizes nulls.
- SystemdAdapterStage: both checkpointDocument (verdict) + consultCheckpoint migrated to build
  ReadinessCheckpoint + codec.encode; dead serialize() removed; ObjectNode import dropped; added
  List + ReadinessCheckpoint imports. mapper/parse/FIELD_NARRATION KEPT — they now serve ONLY the
  consultation-narration read (line ~258), a T9 coordinate. THIS IS A TRACKED TRANSITIONAL SMELL
  (two idioms in one class); user OK'd leaving it, ELIMINATE IT IN T9 when consultation migrates.
- ObservationView (host): toOutputMap() → toWire() returning ObservationWire; SYMPTOM_KEY +
  LinkedHashMap removed; imports ObservationWire.

STILL TODO for T8 (resume here):
1. ClusterReadinessStage (host producer): its consultCheckpoint builds ReadinessCheckpoint + codec
   (same as SystemdAdapterStage's consult variant); migrate observations via ObservationView.toWire().
   Check its mapper/parse — likely also kept for a consultation read (T9).
2. DefaultReadinessAuthority (OSGi consumer, assess): decode ReadinessCheckpoint; read scenarioId +
   override (+ maybe failed). Currently reads FIELD_SCENARIO_ID/FIELD_OVERRIDE via jackson. Use
   codec.decode(checkpoint, ReadinessCheckpoint.class).
3. Generalist (OSGi consumer, consult + observationsFrom): decode ReadinessCheckpoint, map each
   ObservationWire → doctor Observation (was observationsFrom parsing FIELD_OBSERVATIONS list + the
   status/summary/symptom/details keys). scenarioId + recordedAt reads too. NOTE Generalist also
   reads/writes consultation (T9) — keep its mapper for that, tracked smell again.
4. Delete FIELD_SCENARIO_ID, FIELD_FAILED, FIELD_OVERRIDE, FIELD_RECORDED_AT, FIELD_OBSERVATIONS from
   WorldGatewayCatalog (verify no remaining users first — DocumentTest/GatewayVocabularyTest assert
   some of these; migrate/drop those assertions). FIELD_NARRATION/DIAGNOSIS_ADOC/CONSULTATION_REPORT/
   EXPECTATIONS stay (consultation T9); FIELD_WHEN/VERSION stay (visit T9).
5. Migrate tests: ReadinessAuthorityTest (builds checkpoint via FIELD_* → ReadinessCheckpoint),
   GeneralistConsultDocumentTest, RunbookRenderingTest, DocumentTest, GatewayVocabularyTest.
6. Name ObservationWire + ReadinessCheckpoint in world-gateway-spec.adoc (SPEC_COVERAGE).
7. Build: PLAIN reactor (no extension change): `flox activate -- ./mvnw package -Pall-worlds
   -Dmaven.build.cache.skipCache=true -DskipTests=false`. Expect SCHEMA_CONCORD 2 warn (consultation,
   visit). Commit T8.

Files with ObservationView.toWire/ReadinessCheckpoint already edited: SymptomKind.java, ObservationWire.java
(new), ReadinessCheckpoint.java (new), SystemdAdapterStage.java, ObservationView.java.

## T8 gestes 1-6 DONE (2026-07-01) — building, NOT committed

All migration edits done; build running (seed-master -am, plain target/). Per-geste:
1. ClusterReadinessStage: consultCheckpoint builds ReadinessCheckpoint via ObservationView.toWire()
   + codec.encode; dead serialize()/ArrayNode/ObjectNode dropped. mapper/parse KEPT (T9 narration read).
2. DefaultReadinessAuthority: decode(checkpoint, ReadinessCheckpoint.class), reads scenarioId +
   override().flatMap(Severity::parse). ObjectMapper/parse/JsonNode FULLY removed (bonus 2F — was
   only used by assess). failed field is carried but authority ignores it (override/intrinsic drives).
3. Generalist: consult() read side decodes ReadinessCheckpoint; observationsFrom(ReadinessCheckpoint)
   maps ObservationWire→Observation (SymptomKind.slug()→Symptom.parse(id), shared kebab vocab);
   recordedAt via decoded.recordedAt().orElseThrow. Dead parse() removed. WRITE side (consultation
   Document: mapper/serialize/ObjectNode + FIELD_SCENARIO_ID/NARRATION/DIAGNOSIS_ADOC) KEPT — T9 smell.
4. WorldGatewayCatalog: DELETED FIELD_FAILED, FIELD_OVERRIDE, FIELD_OBSERVATIONS, FIELD_RECORDED_AT.
   KEPT FIELD_SCENARIO_ID (retagged "Consultation payload" — still used by Generalist/RunbookRenderer/
   ResourceCreationPipeline consultation WRITE, a T9 field), FIELD_NARRATION, FIELD_DIAGNOSIS_ADOC,
   FIELD_CONSULTATION_REPORT, FIELD_EXPECTATIONS, FIELD_WHEN, FIELD_VERSION (all T9/visit).
5. Tests migrated: ReadinessAuthorityTest (checkpoint via ReadinessCheckpoint+codec, no ObjectNode),
   GeneralistConsultDocumentTest (checkpointWith builds ReadinessCheckpoint+codec, toWire() helper
   maps Observation→ObservationWire; consult-OUTPUT reads unchanged=T9), DocumentTest (dropped the 4
   deleted pins, kept scenarioId/narration/diagnosisAdoc). RunbookRenderingTest NOT touched — its
   consultationDocument builds a CONSULTATION Document (T9), uses only surviving fields.
6. Spec: added readiness-checkpoint para naming ReadinessCheckpoint + ObservationWire (nesting exemplar).

### RECONSIDERATION (the memory note was wrong): ObservationView has TWO views, TWO boundaries
The prior note said "toOutputMap()→toWire()" (rename). WRONG — over-removal. ObservationView.toOutputMap()
serves the PULUMI OUTPUT surface (the sink Consumer<Map<String,Object>> → systemdAdapterLaunchSummary,
a host-internal Pulumi output read back by ClusterReadinessStage for status/summary), which is a
SEPARATE concern from the seam wire. So ObservationView now has BOTH: toWire() (seam→ObservationWire)
AND toOutputMap() (Pulumi output, flat map, SYMPTOM_KEY="symptom" slug). Restored toOutputMap +
SYMPTOM_KEY const + LinkedHashMap import; retagged the record's javadoc to name the two boundaries.
Lesson [[reconsider-choices-when-revisiting]]: verify a "rename" note against actual call sites first.

## T8 DONE (2026-07-01) — committed b7de5a28, build+tests GREEN, SCHEMA_CONCORD 2 warn

All 7 gestes done, seed-master -am reactor GREEN (0 fail, 0 skip), SCHEMA_CONCORD 0 error / 2 warn
(consultation, visit — the last two coordinates, T9). Committed b7de5a28. Also committed 2f193242:
the developer's `nxmatic` Maven profile (build-parent/pom.xml <directory>target~nxmatic) + gitignore
rule for target~nxmatic/ — lets the developer run parallel builds (-Pnxmatic) without colliding with
my default target/. NOTE: target~nxmatic/ was NOT gitignored before; a naive `git add <dir>` aspirates
all its artifacts — stage `src` subtrees explicitly, never whole module dirs.

USER's next-step note (2026-07-01): "on a encore du travail pour concilier les internals avec
l'interface gateway. on regarde ca a la fin en regard des string constants tjrs reference dans la
codebase." → at the END (after T9/T10), do a sweep of the STRING CONSTANTS still referenced across the
codebase, to reconcile the internal record↔map readers (ConsultationReport.OUTPUT_KEY,
Expectation.OUTPUT_KEY, Symptom.ENVELOPE_KEY, the surviving FIELD_*, ObservationView.SYMPTOM_KEY, the
InterventionLedgerLayout "interventions" literal, etc.) with the typed gateway interface. This is the
2E/internal-hardening theme widened to "no loose string keys at the internal seams either".

## fromOutputMap hardening scope (user Q, 2026-07-01)

`fromOutputMap`→`fromWire` (Object→typed) widens ONLY where it's gateway WIRE:
- `ConsultationReportReader`/`ExpectationReader` (consultation coordinate) → hardened in T9 via nested
  ConsultationReportWire/ExpectationWire sub-records.
- `ExpectationPredicate.fromOutputMap`/`ResolutionPredicate` = doctor-records-INTERNAL record↔map
  (kind-discriminator dispatch, never crosses the seam) → separate post-2D backlog, NOT 2D.

Build command (user-confirmed, [[maven-build-cache-and-staging-verify]]): `flox activate -- ./mvnw
package -Pall-worlds -Dmaven.build.cache.skipCache=true -DskipTests=false` (SKIP cache, not disable;
no clean unless stale). Extension changes need the two-phase dance ([[osgi-staging-extension-chantier]]).
See [[world-gateway-2c-complete-2d-designed-state]] [[realm-library-isolation-state]].

## ARC STATUS (2026-07-01) — coordinate arc DONE + LOCKED; reliability arc in progress

ALL 6 coordinates typed + committed + reactor/tests green:
- T5 `5aa8b7e4` readiness-verdict, T6 `20ffc7c2` intervention-request, T7 `de30368d` intervention,
  T8 `b7de5a28` readiness-checkpoint (nested), T9 `acb231b8` consultation+visit (opaque blobs) —
  T9 ALSO eliminated the tracked mapper/parse smell (SystemdAdapterStage, ClusterReadinessStage,
  Generalist, DefaultReadinessAuthority are now jackson-free).
- T10 `42c785a1` — dropped the @GovernedBy(SCHEMA_CONCORD, WARN) override from the seam package-info;
  gate back to ERROR default, 0/0. THE 6-COORDINATE CONTRACT IS LOCKED (build fails if a coordinate
  lacks a wire-record). WorldGatewayCatalog reduced to 2 Pulumi transport keys (consultationReport,
  expectations); every wire-field FIELD_* deleted.
- jsr310 swap `9af2c777` — InstantModule→jackson's JavaTimeModule (see the dedicated memory). Also
  committed `2f193242` the developer's `nxmatic` Maven profile (target~nxmatic dir) + gitignore.

Build (both me + user, 2026-07-01): all-green, 0 fail/skip, SCHEMA_CONCORD 0 error/0 warn.

REMAINING (reliability arc, IN THIS BRANCH per user's "je me connais"):
- **2E** = NEXT, big change, DESIGNED + APPROVED, NOT started → see [[world-gateway-2e-annotations-plan]]
  for the exact bottom-up plan. Decode rich doctor records DIRECTLY via codec by putting
  jackson-ANNOTATIONS on the pure domain (adds zero realm surface — doctor-records already type=record
  dual-realm, annotations already in both realms). Deletes 3 *Reader classes + 6 toOutputMap + 2
  fromOutputMap. Compact BEFORE starting (user's call at 45% ctx).
- 2F sweep residual `new ObjectMapper()` onto codec (T9 removed many; audit what's left).
- 2G JSpecify @NullMarked package default. 2H centralize dep scope/version in dependencyManagement/BOM.
- FINAL: string-constant reconciliation (internals↔gateway) — 2E is the biggest slice of this; +
  FQCN→import pass (e.g. `io.seedmatic...Document` fully-qualified uses, user flagged); whole-branch review.
