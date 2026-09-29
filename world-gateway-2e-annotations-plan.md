---
name: world-gateway-2e-annotations-plan
description: 2E EXECUTION PLAN (locked design, not started) — type the doctor internal record↔map readers by putting jackson-ANNOTATIONS on the pure domain records + decoding rich records directly via the codec. Deletes 3 *Reader classes + 6 toOutputMap + 2 fromOutputMap. Fresh-session work (big change).
metadata:
  type: project
---

## STATUS: DONE (2026-07-01, commit `38c0e888`). Reactor + all tests green, SCHEMA_CONCORD 0 error, pulumi preview passes.

Executed exactly as planned. Notes worth keeping:
- `@JsonIgnore` is DISALLOWED on a ctor parameter — put it on the record COMPONENT header only
  (`record ReferralReply(@JsonIgnore Optional<Referral> referral, ...)`); native record deser passes
  null → the compact ctor normalizes to empty. No @JsonCreator needed on `reconstructed`.
- The per-FIELD tolerance the hand readers gave (drop a malformed report/expectation, keep siblings)
  MOVED to the blob boundary: `MedicalRecordReader.decodeBlob` try/catch→Optional.empty. To make a
  malformed blob THROW (so it degrades rather than folding a half-null record), added compact-ctor
  null-guards to ConsultationReport (checkpointId+plan), RemediationPlan (symptom), Expectation (all
  four). The lenient value-type creators return null → those guards fire.
- Reader tests → codec round-trip tests (ConsultationReportCodecTest, ExpectationCodecTest). The
  toOutputMap shape-assertion tests retired (shape is now the codec's). ConsultationReportSerializationTest
  deleted (doctor-port-test has no codec dep).
- 2G will need `@Nullable` on the 3 lenient creators' return (Symptom/RemediationProgramRef/ProblemRef
  `fromWire`) once @NullMarked lands. [[world-gateway-2d-execution-state]]

NEXT backlog (unchanged): wildcard-import sweep (dedicated commit, user-requested — non-deterministic
`records.*` etc across ~60 files incl doctor-spi + manifests), then 2F (ObjectMapper sweep), 2G
(@NullMarked), 2H (dep scope/version centralization), FINAL string-constant reconciliation + FQCN→import.

## ORIGINAL PLAN (kept for reference)

Prior arc all committed + green: T5 `5aa8b7e4`, T6 `20ffc7c2`, T7 `de30368d`, T8 `b7de5a28`,
T9 `acb231b8`, T10 `42c785a1` (SCHEMA_CONCORD locked at ERROR, 0/0), jsr310 swap `9af2c777`
(InstantModule→JavaTimeModule, see [[codec-instant-module-should-be-javatimemodule]]).

## THE DECISION (user chose, over two alternatives)

User's theme: "re-apply the gateway hardening logic to the INTERNAL packages" — the doctor
record↔map readers still on loose String keys. Two alternatives were mocked as C4 previews:
(a) wire-twins in doctor-core, (b) **jackson-annotations on the pure domain records** ← CHOSEN.

WHY (a) was rejected then (b) chosen: user pushed "jackson est déjà dans les deux mondes, rester
pure dans les records je suis pas décidé" → "si les annotations rendent le code plus lisible, je
vote pour". KEY correction that unlocked it: I had conflated **jackson-databind** (the engine — real
coupling, reverses the codec boundary) with **jackson-annotations** (78 INERT metadata classes, no
engine, no ServiceLoader). Verified facts:
- doctor-records is ALREADY `type=record` (dual-realm, embed; type=record in its bnd.bnd).
- jackson-annotations is ALREADY staged in BOTH realms (`META-INF/bundles/jackson-annotations.jar` +
  flat `com/fasterxml/jackson/annotation/*` in the uber-jar) — transitively via the codec's databind.
  So annotating the domain adds ZERO new realm surface.
- jackson has NATIVE record support (2.12+, reads component names from bytecode) — so the RICH records
  need NO annotations; only VALUE types + the sealed predicate + the transient field need hints.
- Jackson in use = **2.22** (`com.fasterxml.jackson`, NOT 3.x `tools.jackson`).

## EXACT PLAN (bottom-up; verify jackson-record-deser corners by RUNNING, not just reading)

1. **doctor-records/pom.xml** += dependency `com.fasterxml.jackson.core:jackson-annotations` (version
   managed by jackson-bom already in BOM). Metadata-only, no databind.

2. **Annotate the 4 value types** (each: `@JsonValue` on the id-getter, `@JsonCreator` static factory
   returning the instance — lenient parse returns null→jackson treats as absent, KEEP the tolerance):
   - `Symptom` (enum): `@JsonValue id()`, `@JsonCreator fromWire(String)` → `parse(s).orElse(null)`.
   - `RemediationProgramRef` (enum): same shape (`@JsonValue id()` / `@JsonCreator parse`).
   - `SchemaRef` (record `String id`): `@JsonValue id()` + `@JsonCreator parse` (or `@JsonCreator`
     canonical ctor). Open set — any non-blank string.
   - `ProblemRef` (record checkpoint+Optional<symptom>): `@JsonValue toRef()` (the "cp/sym" string) +
     `@JsonCreator parse(String)`. NOTE: it depends on `Checkpoint` (world-gateway seam) — fine, seam
     is already imported.

3. **Sealed predicate** `ExpectationPredicate` — replace the hand `fromOutputMap`/`kind`-switch with
   jackson polymorphism: `@JsonTypeInfo(use=NAME, include=PROPERTY, property="kind")` +
   `@JsonSubTypes(@Type(value=ResolutionPredicate.class, name="resolution"))`. Delete its
   `fromOutputMap` + `toOutputMap`. `ResolutionPredicate`: delete `fromOutputMap`+`toOutputMap`; the
   `symptom` component uses the annotated `Symptom` (kind key auto-emitted). Keep `heldAt`.

