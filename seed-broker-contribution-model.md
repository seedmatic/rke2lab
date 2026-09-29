---
name: seed-broker-contribution-model
description: "The seed-broker's domain-contribution model, understood on the shipped code (2026-07-09). A domain contributes to the broker by publishing a SeedHandler @Component: it already carries its LOGIC + its own encode/decode (own DocumentCodec + wire-records). What's MISSING for full edge-contribution: (1) Coordinate is a closed central enum, not domain-owned — the ONE anomaly; (2) no client-side INTROSPECTION — a client cannot discover 'what can I grow for real?'. Both to build; Coordinate first (introspection rests on it)."
metadata:
  type: project
---

**How a domain contributes to the broker (shipped, verified 2026-07-09).** A domain publishes a
`SeedHandler` `@Component` (`serves()` + `handle(Document)`); `DefaultSeedBroker` collects them by
`@Reference(MULTIPLE)` and indexes `Map<Coordinate, SeedHandler>` by `serves()`, dispatching at
`sow(wanted, seed)` via `.get(wanted)`. **Declarative Services IS the multiplexer** the June design
(`multiplexor-two-models-design`) prescribed — the interface it imports forever is `SeedHandler`. It is
SHIPPED, not missing.

**Three pieces of a contribution, and where each lives (the key finding):**

| piece | contributed by the domain? | how |
|---|---|---|
| logic | ✅ yes | `@Component implements SeedHandler`, collected by DS |
| encode/decode | ✅ yes, INSIDE the handler | the handler holds its own `DocumentCodec` (dual-realm library, neutral) and knows its own wire-records (`codec.decode(doc, ReadinessCheckpoint.class)` / `codec.encode(verdict)`). No central codec-registry to contribute — the codec is neutral machinery, each handler uses it for its own types. |
| Coordinate | ❌ NO — the one anomaly | `serves()` returns a constant of the CLOSED central enum `Coordinate`. The domain DECLARES which coordinate it serves but cannot DEFINE one — it must pre-exist at the center. |

**Why encode/decode needs no contribution.** The `Document(domain, coordinate, String)` that crosses
the seam carries an OPAQUE JSON String; the broker never decodes. `DocumentCodec` is neutral by
polymorphism — `WireEnumModule` maps ANY `WireEnum` ↔ slug reflectively (never names an enum), and
`decode(payload, X.class)` takes the type from the caller. A new domain's wire-records + seam-enums are
handled automatically. **`WireEnum` is the proof the open/contributable pattern already works in this
codebase — `Coordinate` is the lone type that did NOT follow it (closed enum instead of interface).**

**Grafting needs no domain codec either.** The scion/runbook crossing (ScenarioGraft) carries a jGiven
`ReportModel` — agnostic narration (step names + statuses), serialized by `ScenarioJsonWriter`, NOT
`DocumentCodec`. The graft engine (scenario-engine, foundation) knows zero domain vocabulary. Only the
business content riding alongside (the consultations `List<Document>`) uses the codec — and that is the
handler's own encode. So: foundation graft = domain-agnostic; domains are self-sufficient for their
Documents.

## The REST analogy, closed (user, 2026-07-09) — introspection was the missing verb

Introspection was THE missing piece that left the model wobbling: everything said "one door, vocabulary
stays in the domain", yet the client still had to know a document's SHAPE from somewhere — and while
that shape lived in a shared compiled wire-record, the door was NOT the single source (a parallel
channel: the compiled class = a 2nd source of truth). REST closed this long ago: a client does not
compile the server's DTOs, it ASKS the server how to talk (`GET /` index/HATEOAS, `OPTIONS`/OpenAPI),
over the SAME transport. Our broker gets the same verb as a META-coordinate (NOT a new method):
`sow(available, ∅)` → coordinates actually served; `sow(shape, {coordinate:X})` → X's projected schema.
The broker self-describes through its own door, as `OPTIONS` rides the same transport as `POST`.

**Where the analogy ends — what REST does that we do not (and why it does not cost us):**

+ *Deferred to remote, by design* — content negotiation + schema VERSIONING. Embedded = one format, one
  schema version (reactor guarantees the pairing); a semver mismatch only becomes possible at the remote
  split, which the metaphor already dates. Not gaps — a dated frontier.
