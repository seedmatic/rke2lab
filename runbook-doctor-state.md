---
name: runbook-doctor-state
description: feature/runbook-doctor — Increments A-D DONE; BDD-quality pass COMPLETE; DAG chantier has a refined medical-model design (patient=Pulumi stack, Dossier=consultation report, Set<Symptom> bounded by observability, Doctor=generic module, medical record=reconstructable state). Source of truth = docs/architecture/doctor/runbook-doctor.adoc + docs/architecture/doctor/pulumi-doctor-integration.adoc (committed 930069c1). Layer 2 DONE (0cd2b2f5: doctor's plan kept, not dropped — ConsultationReport + ConsultationLog). Layer 2's other steps DISSOLVED (no ExamReport rename; no Set<Symptom>=rule-of-three; VerificationResult-as-projection-of-report impossible — 2 distinct aggregates). NEXT = layer 3 (medical record) in a FRESH session: our model = source, runbook+VerificationResult = views, full record serialized into state, guard renegotiated, phantom-diff to solve first. Pulumi fork RESOLVED (doctor = app logic, Option A). T2/T3/T4 open.
metadata: 
  node_type: memory
  type: project
  originSessionId: fecaba54-8122-4dc1-8916-743ef5d2dec0
---

**LAYER 3 WRITE-SIDE — DONE (2026-06-07, this session). 38/38 green, guard HELD, final review SOUND & READY.**
The phantom-diff dilemma was DISSOLVED by a recadrage (brainstormed + validated): Pulumi already
holds the graph (state `dependencies`) and the longitudinal log (update history) → we serialize ONLY
the diagnostic layer Pulumi ignores, per-node, additively; NOT a top-level `medicalRecord` blob, NOT
edges, NOT timestamp. This REVERSES the previously-locked spec-IncE decisions (top-level blob / full
record / renegotiated guard) — see `docs/architecture/doctor/layer3-medical-record-design.adoc` (a8c37c75) + its plan
(the layer-3 design (committed 9b9302d9); both prose+C4/UML, no Java — [[hub:docs-diagrams-not-java]]).
4 commits: **52781714** `Checkpoint` enum (single-source join key: `slug()` + derived
`resourceName()`="seed-"+slug; both stages' SCENARIO_ID + both resource names consume it — kills the
clusterApi/cluster-api hand-aligned-literal risk; identity ONLY, never topology). **13bb7715** the
cluster→systemd edge is now a REAL `dependsOn` on the resource (`this.systemdAdapter`, not the common
`readinessDependency` parent) → persisted graph matches the runbook nesting; the BDD `@NestedSteps`
nesting becomes a render VIEW, not the edge source. **3eb12587** `toOutputMap()` on
Prescription/RemediationPlan/ConsultationReport (flat, kebab ids via `.id()`, TDD). **4e8e71d1**
per-node `registerOutputs`: each checkpoint ComponentResource registers an ADDITIVE `consultationReport`
output, joined from the shared `ConsultationLog` by `Checkpoint.slug()`, `ifPresent` only (healthy node
= no symptom = no output = no phantom diff). GUARD: `OutputBuilder` UNTOUCHED, the 9 cluster*/handoff/
bootstrapStatus keys unmoved, `ClusterReadinessProjectionTest` green unchanged — top-level Stage-B
contract byte-identical; the diagnostic data lives only UNDER the component resources in state.
Review's 2 MINOR non-blocking findings (left as-is, YAGNI): dossier-element serialization weakly
pinned in the test; `Map.of` null-fragility (acceptable fail-fast). NOTE: `pulumi preview` ran but
can't VISUALLY confirm — `dependsOn` is structural (not a diffable property) and the live probe
returned ok (no symptom → no report); confirm both via a user-run `pulumi up` + state graph inspection.

**READ-SIDE PART 1 — RUNTIME ENRICHMENT — DONE (2026-06-07, user-validated in the VSCode preview AND
the Pulumi log: "exactement ce que je voulais, la séparation des points de vue est respectée", "le
rapport texte a bien disparu").** Renderer fork RESOLVED = Approach A (inject into the JGiven model;
JGiven's AsciiDoc renderer keeps rendering — the ONLY renderer we use). 4 commits on
`feature/runbook-doctor`, all 40/40 green, guard HELD (no Pulumi output change — the diagnosis lives on
the in-memory ReportModel only):
- **c8c7116f** `Checkpoint.scenarioTitle()` — a THIRD single-source identity (slug + resourceName +
  scenarioTitle, still identity-only never topology); both stages pass it to `startScenario(...)`.
- **0ed98c13** `RunbookRenderer.render(ReportModel, ConsultationLog)` injects the diagnosis: for each
  `ConsultationReport`, join to the `ScenarioModel` by `getDescription().equals(checkpoint.scenarioTitle())`
  (JGiven stores the title VERBATIM; only display-capitalized) and set `extendedDescription` to a
  Diagnosis(⚕)/Mitigation(℞) block (AsciiDoc visitor emits extendedDescription — VERIFIED via javap).
  Flipped `RunbookRenderingTest` (the TDD entry): asserts the runbook now CONTAINS the
  `restart-systemd-unit` prescription + "Diagnosis"/"Mitigation" (was asserting their ABSENCE). Added
  `Checkpoint.fromSlug()` (identity lookup belongs on the identity type). Fixed all `render(...)`
  callers (BootstrapStage passes the in-scope ConsultationLog → absorbed the plan's Task 3).
- **daa34928** Pulumi log REFERENCE-PURE: deleted both stages' `logReport(...)` (the verbose
  PlainTextReporter Given/When/Then + "Test Class: null" dump) and the inline ⚕/℞ `log(...)` lines; KEPT
  `consultations.record(...)` (feeds the runbook), the ✓/⚠/✗ status lines, the ⚙ simulate notice, and
  the renderer's "runbook rendered → path" reference. `NestedRunbookTest` (which scraped the log for ⚕)
  re-pointed to assert via the `ConsultationLog` (the authoritative surface). Separation of concerns:
  the log REFERENCES, the runbook NARRATES.

**NEXT = RE-DISCUSS POST-HOC RECONSTRUCTION (deferred; user's call to revisit now that runtime ships
and the rendered runbook is concrete).** See the deferred bullet below.

(historical: the original 3-step framing was)
- ~~NOW = RUNTIME ENRICHMENT only~~ — DONE, see above. Approach A; join by scenario title; flip
  RunbookRenderingTest; strip the log last (the operator-UX decision — done in daa34928).
- **THEN = RE-DISCUSS POST-HOC RECONSTRUCTION (user's call: revisit AFTER the runtime change ships,
  with the rendered runbook in front of us).** Deferred, NOT abandoned — the write-side already made it
  POSSIBLE (the diagnostic is in state). Decide it on concrete output, not in the abstract; the runtime
  render may reframe it as "just reproduce this render from `stack export`", which sharpens the
  no-drift criterion (runtime render == reconstructed render). Do NOT build it pre-emptively.

(historical framing kept:) the post-hoc reconstruction tool (`pulumi stack export`
→ rebuild DAG from `dependencies` → graft `consultationReport` by `Checkpoint.slug()` → render) + the
"our model = source" renderer flip (flip `RunbookRenderingTest`'s "no Diagnosis/Mitigation" assertion
= the TDD entry point — VERIFIED 2026-06-07 the rendered runbook shows the FAILURE but NOT the ⚕/℞
diagnosis yet: `seed-master/target/runbook/adoc/features/runbook.asciidoc` has the AssertionError, no
prescription/Mitigation section). CORRECTNESS CRITERION proven there: runtime in-memory render ==
post-hoc reconstructed render (same view) = the no-drift proof. Then T2/T3/T4 (spec IncE) once a first
reconstruction exists. Uncommitted parking files (dsl-unification) left untouched in the tree by user's
call.

**OPERATOR-UX DECISION (2026-06-07, user) — Pulumi log = REFERENCE ONLY; the runbook .adoc preview in
VSCode is the readable surface.** Target: stop dumping JGiven's `PlainTextReporter.toString(...)` prose
(the verbose Given/When/Then blocks + `Test Class: null`) into the Pulumi log; keep only a reference to
the rendered runbook path + status. User chose "REFERENCE PURE" (cut ⚕/℞ from the log too, not just the
prose). **SEQUENCING (user's call): this log cleanup is the LAST step of the read-side, NOT a
pre0requisite** — because today the ⚕/℞ prescription (e.g. `incus exec master -- systemctl restart
rke2lab-dbus-tcp-system-bus.service`) lives ONLY in the log (lines ~117-118 of a simulated-incident
preview); it is NOT yet in the rendered runbook. Cutting it before the renderer-flip injects
Diagnosis/Mitigation into the .adoc would lose the remediation command from BOTH places. So: read-side
first (diagnosis into the runbook), THEN strip the log to reference-pure. IMPLEMENTATION ANCHOR for the
cut: the two `logReport(reportModel)` calls in `SystemdAdapterStage`/`ClusterReadinessStage` produce the
verbose prose; the actionable `⚕`/`℞`/`⚠` lines come from separate `log(...)` calls; the reference
already exists (`runbook rendered → …/index.asciidoc`).

---

Active chantier on branch **`feature/runbook-doctor`**: build the runbook + doctor subsystem.
Design DONE; **A DONE** (3b46b249), **B/doctor DONE** (aa55ced4), **C/checkpoint#2-nested DONE
DSL-first** (9685793c), **D/live-cluster-wiring DONE** (fc91f127), **D-preview-fix DONE** (140414cb,
2026-06-06). **NEXT = the last deferral**: the shared report-node model so the plan renders into each
runbook node's Diagnosis/Mitigation sections (vs. today's inline log) and records explicit
`dependsOn` DAG edges (today the edge is shown via @NestedSteps nesting). Now unblocked: both
checkpoints are live BDD scenarios.

**Both checkpoints now render in the runbook on BOTH `pulumi preview` AND `pulumi up`.** The preview
fix (140414cb): ClusterReadinessStage was early-returning in preview (cluster absent from the
preview runbook); now it sets jgiven.report.dry-run (bodies skipped, no live infra) but still
plays+finish()es the scenario so the shell renders, then sinks deferredPreview. Verify after running
pulumi: runbook at `seed-master/target/runbook/adoc/index.asciidoc` should show "Systemd adapter
becomes reachable" AND "Cluster becomes ready" (with the nested systemd-adapter dependency).

**BDD-QUALITY PASS (2026-06-07, IN PROGRESS) — triggered by the user reading the rendered runbook.**
Four commits landed on `feature/runbook-doctor`, all 31/31 green; two chantiers remain in the pass.

*1. Cluster naming + visible phases (34f2a1a9, then refined by 0b3eb45d).* The runbook showed
`Given the cluster "master"` (it was passed `config.nodeName()`; fixed to `config.clusterName()` →
"bioskop") and one opaque `the readiness phases run` line. **Final shape (0b3eb45d):** the three
phases are EXPLICIT FLUENT STEPS chained in canonical order —
`.the_kubeconfig_is_published().and().the_api_is_ready().and().the_required_controllers_are_effective()`
— each delegating to a private `checking(ClusterReadinessPhase)` (the enum stays the single join to
probe + simulation; method name is the narration, enum label is the short dossier tag). Replaced an
intermediate `@NestedSteps`+loop+`break` attempt. **JGIVEN SEMANTICS (settled by a scratch probe):**
a top-level fluent chain `a().and().b().and().c()` where `b` throws → JGiven SKIPS the bodies of the
downstream chained steps and marks them SKIPPED (fail-fast is the chain's own semantics, no manual
break). Contrast: inside `@NestedSteps` the exception is DEFERRED (re-thrown at finished()), so a
loop there would NOT fail-fast. `ThenClusterReadiness` is now a thin closing assertion (reached only
on full success); `failingPhase`/`failingDossier` deleted. NestedRunbookTest asserts per-step
StepStatus PASSED/FAILED/SKIPPED — rigorous proof the downstream body never ran.

*2. POM restructure (8ed3c513) — the user's call: BOM has its own lifecycle/version; children carry
NO dependencyManagement.* `bom` is now STANDALONE (no `<parent>`, own version `1.0.0-SNAPSHOT`, only
EXTERNAL version management — the rke2lab inter-module entries moved out). The PARENT holds the
single `<dependencyManagement>` (imports the BOM + manages rke2lab modules at `${project.version}`)
and a common test toolchain in `<dependencies>`: junit-jupiter + jgiven-junit5 + mockito-core +
mockito-junit-jupiter (all test scope, versions from the BOM). The 6 children dropped their
dependencyManagement and inherited test deps; seed-master keeps `jgiven-core` in MAIN scope (plays
scenarios in prod). Mockito 5.11.0 + byte-buddy 1.14.19 come from spring-boot-dependencies, aligned
with JGiven's byte-buddy. Verified two ways: full `clean install` AND from-source `package -pl
:seed-master -am` (the parent importing a reactor BOM resolves without install — no cycle, because
the BOM has no parent).

*3. Test-double policy (decided) + scenario dedup (0d2c7a4f).* **Policy:** Mockito IS in the project
now; rule = lambda for a single-method @FunctionalInterface with a canned return (the probes);
Mockito when verifying an interaction or doubling a multi-method collaborator (the doctor) or a
STATIC seam (`mockStatic(Deployment.class)` for the preview path); NEVER mock a JGiven Stage
(byte-buddy already subclasses them). The user also wants BDD (Given/When/Then) for UNIT tests too,
accepting two JGiven uses: main (scenarios ARE prod behaviour) + test (readable component tests).
**Dedup:** `NestedRunbookTest` used to re-implement the cluster Given/When/Then by hand → the
scenario lived in TWO places. Fixed by INJECTING the probe: `ClusterReadinessStage` and
`SystemdAdapterStage` now take their probe as a ctor param (prod injects ProductionClusterReadinessProbe
/ the dbus gate lambda; SystemdAdapter's preview-only simulate override still wins). The test now
calls the REAL `new ClusterReadinessStage(...).launch()` with a fake/simulated probe + captured sink
— same code prod runs — and also asserts the VerificationResult projection + that the stage consults
the doctor (logged ⚕/℞). `pulumiMode=false` short-circuits `Deployment.getInstance()` so the stage
runs offline. **GOTCHA:** `Deployment.getInstance()` returns `DeploymentInstance` (not `Deployment`)
— mock that type. **KNOWN DEBT:** Mockito's inline mock-maker self-attaches the byte-buddy agent →
"Java agent loaded dynamically" WARNING (non-blocking on JDK 25, forbidden in a future JDK). A
`-javaagent:${net.bytebuddy:byte-buddy-agent:jar}` argLine attempt FAILED (the dependency-plugin
`properties` goal did not substitute the path → JVM got the literal string, crashed the fork);
reverted entirely (no trace). Revisit with a tested approach when the JDK enforces it.

*4. Nesting (e3ac4b4d) — DONE.* The six top-level stage files (Given/When/Then × SystemdAdapter +
ClusterReadiness) collapsed into TWO per-scenario containers `SystemdAdapterScenario` /
`ClusterReadinessScenario`, each a `final` class with a private ctor holding `public static class
Given/When/Then extends Stage<…>`. bdd/ top-level count 23→19. Cross-scenario edge preserved:
`ClusterReadinessScenario.When` references `SystemdAdapterScenario.Given/When/Then` via
`@ScenarioStage` (the nested dependency). Consumers rewired: both prod stages' `Scenario.create`,
`SystemdAdapterScenarioTest extends ScenarioTest<Scenario.Given,…>`, `RunbookRenderingTest`.
**Rendered prose is byte-identical** — JGiven derives step names from METHOD names + scenario titles
from `startScenario(...)` strings, neither touched; only Java type names changed. Proven green by
NestedRunbookTest asserting exact step text. 31/31.

*5. BDD unit tests — DONE (the LAST pass item, two commits).* **Commit 97d0b9d0 — shared fixture
DSL.** The same `Map.of("incus"…"image"…"worktree"…) → ConfigLoader → from(dto)` block was
copy-pasted across 6 test files (as `config()/policy()/dto()/loaderOf()/full()/mandatoryOnly()`).
Extracted to ONE `src/test` DSL `controlplane/config/OperatorConfiguration`:
`empty()/mandatory()/full()` + fluent `with(section,key,val)`/`without("incus.configDir")` +
`asLoader/asDto/asBootstrapConfig/asPolicy`. All 7 tests rewired; mechanics tests (ConfigLoader,
Rke2labConfig, BootstrapConfigFrom, ClusterReadinessProjection) KEEP exhaustive assert bodies — only
input construction goes through the DSL (TDD/BDD split preserved). `without()` expresses
missing-mandatory cases as `full().without(key)`. **DELEGATION RULE (user's, 2026-06-07):** when a
test's SUBJECT is config behaviour (ready-vs-missing), delegate to the `ConfigEntryGate` BDD stages
(compose via `@ScenarioStage`, the follow-the-chain pattern); the DSL is for config-as-FIXTURE only.
**Commit 97383f0b — DoctorScenario.** `DoctorTest` mixed behaviour (consult→plan) + type mechanics
(symptom parse, dossier round-trip). Split: new TEST-ONLY `DoctorScenario` (Given a doctor staffed /
a failure presenting a symptom · When consulted · Then a prescription issued | no treatment but
plan names the symptom) + `DoctorScenarioTest`; Generalist/Specialist/Dossier vocab IS the DSL.
Stages live in `src/test` (doctor is consulted INSIDE checkpoints, never standalone in prod →
localisation rule). The 3 recognized-but-untreated cluster symptoms = one scenario each (NOT
`@ParameterizedTest`) — each renders its own runbook line AND avoids JGiven 2.0.3 probing the JUnit
≥5.13 `ParameterInfo` class (we resolve 5.10.5 via spring-boot) which logged a harmless
NoClassDefFoundError. 33/33 green.

**THE BDD-QUALITY PASS IS COMPLETE.** All 5 items done. Remaining future work is the DEFERRED shared
report-node DAG model (below) — its own chantier, not part of this pass.

**JUNIT 6 / JGIVEN WATCH-ITEM (investigated 2026-06-07, user asked "why not bump to 6.1.0?"):**
BLOCKED, not by caution — by the dependency graph. JUnit 6.0 REMOVED 5.x APIs JGiven 2.0.3 was
compiled against (`PreconditionViolationException`, `ReflectionSupport.loadClass()`, …) → runtime
`NoSuchMethodError`. JUnit + JGiven must move TOGETHER. **Latest RELEASED JGiven is 2.0.3** (Maven
Central confirmed); JUnit-6 support exists only on JGiven's unmerged/unreleased `main`. So no bump
until JGiven ships a JUnit-6 release. When it does: clean coordinated bump = import `junit-bom:6.x`
BEFORE spring-boot-dependencies in our standalone `bom/pom.xml` (first `import` wins) + bump
jgiven-* together. No functional gain today regardless.

**User's design seed for the deferred DAG model:** "it's the step/edge that decides fail-fast vs
fail-at-end." Refined together: a step isn't fail-fast in the absolute — it BLOCKS its dependents
when it's a precondition. Failure propagates fail-fast ALONG dependsOn edges (dependents skipped)
and fail-at-end BETWEEN independent branches (all run, all report). Today's 3 phases are a linear
chain so everything is fail-fast and correct; fail-at-end becomes meaningful only when the shared
report-node DAG lands AND there's a genuine pair of INDEPENDENT checks (none in cluster yet — its
phases are intrinsically sequential). Don't build per-step policy now (rule-of-three: one shape).

**DAG CHANTIER — THE MEDICAL MODEL (terminology session 2026-06-07). SOURCE OF TRUTH = the design
docs in the repo.** Read `docs/architecture/doctor/runbook-doctor.adoc` (Increment E) and
`docs/architecture/doctor/pulumi-doctor-integration.adoc` (C4/UML + the resolved Pulumi fork). The
layer-2/3 plans were executed and removed at the wip→docs migration (the code is the record). (The `~/.claude/plans/parallel-floating-corbato.md`
scratch file is SUPERSEDED by these — it predates the model evolution below.) Model is a DIRECTION,
deliberately NOT frozen — several tensions left open until a first working doctor version exists.

LOCKED in this session:
- **Patient = the Pulumi STACK** (`org/project/stack`, persistent identity). Several patients =
  several stacks. (Evolved: "the bootstrap" → "the checkpoint" → finally "the stack".) A checkpoint
  is NOT the patient — it is where the patient SELF-OBSERVES a subsystem.
- **Flow is patient→doctor:** the patient self-observes, raises its own `Symptom`s, and consults.
  A visit = one `pulumi up`/preview, carrying the admission context (git commit/branch, preview-vs-up,
  call params = Pulumi stack config incl. `simulate`, timestamp — mostly already in `OutputBuilder`,
  NOT yet in the runbook).
- **A consultation carries a `Set<Symptom>` (0..n), BOUNDED BY OBSERVABILITY:** a precondition chain
  (cluster's kubeconfig→API→controllers — downstream unobservable if upstream fails) collects up to
  the first blocker (≈1 today); independent observations collect all. = the user's "edge decides".
- **NO separate exam/observation layer** (ExamReport rename ABANDONED). Today's `Dossier` IS the
  consultation report (status + raised symptoms + summary + details + the plan). `Symptom` unchanged.
- **Medical record = the Pulumi STATE, reconstructable from it** (user correction): at runtime you
  can't query reverse deps (program gets no graph, checkpoints play eager) → runbook built from an
  in-memory model DURING a run; but POST-HOC the DAG is IN the state (each resource carries outputs
  = the node + `dependsOn` = the edges) → reconstructable off-line. DESIGN CRITERION: the model is
  correct when the DAG can be fully reconstructed from the stack state ⇒ the WHOLE consultation
  report (plan included) must land in resource OUTPUTS (→ state), not just the in-memory model.
- **T1 RESOLVED: reactive consult** (on a symptom, as today — not systematic per visit; root-cause
  correlation comes from edge-propagated record access instead).
- **Doctor = generic pluggable MODULE** (settles the config↔doctor open question: neutral module,
  config remediation is its 2nd use case; rule-of-three met). Design MODULE-READY now (open
  `Symptom`/`SpecialistDomain` + symptom→domain ROUTING becomes contributed data, not the hard-coded
  `Generalist.routeBySymptom()` switch); physical Maven extraction DEFERRED.

**LAYER 2 — DONE (0cd2b2f5).** Shipped a `ConsultationReport` (raised dossiers plus the
`RemediationPlan`) and a caller-owned `ConsultationLog`, threaded like the runbook `ReportModel`
(`recordingInto(runbook, consultations)` → `PipelineState` → BOTH stages). On a raised symptom each
stage records a report → the plan (was computed-logged-then-DROPPED in `consultDoctor`) is kept.
Reactive model: a healthy checkpoint raises no symptom → no report. In-memory only, Pulumi outputs
UNTOUCHED (guard held, `ClusterReadinessProjectionTest` unchanged). 35/35 green (+2). **Layer 2's
other planned steps DISSOLVED on contact with the code** (plan predated the reactive model): no
`ExamReport` rename (no exam layer); no `Set<Symptom>` (cluster is a fail-fast precondition chain →
always size 1 → rule-of-three speculation); and "`VerificationResult` = projection of the
consultation report" is IMPOSSIBLE+already-half-done — a healthy checkpoint produces NO report but a
`VerificationResult` ALWAYS exists; they are TWO DISTINCT AGGREGATES (`VerificationResult` = the
always-present Stage-B HANDOFF projection, already a projection of the phase dossiers since Inc D;
`ConsultationReport` = MEDICAL, only on a symptom). The DAG consumes the reports, NEVER the
`VerificationResult`. So layer 2's "unify the output shape" goal was layer 3 in disguise: making
`VerificationResult` a *view of the medical record* IS building the medical record. Only real
residual dup = the 7 `cluster*` keys defined twice (`asOutputs` + `ReadinessOutputMapper`) — optional
handoff-side cleanup, unrelated to the medical model.

**LAYER 3 = NEXT, in a FRESH session (decisions LOCKED 2026-06-07, code in `docs/architecture/doctor/runbook-doctor.adoc` Inc E).**
The medical record (one per patient-stack) is OUR aggregate and becomes the SOURCE; the runbook AND
`VerificationResult` become VIEWS — this REVERSES today's dependency (JGiven `ReportModel` is the
structure today; instead our model is the source and the JGiven model is *derived* for rendering →
resolves the renderer fork toward "our own renderer, JGiven as one backend"). Runtime = in-memory
model (state not yet written; checkpoints eager); state = its SERIALIZATION; post-hoc =
reconstruction. **User's call: serialize the WHOLE record (plans included) into the outputs** for
100%-faithful reconstruction from state alone. **GUARD RENEGOTIATED** (reverses the all-session
"no output changes" stance): Stage-B contract keys UNCHANGED (handoffReady/nextStep/bootstrapStatus/
7 `cluster*`/systemd flat), a NEW ADDITIVE key (e.g. `medicalRecord`) carries the serialized record.
**FIRST PROBLEM TO SOLVE when coding: the PHANTOM DIFF** — `OutputBuilder` deliberately omits
`timestamp` ("changes every run → phantom diff on no-op up"); the full record carries admission
(timestamp/git) + varying plans → re-introduces it. Parade (exclude timestamp / stable hash) tensions
with "100% faithful". Deferred to fresh session because this conversation is FAR past its context
budget and serialization deserves a clean start.

**PULUMI-INTEGRATION FORK — RESOLVED (a949f3bb): doctor is NOT a resource; Option A.** User's
argument: Pulumi resources are PROVISIONED toward a desired state, not used/invoked as actors. A
doctor is behaviour (no desired state); the patient is the stack (modelling it as an inner resource
is circular) → doctor/patient-as-resource walks on Pulumi's toes. Option B (provider-backed
CustomResource) ALSO rejected: re-observation already happens IN-PROGRAM (probes re-run each up), so
a provider `Read` reconstructs managed external state we don't have — heavy Java gRPC plugin for ~no
gain. ⇒ the "does pulumi-java support inline providers?" question is MOOT. **Verdict (Option A):**
doctor stays APPLICATION LOGIC (a module, never a resource); only the consultation report is carried
into state via `registerOutputs` on the checkpoint's EXISTING `ComponentResource` — an output-CARRIER
(ComponentResource is the one kind NOT provisioned toward a target), not a provisioned actor. "Record
= reconstructable state" = re-read the checkpoint's outputs + `dependsOn`; the "re-observe" cycle is
the program re-running, not a provider Read. Full argument + C4/UML in `docs/architecture/doctor/pulumi-doctor-integration.adoc`.
A Pulumi-DOMAIN Specialist (reads engine errors to diagnose) stays possible later as just another
`Specialist` — distinct from persistence, does not make the doctor a resource.

RENDERER-ROUTE — RESOLVED (2026-06-07) into the layer-3 "our model = source" decision: the
medical-record model is the source, the JGiven `ReportModel` is DERIVED from it for rendering (our
own renderer, JGiven as one backend). `RunbookRenderingTest` asserts "no Diagnosis/Mitigation before
the doctor exists" → flipping it is the TDD entry point when layer 3 lands.

STILL OPEN (deferred until a first doctor version exists):
- **T2** stateless doctor vs panel (access as `consult()` param vs internal state). **T3** routing
  contributed (see module-ready). **T4** runtime context = the VISIT's admission, not the patient's
  persistent identity (`org/project/stack`) — decides where it attaches.

**Verified earlier this session:** `.local.d/bioskop/master/host.preview` (synthesized config) is
COMPLETE vs the applied `host/` — 0 config files changed/added; the 105 "missing" files are all
runtime flox artifacts (.flox/log, .flox/cache) + cluster-api/staged/image-state-configmap.yaml
(written at pulumi-apply time by design, IncusResourceBootstrap.createImageStateConfigMap, the
chicken-and-egg fingerprint pattern — docs/staged-post-cluster-resources.adoc). So A–D changed only
the seed-master control-plane (runbook/doctor), NOT the synthesized host manifests — applying is
essentially a no-op on the host-state side.

**Build/test invariant (use this exact command — repo build-cache can give stale reactor jars):**
`flox activate -- ./mvnw package -Dmaven.build.cache.skipCache=false -pl :seed-master -am
-DskipTests=false`. 31/31 seed-master tests green. NEVER `mvn install` to ~/.m2 (CLAUDE.md).

**Increment D delivered** (seed-master): cluster-readiness now played LIVE as a BDD scenario (was
procedural verify() in a lazy Pulumi applyValue lambda → never hit the runbook). `ClusterReadinessStage`
(mirror of SystemdAdapterStage) plays it EAGER in the pipeline thread (deps already concrete), so it
records into the shared ReportModel before BootstrapStage's finally renders — avoids the applyValue
fires-after-render empty-node trap. `ProductionClusterReadinessProbe` bridges each phase to a public
per-phase check on the verifier (checkKubeconfigPublished/checkApiReady/checkControllersEffective; the
phase-0 bootstrap-preconditions gate folded into phase 1); the two dead verify() overloads deleted.
New typed Symptoms KUBECONFIG_MISSING/API_NOT_READY/CONTROLLER_NOT_READY routed in Generalist (domain
CLUSTER) — named in runbook, no specialist yet → empty plan. **VerificationResult is now the
projection of per-phase dossiers** via verifier public ready()/failed() factories — all 10 output
keys + handoffReady→nextStep (Stage B gate) + bootstrapStatus byte-identical; ClusterReadinessProjectionTest
pins it. ClusterReadinessResource = thin graph mirror (keeps dependsOn, no verify). runbook+generalist
threaded BootstrapPipeline→ResourcesStage→ResourceManager→ResourceCreationPipeline→stage. 30/30 green.

**Increment C delivered** (seed-master `controlplane.bdd`, DSL-first/offline): `ClusterReadinessPhase`
(kubeconfig/API/controllers) as BDD steps, each driven by an injectable `ClusterReadinessProbe`→
`Dossier`; the systemd-adapter dependency replayed via **`@NestedSteps`** (`WhenClusterReadiness`
injects systemd-adapter Given/When/Then with `@ScenarioStage`) = the cert-manager follow-the-chain
DAG edge, reusing the same Dossier/Symptom/Generalist machinery. `SimulatedClusterReadinessProbe.
failingAt(phase, symptom)` targets one phase. `NestedRunbookTest` proves nested render + targeted
fake incident + doctor diagnosis. **GOTCHA learned:** never read a JGiven Stage's captured-state via
a public getter — JGiven intercepts public stage methods as STEPS and corrupts the model flush; use
the probe-holder seam (as `SystemdAdapterStage` does). 41/41 seed-master green.

**Increment B delivered** (seed-master `controlplane.bdd`): the doctor. `Dossier` (typed successor
of the probe's Map envelope — status, `Optional<Symptom>`, summary, details + `toOutputMap()`);
`Generalist` (deterministic symptom→`SpecialistDomain` routing → `RemediationPlan`); `Specialist`
interface = the AI-ready seam (`Optional<Prescription> diagnose(Symptom, Dossier)`);
`DbusTcpSpecialist` (connection-refused → `RESTART_UNIT`); `Prescription` + `RemediationProgramRef`
(typed catalog, no magic strings) + `RemediationPlan`. **R3 Map→Dossier retype done atomically, no
shim** — probe/gate/When/Then/fakes/sim/stage-sink moved together; sink converts via `toOutputMap()`
so Pulumi outputs are byte-identical (retype confined to the BDD layer). Checkpoint consults the
Generalist on failure (`consultDoctor`, dossier stashed even when the Then throws), logs `⚕`/`℞`
inline. **DEFERRED to Increment C:** the plan flowing into the runbook *node's* Diagnosis/Mitigation
sections (needs the shared report-node model that arrives with #2's DAG — same place the A
edge-recording work was deferred). `DoctorTest` + retyped tests; 24/24 seed-master green.

**Increment A delivered** (seed-master `controlplane/bdd` + `pipeline` + `policy`):
`RunbookRenderer` (JGiven `ScenarioJsonWriter` → `AsciiDocReportGenerator`; emits a *directory*
`target/runbook/adoc/` with `index.asciidoc`, not one file), caller-owned shared `ReportModel`
(`BootstrapStage` creates it, threads via `PipelineState.recordingInto`, renders in a `finally` so a
CRITICAL throw still renders), typed `Symptom` carried in the probe envelope, and **preview-only**
fault simulation. Key design correction made during the work: `simulate` moved from
`policy.readiness.simulate` to **`policy.preview.simulate`** in a NEW `PreviewPolicy` (separate from
`ReadinessPolicy.override`) — preview-only *by construction* (the apply path never reads the
simulate map; engine `isDryRun()` is the sole gate). R6 test (`RunbookRenderingTest`) caught TWO
real empty-runbook bugs, both fixed: (1) `finished()` was skipped on failure → now in a `finally`;
(2) standalone-played model has null className → `RunbookRenderer.normalize()` names the feature.

**Note:** DAG-edges-from-`dependsOn` was DEFERRED to Increment C — only one checkpoint exists today,
so there are no edges to draw until checkpoint #2.

**DEFERRED (rule-of-three, decided 2026-06-06):** a *contributable fault-simulator seam* — a uniform
contract by which each BDD scenario owner declares how its scenario fails under simulation. Today
the stage hardcodes `SimulatedSystemdAdapterProbe::of` (one scenario). Considered building a
`ScenarioSimulation`-style seam now; decided NOT to — same rule-of-three non-goal as the harness
generalization (only one scenario; abstracting now guesses the wrong axes). Revisit at checkpoint #2.
Operator UX is already fine: `policy.preview.simulate.<scenario>: <kind>` in `Pulumi.dev.yaml`
(shipped commented as a template); preview-only by construction (apply never reads it).

**Read first:** `docs/architecture/doctor/runbook-doctor.adoc` (the design). Migrated from `wip/` to
`docs/architecture/doctor/` at merge time ([[hub:wip-guard-hooks]] enforces that `wip/` never reaches main).

**What it is (two intertwined subsystems):**
- *Runbook* — the deliverable: a rendered `.adoc` DAG (git model) of the readiness scenarios played
  during a provisioning, with results and (on failure) diagnosis + remediation. Dynamic/on-demand;
  in preview the operator can order a *fake incident* (`policy.readiness.simulate.<scenario>`) to
  get a targeted runbook with no side effects.
- *Doctor* — the diagnosis engine consulted on scenario failure: Generalist → (deterministic
  routing) → Specialists → Prescription → RemediationPlan. Specialists read the captured snapshot
  first (cert-manager style), prescriptions are addressed to a remediation program via a typed
  catalog ref (not magic strings).

**Spec's three increments (its own A/B/C):**
- A — Runbook (shared ReportModel in PipelineState, DAG edges from existing dependsOn, AsciiDoc
  render, fake-incident ordering) — built on the EXISTING systemd-adapter checkpoint, no doctor yet.
- B — Doctor (Generalist/Specialist/Prescription/RemediationPlan; checkpoint consults on failure;
  DbusTcpSpecialist first).
- C — Checkpoint #2 (cluster-readiness) nested via @NestedSteps.

**CRITICAL cross-spec link — resolve before/while building the doctor core:** the config refactor
([[config-restructuring-state]]) declares **config missing-input remediation is the doctor's FIRST
use case** (its "Increment 2"), and the config entry gate already exists in `src/main`
(`controlplane/config/bdd/ConfigEntryGate`, asserts ready-vs-missing OUTCOME). So the doctor core
(Generalist, DomainSpecialist interface, Prescription, RemediationPlan) is shared between the two.
**OPEN QUESTION (must decide):** does the doctor core get built HERE (runbook-doctor) and config
Increment 2 consumes it, or is it a neutral shared module? The config spec leans "config is first
use case"; the runbook-doctor spec assumes the doctor is born in its Increment B. Reconcile the
numbering/ownership at the start of the work. Also: InfraDomain enum is designed to gain a
per-constant `specialist()` once the doctor types exist — that's the config↔doctor seam.

**Conventions in force:** [[hub:sequential-no-compat-workflow]] (delete old paths same change, no
compat), BDD-in-main / TDD-in-test split ([[bdd-jgiven-test-strategy]]), JGiven stages must be
non-final (byte-buddy subclasses them), build via `flox activate -- ./mvnw -pl :seed-master -am
test -DskipTests=false` (reactor, tests skipped by default), Claude may run compile/test/preview.