4. **ReferralReply** — the tricky one: `Optional<Referral> referral` is TRANSIENT (never serialized).
   Put `@JsonIgnore` on the component. Deser must use `reconstructed(assessment, prescription)` (leaves
   referral empty). VERIFY BY RUNNING: jackson canonical-ctor deser with an @JsonIgnore'd Optional
   component + a validating compact ctor (assessment non-null) is the classic "compiles, misbehaves"
   corner — may need `@JsonCreator` on `reconstructed` or a `@JsonProperty`-tagged ctor.

5. **Delete the 6 `toOutputMap()`**: ConsultationReport, RemediationPlan, ReferralReply, Assessment,
   Prescription, Expectation. **DO NOT delete** `Observation.toOutputMap()` (feeds the Pulumi sink in
   SystemdAdapterStage — a DIFFERENT surface) NOR `Intervention.toOutputMap()` (T7 intervention path).
   Delete the OUTPUT_KEY constants? NO — `ConsultationReport.OUTPUT_KEY`/`Expectation.OUTPUT_KEY` are
   the Pulumi transport keys still used (== WorldGatewayCatalog.FIELD_*), keep them.

6. **Delete 3 reader classes**: `ConsultationReportReader`, `ExpectationReader` (doctor-core.internal).
   `ExpectationPredicate.fromOutputMap` + `ResolutionPredicate.fromOutputMap` gone in step 3.

7. **Codec** gains thin generic helpers (the rich records are decoded via the SAME MAPPER):
   - `<T> T fromMap(Object rawMap, Class<T> type)` = `MAPPER.convertValue(rawMap, type)`.
   - `Map<String,Object> toMap(Object record)` = `MAPPER.convertValue(record, MAP_TYPE)`.
   - **MUST**: `MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` to keep the
     ADDITIVE-schema tolerance the string readers had (unknown key ignored, not a crash). User chose
     "full-typed + keep tolerance".

8. **Wire the two call sites**:
   - `Generalist.consult` (producer): `report.toOutputMap()`→`codec.toMap(report)`,
     `expectations…toOutputMap()`→`codec.toMap(exp)`. (These feed the opaque Consultation slots.)
   - `MedicalRecordReader.visitOf` (consumer): `ConsultationReportReader::fromOutputMap`→
     `blob -> codec.fromMap(blob, ConsultationReport.class)` (wrap in try/catch→Optional.empty to KEEP
     the per-entry partial fold — the fail-at-end contract MedicalRecordReaderTest pins), same for
     `ExpectationReader::fromOutputMap`→`ExpectationWire`… no, →`Expectation.class`.

9. **Migrate ~8 tests** (drop the now-jackson-native cases, keep the semantic ones):
   - doctor-core: `ConsultationReportReaderTest` (~290 lines — the tolerant-degradation cases; many
     become "codec round-trips" or move to a codec test; the malformed→empty cases now assert the
     try/catch→partial behavior), `ExpectationTest`, `MedicalRecordReaderExpectationTest` (already
     partly T9-migrated), `GeneralistConsultDocumentTest` (uses the deleted readers at lines 84/98/118
     — swap to codec.fromMap).
   - doctor-port: `ResolutionPredicateTest`, `AssessmentTest`, `ReferralReplyTest`,
     `ConsultationReportSerializationTest` — these assert `toOutputMap()` shapes; retarget to codec
     round-trip or delete the shape-assertions (tests-at-the-contract).
   - Fix the stale `{@link ConsultationReportReader}` javadoc refs in `Consultation.java` +
     `VisitWire.java` (world-gateway seam) — they name the deleted readers.

10. **Build**: `flox activate -- ./mvnw package -Pall-worlds -Dmaven.build.cache.skipCache=true
    -DskipTests=false -pl :seed-master -am`. Expect SCHEMA_CONCORD still 0/0 (2E doesn't touch
    coordinates). Watch for jackson-deser runtime failures on ReferralReply/Optional/sealed-type.
    Commit as its own `refactor(doctor): decode rich records via codec, delete hand readers (2E)`.

## GOTCHAS (the "run to verify" list)
- ReferralReply @JsonIgnore Optional + validating ctor (step 4) — highest risk.
- Assessment compact ctor throws on blank summary → a lenient decode of a malformed assessment will
  THROW inside jackson, not yield empty. The old reader degraded it. Decide: catch at the fromMap
  boundary (MedicalRecordReader already per-entry try/catch) — acceptable, the whole entry degrades.
- Observation is nested in ConsultationReport but its toOutputMap STAYS (dual use). For decode, the
  RICH ConsultationReport holds `List<Observation>` — jackson deser of Observation needs its
  `Optional<Symptom> symptom` (Jdk8Module ok) + `details` Map. Observation has NO @JsonCreator; native
  record deser should handle it (verify the Optional<Symptom> field decodes via the annotated Symptom).
- `MedicalRecordDump` (host, seed-master) reads VisitWire.consultationReport() as opaque Map for YAML —
  UNAFFECTED (never decodes to rich types). Leave it.

## FLOW (unchanged from T9; only the map↔rich step is now codec, not hand readers)
Generalist →rich ConsultationReport →codec.toMap→ Map → Consultation opaque slot →Document(String).
Host copies Map verbatim to Pulumi output → VisitWire(List<Map>) → MedicalRecordReader
→codec.fromMap(Map, ConsultationReport.class)→ rich record (per-entry try/catch = partial fold kept).
See [[world-gateway-2d-execution-state]] [[realm-library-isolation-state]].
