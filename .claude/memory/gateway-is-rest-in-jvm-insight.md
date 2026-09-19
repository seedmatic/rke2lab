---
name: gateway-is-rest-in-jvm-insight
description: "★ INSIGHT (user, 2026-07-08, end of the fork-B session): the world-gateway model IS 'REST, in-JVM' — transport the whole REQUEST through ONE door, not shared service-interface calls; each world keeps its OWN copy of the vocabulary (no shared *Wire twins). This collapses the seam to a single request/response broker and makes the doctor's 12 frontier words INTERNAL again. The next brainstorm starts here."
metadata:
  type: feedback
---

**The insight (user, 2026-07-08):** "chacun peut garder sa propre version du vocabulaire, et on peut
transporter toute la REQUÊTE et non pas les appels de services eux-mêmes via la gateway… on ferait du
REST API mais in-JVM. C'est peut-être ça le modèle de la gateway. J'ai réinventé REST." Correct — and
the codebase is already ~90% there; what remains is finishing the RPC→REST passage.

**The three moves it names:**

1. Each world keeps ITS OWN version of the vocabulary — no shared type imposed as the crossing
   currency. Each side (de)serializes to/from its own form.
2. Transport the whole REQUEST, not per-service calls. Instead of the host crossing the seam once per
   typed verb (`authority.assess(doc)`, then `doctor.consult(doc)`), post ONE request describing what
   is wanted; the other world executes it at home.
3. REST in-JVM: `Document` is already the envelope (`domain`=host, `coordinate`=route, `payload`=JSON
   body). One door `handle(Document) → Document` replaces the shared service interfaces.

**Measured facts that confirm it (2026-07-08) — the gateway is a REST/RPC HYBRID today:**

- The seam ALREADY carries `Document`→`Document` verbs: `ReadinessAuthority.assess(Document)` and
  `ConsultingService.consult(Document)`. The signature is pure REST (envelope→envelope), but it is
  WRAPPED in shared SERVICE INTERFACES that must themselves cross the seam. That wrapping is the
  residual RPC — the only thing standing between today and full REST.
- `Document` is already the REST message: `record Document(String domain, String coordinate, String
  payload)` — host / route / body. The model exists; the service interfaces on top are the excess.
- So dropping the shared interfaces for ONE broker door would: (a) shrink the seam to the
  request/response envelope alone; (b) make the doctor's 12 frontier words
  ([[port-vocabulary-not-cross-domain-dup]]) INTERNAL to OSGi again — the host only knows "I posted a
  `readiness-checkpoint`, I got a `consultation`"; (c) dissolve the `*Wire`↔`records` twins (each world
  keeps a private (de)serialization, no shared type); (d) give the LOCAL signal that was missing
  ([[gateway-crossing-three-natures]]) — one gesture (post to the door), one visible frontier, not a
  diffuse per-type property.

**Why it fits the whole day's arc:** the user reached it after two correct hot intuitions ("traverser
en JSON" = the REST body; "on a déjà un ObjectMapper" = the REST codec). It answers the vast-vocabulary
question ([[port-vocabulary-not-cross-domain-dup]]) not by generalizing across domains but by making
the vocabulary NON-SHARED. `WorldGatewayCatalog` would become the routing table (coordinate→handler).

**Open questions for the next brainstorm (NOT decided):** who owns the single door and how it dispatches
by `coordinate` (a registry/multiplexor — cf. the multiplexor idea parked earlier in
[[cluster-seed-execution-state]]); whether the door is one OSGi service the host calls or a pair of
directional channels; how request/response correlate; whether `ReadinessAuthority`/`ConsultingService`
disappear entirely or become in-OSGi-only. Also weigh against [[world-gateway-frontier-discipline]]
(fewer shared words is MORE of that discipline, good) and the REST-cost (a dispatch layer, looser
compile-time typing at the door — the trade REST always makes).

## ATLAS-FIRST CONFRONTATION (2026-07-08, same session) — the insight is ALREADY the design-of-record

Read `docs/architecture/osgi/world-gateway-spec.adoc` + `integration-atlas.adoc` +
[[multiplexor-two-models-design]] before deciding. Three findings that reframe the whole thing:

1. **"REST in-JVM" is NOT a new idea — it is the shipped design-of-record.** The spec's title is
   *"ports are services, data crosses as validated Documents"* and its opening section is literally
   *"The mental model: REST, in our embedded model"* with the full REST correspondence table
   (`coordinate`=path, `Document`=body, JSON Schema=OpenAPI, OSGi=authority, "embedded today, remote
   tomorrow — forward-compat by construction"). So the user RE-DERIVED the design-of-record from
   memory. Strong confirmation the model is natural (it emerged twice).

2. **The multiplexor (DomainDag*) was NEVER built — it stayed design-only** (grep: zero `DomainDag*` in
   code). What shipped instead: three `Document→Document` verbs with real impls — `assess`
   (`DefaultReadinessAuthority`), `consult` (`Generalist`/`ConsultingService`), `canonicalize`
   (`ClinicalReasoning`) + their fakes. So the CODE already evolved toward Exchange-style
   request/response, not the unidirectional-data-to-Pulumi multiplexor. The insight NAMES what the code
   already became.

3. **The spec ALREADY names the tension as FUTURE work — and names THIS chantier as its trigger.** Spec
   §"Planned evolution — the per-domain roster": the multiplexor is "FUTURE, not dead", the
   OPEN-extensibility the *distributed peer-to-peer mode requires*; today `Coordinate` is a closed
   central enum and N=1 (only doctor contributes) hides the cost; the named trigger is *"right after the
   behavior-driven pipelines land (cluster, the second contributing domain)"* — i.e. exactly the fork-B
   chantier in flight.

**THE decision the next brainstorm must take (atlas-first done, decision deferred by the user):** the
spec still keeps N SERVICE INTERFACES (`ConsultingService`, `ReadinessAuthority`, `InterventionIntake`),
each `Document→Document`, AND separately envisions the multiplexor as a data-egress roster of
`DomainDagMapper`. The user's Exchange insight UNIFIES the two: a `consult` is an Exchange
`readiness-checkpoint→consultation`; a data egress is an Exchange `domain-facts→pulumi-output`. Same
shape. So: does ONE broker door (dispatch by coordinate) SUBSUME both the N service-verbs AND the
multiplexor-roster, closing the `ConsultingService` "OPEN knot" [[multiplexor-two-models-design]] left
explicitly deferred (in-process-vs-across-runs, 5 sites)? The `world-broker` rename rides on this.
Weigh: subsume = one model for the whole frontier (simplest, closes the knot) vs the spec's current
split (N typed verbs = stronger compile-time typing per verb, the REST-cost trade).

## Two outputs, disjoint channels — the ReportModel is NOT a resource/output input

User Q 2026-07-08: "what must the host know of the ReportModel to materialize resources/outputs?" —
ANSWER: nothing.

Verified by grep: NO resource/output/pulumi component reads `ReportModel`/`ScenarioModel`/`StepModel`
(empty). What feeds Pulumi is a PROJECTED FACT — `VerificationResult` (flat record: `readinessEnabled`,
`apiReady`, `handoffReady`, `bootstrapStatus`), the "already-played readiness result"
(`ResourcesStage` javadoc: "the pipeline never plays a checkpoint"). The `ReportModel` is touched only
by 5 narration-channel classes (`ClusterSeedTopic`, `RunbookRenderer`, `HostSeeder`, `ClusterSeedRun`,
`ClusterSeedScenario`); `RunbookRenderer` imports AsciiDoc, zero `com.pulumi`. The two channels are
DISJOINT in code.

So a scenario has TWO outputs, and only ONE is a "result" in the REST sense:

- the VERDICT (`ReadinessVerdict`/projected `VerificationResult`) = the REST RESPONSE BODY — flat,
  typed, already a frontier word; it drives resources/outputs (the machine channel).
- the `ReportModel` = an OBSERVABILITY ARTIFACT — narration the host GRAFTS into the operator runbook
  and renders to AsciiDoc; the host never INTERPRETS it to act (the human channel).

**This settles a whiteboard open question:** an `Exchange`'s RESPONSE is the verdict, NOT the
ReportModel. The ReportModel rides a SEPARATE observability channel (serialized, grafted, never read
to decide). The host never needs to understand the ReportModel to act — it agrafts-and-renders it, and
acts only on the verdict. Exactly REST's "status/headers for the machine vs a log body for the human".
Corollary for 2b: the inbound/outbound envelope carries the verdict as the response; the ScenarioModel
graft is a parallel concern, not the Exchange payload.

## The loop closes — a PROVISIONING broker, coherent with "seed" (user, 2026-07-08, session close)

The user's synthesis: this broker has a vocabulary SPECIFIC to provisioning — it speaks about
resources and how they are provisioned — and that is coherent with the program's name, `seed-master`
(la graine). "On boucle, tout est cohérent." The rigor under the feeling is a TWO-LAYER structure:

- TRANSPORT layer = neutral/generic — `Document`, `coordinate`, `handle(request)→response`.
  Reusable, forward-compat (Felix remote → same contract, HTTP transport). Speaks of nothing specific.
  This is the 6-word generic core ([[port-vocabulary-not-cross-domain-dup]]) — kept minimal, correctly.
- VOCABULARY layer = provisioning-specific — `readiness-checkpoint`, `resource`, `verdict`, `outputs`.
  The coordinate catalog IS the domain. This is the 12+ domain-loaded words — and they are RICH BY
  RIGHT, not by bloat: a provisioning broker MUST speak resources/readiness/verdict; that richness is
  its meaning, not fat. This re-frames and CLOSES the "vast vocabulary" worry: keep the TRANSPORT thin
  (done), let the DOMAIN vocabulary be as rich as seeding requires.

The coherence runs the whole stack: `seed-master` (the program = the seed) → drives resource
provisioning → through a broker whose vocabulary speaks resources/readiness/verdict → the verdict
drives the Pulumi outputs (what gets sown). The name `seed` is not decorative — the whole system is a
sower; the broker is the exchange organ OF a seed, and it speaks provisioning because provisioning
master IS its purpose. Everything ties together: the two frontiers (OSGi broker + Pulumi ACL), the two
channels (verdict=machine, ReportModel=human), and the seeding name all cohere.

## Rename settled: `seed-broker` (NOT `world-broker`) — the module is a provisioning broker, be honest

First-pass reasoning said `world-broker` (generic organ, seed-specific cargo). FALSIFIED by the code
(2026-07-08): the module is NOT generic in practice. Verified — (a) consumers are ONLY seeding
(seed-master) + doctor; nothing generic uses it. (b) the ONE bundle FUSES both layers: neutral
transport (`Document`, `Coordinate`, `WireEnum`, `DocumentContract`) sits in the SAME package as the
provisioning vocabulary (`Checkpoint`, `ReadinessCheckpoint`, `ReadinessVerdict`, `Consultation`,
`Patient`, `SymptomKind`, `Action`). So calling it "generic" is a pretension the code does not support
— it is already a seeding broker wearing a generic name.

User decision: rename `world-gateway` → **`seed-broker`** — the honest name for what it IS. One module,
provisioning vocabulary assumed (the whole system is `seed-master`, la graine — the broker sows). The
two-layer split (a reusable neutral `broker-core` transport vs the seed-specific coordinate catalog) is
NOT materialized now — it stays a documented FUTURE split point, triggered by a real second
non-seeding consumer (rule-of-three; same "no abstraction at N=1" discipline applied to the
multiplexor). Rename cost: ~10 bnd + imports across the tree; ride it on the subsume-vs-extend broker
decision, do it in one atomic pass (delete old name entirely, per CLAUDE.md no-compat-shim rule).
`gateway-document-codec` renames alongside → `seed-broker-codec` (or fold into the broker module).

## THE KEYSTONE reframe (user, 2026-07-08): crossing worlds is a DETAIL; the client just sows a seed

The load-bearing shift, stronger than the name: *"le fait qu'on change de monde pour semer une graine
est un détail"* + *"j'ai une graine et je veux la semer, je m'adresse au seed-broker pour qu'il la sème
au bon endroit dans mon terrain."* We spent the day treating the host↔OSGi seam as THE problem (three
natures, REST, seams to system-export). From the CLIENT's view that is all broker-internal plumbing.
The client does not say "I cross a classloader frontier" — it says *"sow this seed."* Whether the broker
does that by switching worlds (Felix), staying put, or tomorrow HTTP-remote — the client does not care.
That is REST's core promise: the client is blind to the server's implementation.