+ *Moot by construction, where we are SAFER than REST* — the GET-vs-POST (safe/read vs mutate)
  distinction. Our one door `sow` does not carry it and does not need to (user, 2026-07-09): REST needs
  the verb because over the wire NOTHING enforces a handler leaves the request untouched — `GET is safe`
  is a CONVENTION the protocol cannot impose. Our `Document` is an IMMUTABLE record; introspection shares
  the very same reference in-JVM, and immutability means sharing it produces no side effect — a `sow`
  CANNOT mutate the seed it is handed, not by promise but because the TYPE forbids it. REST declares safe
  and hopes; immutability makes violating it impossible. The read/mutate distinction REST carries in the
  verb, we carry in the record's immutability — a STRONGER guarantee, not an absent one.
+ HATEOAS beyond index + per-coordinate schema (hypermedia state transitions) = where the metaphor
  plateaus; YAGNI.

Verdict: nothing essential is missing — REST's extras are either deferred-to-remote (planned) or
replaced by a stronger guarantee. The analogy holds on the core; where it diverges, in our favour.

## What's missing — two builds, in dependency order

**1. Coordinate → contributable (the socle, do FIRST).** Make `Coordinate` an INTERFACE (`slug()`),
exactly as `WireEnum` already is; each domain provides its own enum (`DoctorCoordinate implements
Coordinate { READINESS_VERDICT("readiness-verdict"), ... }`). Then a handler's `serves()` returns a
domain-owned coordinate, and "publish a handler = publish its coordinates" holds end to end. Nothing
else needs to move: the broker indexes by identity (works), `WireEnumModule` handles it by
polymorphism (works). Verified NO prod code needs the closed set — `Coordinate.values()`/`valueOf`/
exhaustive `switch` = ZERO; the only `Coordinate.parse(slug)` caller is a test (`BrokerVocabularyTest`).
So opening the enum breaks no dispatch logic. ATOMIC change (user: no two-versions-of-the-system), but
bounded — today only ONE domain exists (`Domain.DOCTOR`); all 6 coordinates + wire-records are doctor's.
The wire-record binding `@DocumentContract(Coordinate)` and the `SCHEMA_CONCORD` ASM gate must follow
(the gate scans coordinate↔wire-record; if coordinates become domain enums, the gate scans per domain).

