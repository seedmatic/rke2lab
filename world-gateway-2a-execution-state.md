---
name: world-gateway-2a-execution-state
description: World-gateway 2A+2B SHIPPED + REVIEWED on feature/cluster-edge. 2B COMPLETE (zone-0 Tasks 1-3 + Task 4+5+6 commit 33a2b30a; per-increment review done 2026-06-30, verdict APPROVED-WITH-MINOR, 2 hygiene nits in 2ff96746) — the host consult path holds NO doctor reasoning; consult crosses as a Document (String payload, Option B), seam split moved the 3 record verbs to ClinicalReasoning (doctor-spi) via ConsultingService.adapt. realm-boundary worklist 41→38. DECISION (2026-06-30): branch is NOT merged until the worklist reaches 0 and REALM_BOUNDARY flips WARN→ERROR — that gate flip IS the merge point ("the two worlds must live separate at merge"). 2C is BRAINSTORMED + SPEC'D (commit e6b2f6f1, the PEER MODEL → [[world-gateway-2c-peer-model-design]]). The LAYOUT-FIRST increment then SHIPPED (commits a05dd52c..a0f64f2a, osgi/ re-laid into foundation/runtime/domains, gateway→world-gateway → [[osgi-layout-shipped-state]]). NEXT: write the 2C PLAN (writing-plans on the existing 2C spec, against the NEW world-gateway/launcher names) → execute 2C (worklist 38→0) → flip REALM_BOUNDARY WARN→ERROR (the merge point) → remote-validation capstone → merge. Authoritative ledger: .superpowers/sdd/progress.md. See [[world-gateway-2c-peer-model-design]] [[osgi-layout-shipped-state]] [[checkpoint-identity-to-seam-backlog]].
metadata:
  type: project
---

## 2B COMPLETE through Task 4+5+6 — RESUME AT TASK 7 (2026-06-29)

Trust the SDD ledger `.superpowers/sdd/progress.md` + `git log` over recollection. Session commits:
`e4aeaec8` S1 slf4j backend · `7389e973` Option B (Document.payload→String, gateway-port jackson-free) ·
`d786b6ce` DUPLICATE_REALM_CLASS static gate (found cdk8s dup, WARN) · `405dac85` memories · `b8481c2d`
aggregator-layout spec (post-merge work) · `33a2b30a` Task 4+5+6 (consult reasoning crosses as Document).

**State:** the host consult path holds ZERO doctor reasoning type. consult crosses as a checkpoint
Document carrying an `observations` LIST (1 systemd / N cluster, all kept — no info lost); Generalist
routes on the first symptom-bearing one. ConsultationLog holds Documents; egress copies the
consultationReport/expectations sub-trees opaquely to the same OUTPUT_KEYs (readers UNCHANGED);
RunbookRenderer reads diagnosisAdoc. The 3 record verbs left ConsultingService → ClinicalReasoning
(doctor-spi), reached via `consultingService.adapt(ClinicalReasoning.class)` (the adapt(Class) default).
Checkpoint (identity enum) + Observation/Symptom (egress/scenario) legitimately remain.

**VERIFIED:** full reactor `package -Pall-worlds -DskipTests=false` BUILD SUCCESS; DoctorCoreInContainerTest
33/33, DoctorPortInContainerTest 34/34; realm-boundary 41→38 warn; gates green (spec-coverage 0 error,
duplicate-realm-class cdk8s WARN).

**REMAINING:** Task 7 — close-out (mark 2B shipped) + a final whole-branch review (use
requesting-code-review / a code-reviewer subagent on the whole branch diff). Then finishing-a-dev-branch.
OUT of 2B: 2C (the reconstruction path — DriftReview, the *Readers, recordForCurrentPatient/reviewOpenProblems),
the egress increment (Checkpoint/Observation/Symptom → seam, [[checkpoint-identity-to-seam-backlog]]),
the in-container realm diagnostic ([[realm-duplication-gate-brainstorm-state]]), the doctor-core-test
transitive-leak cleanup ([[transitive-import-leaks-doctor-core-test-backlog]]), the osgi/ aggregator
re-layout ([[osgi-aggregator-layout-spec-state]], post-merge).