The metaphor completes the model (each word now has a place):

- the SEED = the request (`Document` — what I want sown)
- the SEED-BROKER = the exchange organ (`handle` — I address it, full stop)
- "at the right place" = dispatch by `coordinate` (the broker knows WHERE to route)
- the TERRAIN = the provisioned-resource world (Pulumi / master)
- "it sows it" = execution — of which the world-crossing is only an INTERNAL detail

Design consequence: the open question "who owns the door / how it dispatches" is broker-internal,
INVISIBLE to the client. The client has ONE relationship — hand a seed, the broker does the rest. The
"world change" drops a rank: from a visible architecture decision to broker-internal mechanics. This is
the real payoff of the REST insight — it HIDES the seam instead of making it omnipresent, the exact
remedy to the morning's "three natures / missing local signal" pain
([[gateway-crossing-three-natures]]): there is now ONE local signal (address the broker with a seed),
and the crossing is behind it. Keystone for the whole model — above the naming.

## Consequence (user, 2026-07-08): ONE seam only — the `-port`s stop being seams, become domain APIs

The keystone forces a reclassification the user named: reduce the `type=seam` modules to the
seed-broker ALONE. Today (verified) TEN modules are `type=seam` — the 8 domain `-port`s
(cluster/doctor/systemd/incus/netplan/manifests/bbox/auth) + `pipeline-port` + `world-gateway`. All
cross the host↔OSGi frontier. The conceived form: only `seed-broker` is a seam (the one thing that
crosses the WORLDS). The `-port`s REMAIN (structural, structuring) but CHANGE NATURE — from
inter-WORLD seams to a domain's external API, an inter-DOMAIN vocabulary consumed only inside the OSGi
world (how one domain exposes itself to the others).