**2. Client-side INTROSPECTION (rests on #1, design AFTER).** User's insight (2026-07-09): the client
side is missing its half — "how do I discover what I can grow FOR REAL?", the REST-introspection /
OSGi-service-discovery analogue. A client today must hardcode `Coordinate.X` and know its wire-record.
The DESCRIPTION already exists but is BUILD-TIME only: `@DocumentContract` makes the wire-record BE the
schema (components → JSON Schema, projected by `SCHEMA_CONCORD`). It is consumed by the gate, never
offered to a client at runtime. The cure, symmetric to the domain side: the broker offers `available()`
→ the set of coordinates ACTUALLY served (the published handlers — "for real" = who has a grower, not
the theoretical enum) + each one's projected schema. This is the demux of the DS roster: the client
asks the broker "what can you grow?", the broker answers from what its handlers declare. The REST index
+ per-resource schema, in-JVM. Open question deferred to design time: does the handler carry its own
description (coordinate + wire-record/schema), or does the broker derive it from the roster
(serves() + reflect the @DocumentContract record)? Decide when building #2, on #1's stabilized shape.

## Client-side état des lieux (started 2026-07-09, doctor = the only domain today)

Doctor exposes TWO surfaces to a client: doctor-port (services: ConsultingService, HealthSystem,
InterventionLedgerWriter, the journals) and the seed-broker-port wire-records (ReadinessCheckpoint,
ReadinessVerdict, Consultation, InterventionRequest/Wire, VisitWire, Patient, Action, SymptomKind,
Checkpoint, ObservationWire). Scanning what the CLIENT (host seed-master + cluster, not doctor) still
touches of the doctor data structure — THREE natures, only the second is debt:

1. **LEGITIMATE — the client speaks the protocol** (it consults the doctor, so it knows the schema of
   the resources it calls, exactly like a REST client). cluster BUILDS a ReadinessCheckpoint +
   ObservationWire/SymptomKind to consult; the host READS ReadinessVerdict.action() to decide
   stop/continue. Knowing the exchange vocabulary is normal.
2. **DEBT — the host reads INTO a structure it should transport opaque.** THE clearest symptom:
   `SystemdAdapterResource.copyDiagnosticOutputs` does `CODEC.decode(consultation, Consultation.class)`
   then reads `decoded.consultationReport()` / `decoded.expectations()` only to re-route them to two
   Pulumi output keys. It KNOWS two internal fields of a Document it claims to carry opaquely. The
   proof it is residue: `SeedBrokerCatalog` (the two `FIELD_CONSULTATION_REPORT`/`FIELD_EXPECTATIONS`
   keys) exists ONLY for this re-routing — the exact thing the multiplexer was meant to erase. The host
   opens the parcel to sort its contents instead of passing it sealed.
3. **NEUTRAL host, not doctor** — `ConsultationLog` (a host container), and most of the big counts are
   tests.

**The point:** the client needs the PROTOCOL (nature 1), not the internal STRUCTURE (nature 2). Today
it knows both; nature 2 is the debt. And it ties to introspection (#2 build above): if the client could
DISCOVER a coordinate's schema (offered by the broker), it would never hardcode knowledge of internal
fields — introspection is the cure for the structure-debt.

## The Pulumi-frontier decorrelation — resolved by INTROSPECTION, not by a new common model (2026-07-09)

User's constraint (2026-07-09): "le domaine doctor ne doit pas avoir connaissance des contraintes liées
à la frontière pulumi — ça doit rester à la frontière et ne pas transpirer dans les domaines via le
seed broker." It is VIOLATED today, at two exact points, and this is nature-2's concrete cure.

**The leak, precisely (two points, two names):**

+ WRITE — `SystemdAdapterResource.copyDiagnosticOutputs` (seed-master): `CODEC.decode(consultation,
  Consultation.class)`, then splits `.consultationReport()` / `.expectations()` under TWO Pulumi keys.
+ READ — `StackMedicalRecordJournal.visitDocument` (pulumi-edge): reads those two named keys back and
  reassembles a `VisitWire`.
+ The two names that ARE the whole leak: `SeedBrokerCatalog.FIELD_*` (foundation) + `ConsultationReport
  .OUTPUT_KEY` / `Expectation.OUTPUT_KEY` (doctor-records). The domain carries the NAME of Pulumi slots.

**The key finding — the "common contract" already EXISTS; it is doctor's own aggregate model.** Navigating
the doctor records, every Pulumi term has a doctor counterpart, one-to-one:

| Pulumi | Doctor | proof in the records |
| --- | --- | --- |
| stack (org/project/stack) | `Patient` / `MedicalRecord` | `Patient(org, project, stack)` — literally the same record |
| a stack update (version, when) | `Visit` | `Visit(version, when, …)` ← `StackHistory.Entry(version, when)` |
| a **resource** (SystemdAdapterResource, ClusterReadinessResource) | a **`ConsultationReport`** | both are ONE-per-checkpoint; the resource IS a checkpoint's consultation |
| an **output** (named key) | a sub-tree the `Visit` aggregates | `Visit = reports[] + expectations[]` → the two keys |

So the two-key split is NOT legacy accident and NOT a Pulumi need — it is doctor's OWN structure: `Visit`
holds `reports` (per-checkpoint / per-resource) AND `expectations` (per-Visit, aggregated across
resources, checked against the NEXT visit — the `MedicalRecord.efficacyOf` lookahead). Two doctor lists →
two keys. Pulumi is merely a BACKEND that realizes this shape (stack=record, update=visit, resource=report,
output=aggregated visit sub-tree). No new common model to invent.

**The cure = runtime INTROSPECTION, OSGi-side (NOT local reflection at the frontier).** An earlier draft
said "the wire-record is `type=seam` → the host holds a copy → `getRecordComponents()` at the frontier":
that is WRONG and was rejected same-day (see the roles paragraph) AND is now moot — the migration MOVED
the wire-records into `doctor-records` (not `type=seam`), so the host holds NO copy to reflect on. The
correct cure: the frontier ASKS the broker (a `sow` at a meta-coordinate); the REFLECTION of the marked
components happens OSGi-side, where the class lives (`DoctorGraftReflector`, shipped — it serves
`GraftCoordinate("doctor")` and reflects `@Graft` components of `Consultation`). The runtime twin of
`RecordSchemaProjector`, but OSGi-side, never at the frontier.

**Roles decided (user: "chacun son rôle", deterministic — 2026-07-09; CORRECTED same day — the frontier
does NOT reflect locally, it ASKS the broker):** the user caught that reflecting AT the frontier would
make it HOLD the seam class — the very tie to remove. Introspection here is the SAME "ask the broker"
mechanism (the final key applied to the Pulumi frontier too): "what are the storage slots of coordinate
X?" is a `sow` at a META-coordinate. REFLECTION lives where the CLASS lives (OSGi-side).

+ the DOMAIN *declares*: a component-level marker (the field-level analogue of `@DocumentContract`) on the
  wire-record components that are addressable storage slots (the "outputs"). Doctor declares WHICH fields
  are persistable — never WHERE/HOW Pulumi stores them.
+ the META-HANDLER *reflects*: reads the marked components OSGi-side (its realm holds the wire-record
  class), answers the slot names as a Document (Strings). The runtime twin of `RecordSchemaProjector`,
  but OSGi-side, not at the frontier.
+ the FRONTIER *asks + executes*: `sow(slots, {coordinate})`, then routes each returned slot NAME to a
  Pulumi key = that name, transports the rest opaque. Holds NO wire-record class, hardcodes NO name.
+ This satisfies BOTH constraints at once: Pulumi never transpires into the domain (marker + reflection
  OSGi-side), AND the host holds no class (true from PALIER 2, not only palier 3 — the frontier only
  asks). ONE mechanism (ask the broker), two application points (Pulumi frontier, then sowing) — no
  throwaway local reflection.
+ Rejected: the frontier reflecting locally (it would hold the seam class); the convention "`List<…>`
  component = aggregated slot" (too implicit, against determinism); the marker on SeedHandler (storage
  vocabulary back in the domain contract — the handler stays `Document → Document`, backend-blind).
+ In-JVM note (user, 2026-07-09): `sow` passes the Document BY REFERENCE (no copy) — but only the NEUTRAL
  `Document` (record of Strings, immutable, same classloader both sides) crosses; the `String payload` is
  the wall that stops any typed wire-record reference from crossing realms. Introspection answers are
  Strings (slot names), so no wire-record class crosses even there.

This is the concrete resolution of nature-2 AND the bridge between the two fronts (doctor contribution +
introspection). Sequence unchanged: socle first (Coordinate→interface + doctor-broker-port), THEN this
Pulumi decorrelation as its own increment. RESUME after socle: build the component marker + the frontier
introspector, delete SeedBrokerCatalog + the two OUTPUT_KEY.

## The sealed-envelope invariant + which gate it obsoletes (user, 2026-07-09)

User's crisp statement of the target: **the broker distributes envelopes, but for a given envelope
sender == recipient == the domain.** The host is only the postman — it carries the sealed envelope, it
neither opens nor writes it. Consequence for governance (two gates, OPPOSITE effects):

+ *SCHEMA_CONCORD becomes obsolete AS A SEAM GATE* — its justification is an inter-realm AGREEMENT (both
  worlds parse the payload with their own jackson, so they must agree on the schema). When the envelope
  is unopenable host-side, there is only ONE reader (the domain, on both ends of the round-trip): an
  agreement with one party guards nothing. NET, MEASURABLE retirement criterion: **SCHEMA_CONCORD dies
  the day NO host code does `codec.decode(WireRecord.class)`.** NOT yet — the host still decodes typed
  (the deferred nature-2 debt), so today the gate still guards a real two-reader agreement; keep it
  through the transition (adapting it to domain-owned coordinate enums is NOT wasted). What survives its
  death: `RecordSchemaProjector` (record→JSON Schema) recycles as the runtime introspection source
  (`sow(shape,…)`); "every coordinate has a wire-record / schema well-formed" become tests INTERNAL to
  the domain, not seam governance.
+ *REALM_BOUNDARY becomes LOAD-BEARING, the opposite* — it forbids a flat/host class from referencing a
  bundle-only domain type. Moving the wire-records from the seam (`type=seam`, shared) INTO doctor-records
  (`type=record`, not system-exported) is what makes the envelope unopenable host-side, and REALM_BOUNDARY
  is the gate that ENFORCES it. It is the mechanism of the sealed envelope, not obsolete.

See [[gateway-is-rest-in-jvm-insight]] [[multiplexor-two-models-design]]
[[world-gateway-lost-open-extensibility-debt]] (this is that debt's concrete resolution path)
[[spec-coverage-gate-state]] (the gate whose seam-justification this retires).

## SHIPPED since (state update 2026-07-10)

Much of the "to build" above is now realised — do not re-plan it as open:

+ *Coordinate → interface: SHIPPED.* `SeedCoordinate` is an interface (`slug()` + `domain()`);
  `@SeedContract(String slug)` binds by slug. The seam renamed `world-gateway → seed-broker-port` (+
  `seed-broker-codec`); `Document → SeedEnvelope`, `DocumentCodec → SeedCodec`.
+ *Wire-records LEFT the seam: SHIPPED.* `Consultation`/`VisitWire`/`InterventionWire`/etc. now live in
  `doctor-records` (not `type=seam`) — so the host holds NO copy, and any "reflect at the frontier" idea
  is moot. `SeedBrokerCatalog` still exists but is slated for deletion (it names Pulumi output keys, the
  leak the decorrelation removes).
+ *The graft path: SHIPPED OSGi-side.* `@Graft` marks a wire-record component; `DoctorGraftReflector`
  (`@Component implements SeedHandler`) serves `GraftCoordinate("doctor")` and reflects the `@Graft`
  components. The frontier "asks the broker" mechanism has a live answerer — only the HOST side that ASKS
  is not written yet.

## Host adaptation — the DSL grammar + the storage model (session 2026-07-10)

The host-side design is settled (see [[seed-broker-host-adaptation]] for the full état des lieux):

+ *Getting a `SeedBroker` host-side is SOLVED* — `awaitService(SeedBroker.class)` in a stage;
  `FrameworkLaunchPipeline.embedded().during(…, SeedBroker.class, …)` offline. Not the migration's
  problem.
+ *HATEOAS is the second discovery idiom, ONE verb.* The spec listed both REST discovery idioms but only
  developed `OPTIONS` (ask a meta-coordinate for a shape). The other is HATEOAS: a reaped PLANT advertises
  the coordinates it can be sown at NEXT. Following one is a `sow` at that coordinate — NOT a new
  `graft(...)` verb. The DSL is ONE door: `sow(coordinate, seed) → plant + graft coordinates`, then
  `sow(graftCoordinate, plant)`. (User was learning the DSL and proposed a 2nd verb; corrected — one verb,
  sow twice, exactly as REST HATEOAS follows links with the same GET/POST.)
+ *The stack is an envelope store; a stored envelope RE-SOWS.* `sow → plant → (stored in the Pulumi
  stack) → seed` — the horticultural cycle closed. The read frontier reads envelopes back opaque and
  transports/re-sows them; it never builds a `VisitWire`.
+ *CRUD addresses the SHELL, never the payload.* A `SeedEnvelope(domain, coordinate, payload)`: the shell
  (neutral Strings) is what the frontier files/finds BY; the payload is sealed. The one mechanical change:
  `StackSnapshot.outputsNamed(key)` stops being keyed on a domain FIELD (`consultationReport`) and starts
  being keyed on a COORDINATE slug — that kills `SeedBrokerCatalog.FIELD_*` and both `OUTPUT_KEY`. Store
  the sealed String, return the sealed String, the slot name comes from the plant (asked of the broker).
+ *`@Graft` vs the DSL "graft" — RESOLVED (2026-07-10): no collision.* `scion`/`rootstock` mean the same
  on both channels → graft/scion/rootstock are ONE register, `graft` its verb. Settled renames (to apply
  at coding): `@Graft` → `@Scion` (an annotation names a role, not a verb); add `@Rootstock` (the twin —
  marks the receiver IDENTITY component, `scenarioId`); `GraftCoordinate` → `SplitCoordinate` (its role is
  "split the envelope" = return `{rootstock → [scions]}`). The frontier then hardcodes NOTHING. Full
  detail in [[seed-broker-host-adaptation]]; the `SeedEnvelope` type is NOT renamed (`végétal` is a lexicon
  word, not a class name).