(Historical 2B-execution detail — S1/Option B/root-cause — retained below.)

## 2A SHIPPED (2026-06-27, feature/cluster-edge — kept, not merged)

Plan: `wip/plans/2026-06-27-world-gateway-2a-document-foundation.md`.
Spec: `docs/architecture/osgi/world-gateway-2a-document-foundation-spec.adoc`.
Parent design: [[world-gateway-document-design]] (2A/2B/2C/2D; 2A = Document foundation +
readiness verdict crossing; cut = parse-vs-consume so `from()` is doctor-free).

Commits (on top of `b423f407` Task 3):
- `0269c1b0` **fix(staging)** — Plan-1 gate timing flaw. `REALM_BOUNDARY` ran inside
  `reconfigureStaging` BEFORE `delegate().execute()` (before compile) but reads the exec's
  `target/classes` → on a cold tree: no governance anchor → ERROR default → only seam dep-jars
  policed → 18 ERROR → build fails at generate-resources → compile never runs → DEADLOCK. FIX:
  `enforceGates` moved AFTER `delegate().execute()`; the shade/staging Xpp3Dom reconfiguration STAYS
  before (mojos read it as they build). `resolveBundles` computed once, shared. The gate now runs on
  EVERY build and finally self-scans host classes on clean builds (soundness gap closed). Extension
  is RELEASE-coord `1.0.0` via `.mvn/extensions.xml`, so it must be `install`ed to ~/.m2 (the
  documented exception — the reactor can't supply an extension loaded before it).
- `abe3626e` **feat(seed) Étape 4** — `SystemdAdapterStage` builds a readiness-checkpoint `Document`,
  calls `ReadinessAuthority.assess`, reads the verdict's `action` (stop|continue-degraded). No
  `Severity` type on the host anymore; Task-3 bridges gone. `runbook`/`consultations`/`doctor` are
  `@Nullable` (the stage null-guards them — real optional collaborators; prod ALWAYS supplies them,
  null only in test). A package-private 7-arg test-only ctor omits the three; the same-package test
  fixture bridges it to a public `failing(...)` factory — keeps the test ctor off the prod public
  API WITHOUT dropping the class's `final` (so no anonymous-subclass/inheritance route). worklist
  44→41.
- `fe31317a` **refactor(testkit)** — see the durable pattern below.

Verified: full reactor `package -Pall-worlds -DskipTests=false` BUILD SUCCESS, 0 test failures,
`realm-boundary: 0 error` everywhere (41 warn for seed-master). Reviewed by an opus code-reviewer
(only minor comment-hygiene findings, all fixed and folded in).

## Durable pattern learned: DERIVE the in-container install closure from the host

The `OutOfContainerFrameworkExtension` proxy tests each hand-maintained the bundle set to install
(the JUnit runner world ×3, jackson, doctor records+spi, manifests' 14-name GRAPH). Three were
derivable. Generalized the SHARED `BundleIndex.closeOverImports` walk — the one prod `BootPlanner`
drives, already used here for `withScr()` — to seed from the HOST bundle:

- `installImportClosureOf(Bundle... hosts)` installs every classpath bundle the hosts transitively
  import, nothing else. Its already-provided set is read from the **running system bundle's own
  exports** (`framework.adapt(BundleWiring).getCapabilities(PACKAGE_NAMESPACE)`) — intrinsic
  framework packages + the seam `systemPackages`. Seeding it with only the configured seams was the
  bug that pulled `osgi.core` (a duplicate exporter of `org.osgi.framework` → resolve returns false
  though everything "wired"). `exporterOf` already skips seam-typed bundles, so a seam is never
  pulled.
- `withJUnitRunner()` captures the proxy-infra runner world (launcher/engine/this testkit) in ONE
  shared declaration — it is the test's own scaffolding, NOT derivable from the host.

What STAYS explicit is exactly what OSGi makes irreducible: **seams a host imports stay
system-exported** (host-flat by design; the walk skips them) — and a seam exports MULTIPLE packages,
so list them ALL (manifests-core needed manifests.port + .port.node + .port.profiles + netplan.port +
systemd.port + pipeline; missing one → host UNRESOLVED). A FRAGMENT is reached only if it EXPORTS a
package the host imports (manifests' systemd-cdk8s-manifests exports systemd.cdk8s → pulled); a
fragment nothing imports could not be derived (doctor-port seeds host+fragment because the fragment's
FakeSpecialist imports doctor.spi the host doesn't). Migrated all 3 proxies uniformly: doctor-core 30,
doctor-port 34, manifests-core 6 — green. Diagnostic lever for a false resolve: `@FrameworkLog(DEBUG)`
prints the Felix resolver WIRE/FRAGMENT-WIRE trace to stdout (no slf4j backend needed); the
`resolve()` slf4j post-mortem needs a backend (JGivenTestkit supplies one, bare `builder()` does not).

## 2B — SPECCED + PLANNED, RESUME HERE (execute, 2026-06-27)

Spec: `docs/architecture/osgi/world-gateway-2b-consult-path-spec.adoc`.
Plan: `wip/plans/2026-06-27-world-gateway-2b-consult-path.md` (7 tasks, TDD, one commit each).
Both committed `4c91a852`. Design brainstormed WITH the user (5 decisions, all in the spec) — do NOT
re-litigate; execute.

**Scope:** the consult/failure path crosses as a Document, decomposed BY ZONE (user's choice), the
shared seam first because both consult stages share `ConsultingService`'s 3 verbs:
- zone-0 (Tasks 1-3): add `consult(Document checkpoint)→Document consultation` to `ConsultingService`
  (the Document twin of 2A's `assess`, a DISTINCT verb — NOT folded into assess); `Generalist`
  implements it, rendering the narration string AND the `diagnosisAdoc` AsciiDoc block OSGi-side (it
  owns the `RemediationPlan`); rename `DoctorGraph`→`ConsultationDag`.
- zone-1 (Task 4): systemd-adapter — probe returns a checkpoint Document (symptom as a SLUG string,
  no `Observation.failed(Symptom)`), stage calls `consult(checkpoint)`, logs narration.
- zone-2 (Task 5): cluster — identical; THEN remove the 3 old record-typed verbs from the seam.
- Task 6: `RunbookRenderer` reads `consultation.diagnosisAdoc()` (a string) into the jGiven shell,
  drops its `doctor.records` imports; `diagnosisBlock` MOVED to `Generalist` in Task 2.
- Task 7: close-out — worklist's consult-path slice gone, mark 2B shipped.

**The 5 design decisions (settled, in the spec):** (1) consult DISTINCT from assess — keeps the
authority(verdict) vs consulting(diagnosis) seam split. (2) narration + diagnosisAdoc are strings the
host LOGS/INSERTS — DEFINITIVE, not transitory; produced OSGi-side. (3) the host does NOT render the
runbook — OSGi produces the AsciiDoc TEXT (markup, not HTML), so NO asciidoctor/jruby/graphviz
dependency. (4) DoctorGraph→ConsultationDag. (5) self-review caught: 2A's checkpoint
(scenarioId/failed/override) is INSUFFICIENT to route a consult — EXTEND it with `symptomKind` (the
Symptom slug, OSGi maps back to the enum it owns) + `summary` + `details`; one checkpoint instance
feeds both assess and consult.

**Two open verifications flagged for the executor** (in the plan's self-review): whether `Checkpoint`
is a `doctor.records` type (then the runbook join uses the raw slug string instead) and whether
`doctor-port` already deps `gateway-port` (add if absent — Task 1).

**Boundaries:** 2B touches ONLY the consult/failure path. NOT the reconstruction path (`DriftReview`,
`*Reader`, `recordForCurrentPatient`/`reviewOpenProblems` — those 2 seam verbs STAY) = 2C; NOT the
Pulumi-resource egress (`*Resource`, `toOutputMap`) = egress increment; NOT the JSON schemas / the
REALM_BOUNDARY→ERROR flip = 2D. `ConsultationReport` is NOT deleted (reconstruction + its OSGi tests
still use it). Green-per-zone: zone-0 is build+OSGi-test green but the HOST worklist does NOT shrink
until zone-1/2 (host still calls old verbs) — expected, not a regression.

**Verify recipe:** seed-master via `package -Pall-worlds -DskipTests=false -Dmaven.build.cache.skipCache=true`
(NEVER bare `test`); doctor-core-test via bare `test` on its module; full reactor to read the
`realm-boundary` worklist shrink per zone.

Branch kept, never merged ([[hub:external-worktree-operating-model-state]]). Folds the
[[doctor-graph-vs-dag-vocabulary-backlog]] rename. See [[world-gateway-document-design]]
[[realm-boundary-gate]] [[maven-build-cache-and-staging-verify]]
[[felixframeworkextension-renamed-outofcontainer]] [[options-always-as-c4-diagrams]].

## 2B EXECUTION — RESUME HERE (2026-06-28)

Plan `wip/plans/2026-06-27-world-gateway-2b-consult-path.md`; SDD ledger `.superpowers/sdd/progress.md`
(authoritative — trust it + `git log` over recollection after compaction). The design evolved a LOT via
user dialogue beyond the original plan; the live state:

**zone-0 SHIPPED (Tasks 1-3, reviewed, in the ledger):**
- Task 1 (`864ec8d8`+`a892c8ee`+`bdd41226`+`71ce2da3`+`8a971397`): the typed seam vocabulary —
  user flagged the catalog "fourre-tout" → lifted the closed value domains into seam enums
  `Domain`/`Coordinate`/`Action`/`SymptomKind` (each `slug()`+`parse()`, byte-for-byte uniform);
  `GatewayCatalog` slimmed to `FIELD_*` schema keys ONLY. `Document` STAYS neutral; call sites write
  `.slug()`. NO flat `Field` enum (the per-coordinate JSON Schema in 2D is the real field typing).
  `consult(Document)` verb + gateway-port→doctor-port pom dep. Opus review Approved (first reviewer
  FABRICATED — 0 tool calls; ALWAYS make reviewers quote verbatim identifiers from the diff).
- Task 2 (`fc0e441e`+`c1949cc3`+`1bbfdce0`): `Generalist.consult(Document)` (parse checkpoint,
  `toSymptom(SymptomKind)` exhaustive switch no-default, rebuild Observation, route, return narration
  + diagnosisAdoc). User flagged 3 dispersed `new ObjectMapper()` → seam owns payload build via static
  `Document.newPayload()`; the instance `DocumentCodec` @Component (YamlMapper's JSON twin) deferred to
  2D ([[document-codec-instance-in-2d-backlog]]). Opus review Approved.
- Task 3 (`45f8b7fd`): pure rename DoctorGraph→ConsultationDag.

**zone-1 (Task 4) — the egress/reconstruction knot + the jackson root cause:**
- User constraint (HARD): the Pulumi output must keep the SAME info (form may differ) AND the medical
  record must stay reconstructible from the stack (the stack IS the record store). →
  [[world-gateway-2b-zone1-egress-knot]]. Resolution A-struct: the consultation Document carries the
  RENDERED strings AND the STRUCTURED plan/observations/expectations in the existing `toOutputMap()`
  shapes, so `ConsultationReportReader`/`ExpectationReader` stay UNCHANGED; the probe KEEPS `Observation`
  (egress+scenario), only the consult reasoning leaves the host. Task 4 in the plan is rewritten for this.
- Task 4a (`acf44cca`, COMMITTED but BROKEN) enriched the consultation Document with the structured
  sub-trees — but introduced a `LinkageError` in-container (proven root cause below). It compiles + passes
  FLAT but FAILS `DoctorCoreInContainerTest` [15]/[16].

**ROOT CAUSE (proven 2026-06-28, [[document-seam-cannot-expose-jackson-jsonnode]]):** `Document.payload()`
returns a jackson `JsonNode` — a BUNDLE type — exposed through a FLAT `type=seam` (gateway-port). jackson
is a bundle (user's standing rule: jackson enters OSGi via a bundle, not the JCL); the seam is flat → two
`JsonNode` realms → `LinkageError` when doctor-core (bundle) touches a Document payload. Latent since 2A;
revealed by the first in-container test that exercises it. Felix `@FrameworkLog(DEBUG)` on
DoctorCoreInContainerTest gave the WIRE proof.

**DECIDED SEQUENCE (do in this order):**
1. **S1 — slf4j test backend (NOW, before Option B).** The `OutOfContainerFrameworkExtension.resolve()`
   diagnostic runs HOST-side and logs via slf4j `LOG.error(... unsatisfiedRequirements(bundle))` — but
   `@Osgi` testkit runs have NO backend (junit-testkit deps slf4j-api ONLY, on purpose — a 2nd provider
   once broke jGiven resolve). Add a TEST-SCOPE backend (logback-classic + logback-test.xml) on
   junit-testkit / the `-test` modules — host-side, NOT in the boot closure, so the jGiven trap can't
   recur. Gives a voice to the already-written `unsatisfiedRequirements()`. VERIFY by re-running a jGiven
   in-container test after, to prove resolve isn't re-broken. (S3 — a dedicated testkit resolve-failure
   dump — only if S1 proves insufficient; `unsatisfiedRequirements()` already does S3's job, just muted.)
2. **Option B — Document.payload becomes a String.** `Document(String domain, String coordinate, String
   payload)`; drop `Document.newPayload()`; gateway-port DROPS its jackson dependency. Each world
   serializes/parses with ITS jackson (doctor-core's bundle one; host's). Refactor the 2A foundation:
   `Document`, `ReadinessAuthority.assess`/`DefaultReadinessAuthority`, `SystemdAdapterStage`,
   `Generalist.consult`, all tests reading `payload()` as JsonNode. This SUPERSEDES Task 4a's approach
   (4a's `acf44cca` enrichment is re-expressed as String serialization). REVERT/rework 4a's Generalist
   change accordingly. Verify via `DoctorCoreInContainerTest` (NOT flat).
3. **SEAM_PURITY staging gate.** Add to `StagingGate` (maven-embed-staging-ext): a `type=seam` bundle's
   `Import-Package` may name ONLY system-exported (other seams)/JDK/OSGi packages — a bundle package
   (jackson) = ERROR. Goes green exactly when Option B drops jackson from gateway-port. Freezes the
   invariant.
4. THEN finish zone-1 (Task 4 host migration: consultDoctor→Document, ConsultationLog carries Documents,
   egress reads the structured payload into the same OUTPUT_KEYs, readers unchanged), zone-2 (Task 5),
   runbook (Task 6), close-out (Task 7).

**Working-tree state at compaction:** on top of `acf44cca`. `Generalist.java` reverted to `acf44cca`
(the moot toNode experiment dropped). `DoctorCoreInContainerTest.java` has `@FrameworkLog(DEBUG)` +
the FrameworkLog import ADDED (diagnostic lever — REMOVE once S1 lands). The renamed memory
`document-seam-cannot-expose-jackson-jsonnode.md` is untracked. NOTHING half-written in prod code beyond
4a (which is committed and will be reworked by Option B).

**Reviewer reliability rule (learned this session):** a subagent reviewer that reports 0 tool calls or
identifiers absent from the codebase has FABRICATED — discard, re-dispatch forcing verbatim diff quotes.
Always re-run the IN-CONTAINER harness yourself for seam/Document changes; a flat `-Dtest=` hides
two-realm collisions.