Why they are seams today = the LEAK the keystone closes: each `-port` is consumed host-side now
(measured: doctor-port 9 host files, manifests-port 8, bbox-port 8, cluster-port 6, systemd/incus 4,
netplan 2, auth 1) BECAUSE the logic still lives host-side (readiness, consult, resource stages). Once
the client speaks only to the broker, those host imports of `-port` vanish — the `-port` reverts to
inter-domain-only. So there is a NET, VERIFIABLE criterion: **a `-port` stops being `type=seam` when
zero host files import it** (the host-side count → 0). Gate-able (REALM_BOUNDARY already knows realms);
a plan can track it per-port as a shrinking count. This is the structural end-state the two-worlds
architecture was always reaching for: the frontier is ONE door (seed-broker), everything else is
inter-domain wiring that never leaves OSGi.

## A scenario's vocabulary hygiene — the broker's absence makes a domain scenario do frontier work

User Q (2026-07-08): a scenario running INSIDE its domain should speak ONE nature of vocabulary — its
own. Which scenario speaks a foreign nature? Classified every import of each `*Scenario` by nature
(INTRA-domain / other-domain / FRONTIER). Contrast, measured:

- `DoctorScenario` (healthy intra-domain): 12 types of ITS nature (doctor.internal/records/spi) + 1
  frontier word (`Patient`, the identity key). Homogeneous.
- `ClusterReadinessScenario` (cluster-bdd, the one I wrote in 2a-consult): only 3 of its own nature
  (`cluster.port`) but EIGHT frontier words (`Document`, `Coordinate`, `ObservationWire`, `SymptomKind`,
  `Checkpoint`, `ReadinessCheckpoint`, `Domain`, `DocumentCodec`) + 1 other-domain (`doctor.port`).

The real signal is NOT the `doctor.port` import (an inter-domain relation, legitimate — the user
accepts multi-domain scenarios and inter-domain wiring as natural). It is the 8 FRONTIER words: the
scene builds the crossing envelope BY HAND — assembles a `ReadinessCheckpoint` of `ObservationWire`s,
encodes it to a `Document` via `DocumentCodec`, names `Coordinate`/`Domain`. The scene is doing the
BROKER's job manually, because the broker does not exist yet. It mixes two natures: "read the cluster's
readiness" (its own) and "package a message to cross to the doctor" (frontier).

This VALIDATES the broker from a new angle — scenario vocabulary hygiene — and names a concrete debt in
the 2a-consult code: once the broker exists, it REMOVES the 8 frontier words from the scene. The scene
would say only "consult the doctor on this failure" (an intent); the broker owns the
envelope/coordinate/codec. A domain scenario would speak its domain again — no `ObservationWire`/
`Document`/`DocumentCodec` inside a domain scene. Criterion for a plan: a healthy intra-domain
`*Scenario` imports its own `-port` + at most identity/intent frontier words, NOT the envelope
machinery. Track it the way the host-import count tracks the seam shedding.

## A `seed-bdd` home for the composer (user, 2026-07-08): -bdd = a scenario HOME, per world

Follows the scenario-hygiene finding. Mapped the 5 scenarios by nature: `ClusterReadinessScenario`
(cluster-bdd) and `DoctorScenario` (doctor-core-test) are DOMAIN scenarios; the two host-side
"avant" ones (`SystemdAdapterScenario`, `ClusterReadinessScenario` in seed-master) are domain scenarios
MISPLACED host-side (transitional, to descend into their `X-bdd`); and `ClusterSeedScenario` is NOT a
domain scenario — it is the COMPOSER (orchestrates the 7 phases; grep: imports ZERO pulumi/port/gateway,
pure `@ScenarioStage` composition). The composer today is buried in `exec/seed-master/controlplane/bdd/`
mixed with host stages and the misplaced domain scenarios — it has no home that says its nature.

User's call (dogfooding + discoverability, "on est dans foundations et on applique le dogfooding —
avoir un seed-bdd ne me choque pas, on saura où chercher"): create a `seed-bdd` home for the composer +
the broker/graft wiring. Then the rule is uniform — EVERY scenario has an `X-bdd` where you find it.

The distinction to NOT blur: `cluster-bdd` is an OSGi bundle played IN-CONTAINER; `seed-bdd` is HOST
(the composer drives Pulumi via its stages — host-irreducible, `grep pulumi osgi/ == 0` forbids it in
OSGi). Same `-bdd` suffix, two execution natures. So `-bdd` gains a uniform meaning: *a scenario HOME*,
declined per world (`cluster-bdd` in-container / `seed-bdd` host) — exactly as `*Scenario` names the
NATURE not the mechanism ([[cluster-seed-execution-state]] placement rule). seed-bdd holds: the composer
`ClusterSeedScenario` + broker-post/graft wiring; nothing else — the misplaced domain scenarios go home
to their own `X-bdd`. A plan tracks it as: extract seed-bdd, descend the 2 host-side domain scenarios.

## The germination cascade — the broker is NOT symmetric (user, 2026-07-08)

Asked whether the broker-gardener could route a seed freely to a HOST-side OR an OSGi-side handler
(symmetry). User's answer: for seed-master, NO — and the honest image is a generative cascade:
*"une graine semée par l'acteur Pulumi fait éclore une plante qui essaime de nouvelles graines qui
elles vont éclore dans le monde OSGi"* — then the technical anchor: *scenario → sub-scenario*.

- Pulumi (driving actor, C1) sows the FIRST seed = launches the ROOT scenario, host-side.
- it germinates into a host plant = the root plays its host phases (preflight/bbox/incus, Pulumi-native).
- the plant SEEDS ANEW = a phase opens a SUB-SCENARIO (readiness, systemd-adapter checkpoints).
- those new seeds bloom in OSGi = the sub-scenario is played IN-CONTAINER, its `ScenarioModel` grafts
  back (`addNestedStep`).

So symmetry is REJECTED, and here is the why (worth writing so it isn't re-litigated): the seed has ONE
origin (host-born, because Pulumi the driving actor lives host-side), and the SAME seed does not cross —
the host plant produces a SECOND generation, and it is the offspring that reach OSGi. Directional,
nothing flows back up but the grafted model. This is NOT in tension with the spec's existing
"OSGi holds the authority (the asymmetry)" section — it is the same directionality on the type axis:
the OSGi bloom owns what it grows, the host owns the seed's birth. The broker-gardener has several
parcels IN PRINCIPLE (host/OSGi/remote), but seed-master's seed is not freely routable — born host,
offspring bloom OSGi. Documented in world-gateway-spec.adoc § "The germination cascade". See
[[cluster-seed-execution-state]] (the scenario→sub-scenario graft is fork B's 2b).

## ★ RESUME POINT (2026-07-08, post-compaction) — DESIGN done, CODE next

The vision is fully DOCUMENTED and atlas-VALIDATED (7 commits this session, tree clean, on
`feature/cluster-seed-scenario`): `87722d2d0`+`953612b25` (fork B increment 2a — cluster-readiness plays
in-container + consults the doctor itself), then the doc arc `3fd85e6cb` (seed-broker vision in
world-gateway-spec) → `e0945ec71` (specs re-cut BY NATURE: pipeline-spec→bdd/bdd.adoc,
host-pipeline→atlas/seed.adoc) → `42ec9800a` (seed-orchestration prose → new osgi/seed-spec.adoc) →
`9091746bc` (germination cascade, symmetry rejected) → `e38aa2251` (atlas L0 additivity verdict — the
broker is a foundation integrating worlds + domains, additive-with-named-erasure).

What is DONE = the conceived design + its atlas additivity certificate. What is NOT done = the CODE
(the atlas verdict certifies the design is additive, NOT that it is built). The realization chantiers,
each with a verifiable criterion already established this session — an ORDER still to be cadré with the
user (we were about to when compaction was called):

1. Finish fork B **2b** (the host graft) — the ONE piece of live code half-started: host plays the
   sub-scenario in-container via the front-door, symmetric inbound envelope, grafts the ScenarioModel
   (`addNestedStep` + FAILED→SKIPPED). BLOCKED in PROD by the frontier hole (jGiven flat, cluster-bdd
   not staged); provable in the cluster-bdd TEST (real Felix). See [[cluster-seed-execution-state]].
2. **seed-bdd** home for the composer `ClusterSeedScenario` (host `-bdd`); descend the 2 misplaced
   host-side domain scenarios into their own `-bdd`. Criterion: every scenario in an `X-bdd`.
3. The **broker door** — collapse the N `Document→Document` service interfaces (ConsultingService,
   ReadinessAuthority, InterventionIntake) into one `handle(Document)→Document` dispatched by
   coordinate; the verbs become OSGi-internal handlers. Closes the ConsultingService in-process knot.
4. **`-port`s shed `type=seam`** — criterion: a `-port` leaves the seam when zero host files import it
   (measured baseline: doctor 9, manifests 8, bbox 8, cluster 6, systemd/incus 4, netplan 2, auth 1).
5. **Rename** `world-gateway → seed-broker` (+ `gateway-document-codec`) — atomic pass, ~10 bnd +
   imports, no compat shim. Ride it on the door work.
6. A scenario's vocabulary hygiene: a domain scene stops importing the envelope machinery
   (`Document`/`ObservationWire`/`DocumentCodec`) once the broker exists — the 2a-consult code is the
   debt this fixes.

Also parked: the `pipeline-spec` re-cut is DONE, but [[pipeline-spec-recut-plan]] may hold residual
notes. [[specs-decompose-like-modules]] = the doc-decomposition principle (by nature, not 1:1 modules).
Dispatch owner: leaning multiplexor, MUST re-confront it to the Exchange needs (it predates them). See
[[multiplexor-two-models-design]] [[cluster-seed-execution-state]] [[gateway-crossing-three-natures]]
[[port-vocabulary-not-cross-domain-dup]] [[document-seam-cannot-expose-jackson-jsonnode]].
