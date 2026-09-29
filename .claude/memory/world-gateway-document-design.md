---
name: world-gateway-document-design
description: "DESIGN CONVERGED 2026-06-27 (NOT built). The host<->OSGi gateway re-designed after a 5-angle code review found the multiplexor spec covers only 1 of 3 real leaks. Principle: ports describe SERVICES not data; everything crosses as a Document (YAML) validated by a per-document-type JSON Schema; OSGi holds authority + owns all typing; the host TRANSPORTS (no doctor types host-side); the boot pipeline is re-decomposed GROUND/GATEWAY/APPLY with GATEWAY framed by the boot-run-close. Supersedes the stale parts of multiplexor-spec + world-boundary-spec."
metadata:
  node_type: memory
  type: project
---

## Why we reopened the design (the trigger)

`pulumi preview` crashed `NoClassDefFoundError doctor.records.Severity` once doctor-records went
`type=record` (bundle-only, shade-excluded). A 5-angle code review of the post-OSGi/DS-alignment
codebase found the host↔OSGi boundary has **THREE leaks**, and the existing specs
(`multiplexor-spec.adoc`, `world-boundary-spec.adoc`, 24-25 June) cover only one:

1. **The seam SPEAKS records.** `doctor-port` AND `cluster-port` (type=seam, system-exported)
   `Import-Package: doctor.records` + `uses:=`. 19 record types in seam method signatures
   (`consult(Symptom,Observation)`, `admit(Patient)`…). The port contract REQUIRES the host to load
   a `type=record` class → contradiction. NOT covered by the spec.
2. **The host REASONS on records.** 19 host files. `ControlplanePolicy.from()` does `Severity.parse()`
   AT BOOT (the crash); `SystemdAdapterStage:176` branches `== Severity.CRITICAL`; probes build
   `Observation.failed(Symptom.X)`. parse/branch/produce — NOT path-addressing. NOT covered.
3. **Egress self-serialization.** 14 files `toOutputMap`/`OUTPUT_KEY`/`fromOutputMap` in `osgi/`.
   This is the ONLY leak the multiplexor spec addresses.

Verdict: "step 5 = preview green" was FALSE. The multiplexor (egress) alone leaves the boot crash
(leak #2) and the seam contradiction (leak #1) open. So we re-designed, with the user.

## The converged design (validated 2026-06-27, in `.claude/claude-preview.adoc` diagrams)

1. **Ports describe SERVICES, not data.** Each `-port` (type=seam) exposes VERBS; signatures take/return
   `Document`, never a data type. `consult(Symptom,Observation)→RemediationPlan` becomes
   `consult(Document)→Document`. The seam stops being 61 exported types; it becomes a handful of
   service interfaces.
2. **OSGi holds the authority and knows the domains.** All rich typing + reasoning live OSGi-side.
   The host **TRANSPORTS** (decided: option map-B, asymmetric): it produces the ingress Document from
   its host-native facts (pulumi config, raw probe output), reads ONE control field from the response
   (continue/stop), and forwards the egress Document opaquely to pulumi. The host holds NO doctor
   type and NO doctor mapper. This kills leak #2 at the root (no `== Severity.CRITICAL` host-side).
3. **Contract = a JSON Schema PER DOCUMENT TYPE (coordinate), owned by OSGi.** Zero shared Java type;
   the only shared fixed point is the meta-schema (JSON Schema draft standard, from the lib).
   **Validation is GRADUATED (refined after first writing this memo):** the BUILD is the load-bearing
   check (mapper unit tests round-trip Document↔schema + a 4th staging gate asserts mapper↔schema
   concord and schema↔meta-schema) — sufficient because we build on ONE codebase (host + OSGi shipped
   together, schemas cannot drift in version). RUNTIME validation is DESIGNED but **YAGNI/deferred**:
   OSGi side = an optional validation *fragment* attaching to a domain bundle; host side = OFF by
   default, ON only when OSGi is configured *remote*. It earns its keep only when host and OSGi become
   separately deployable (remote split, or old host + newer META-INF/bundles → semver mismatch). The
   mental model that grounds all this: **"REST, but in our embedded model"** — coordinate = path,
   Document = body, schema = OpenAPI; embedded today, remote later, same contract. Introspection /
   schema-crossing-at-boot also deferred (same-codebase: the host knows the offered services at build).
   (Rejected: contrat-A opaque = silent drift; contrat-B shared wire DTO = reintroduces a shared data
   type; val-A OSGi-only-validation = leaves host emitting unchecked docs onto a future wire.)
4. **Hierarchy — two different units, both instincts were right about different things:** the
   **DOMAIN** is the ownership unit (owns its `DomainDagMapper` `@Component`, collected by
   `@Reference List`, the proven DS mechanism); the **DOCUMENT TYPE** (`coordinate`) is the contract
   unit (one JSON Schema each, what the validator sees). A domain owns N document types. Matches
   `Document(domain, coordinate, payload)`: domain = ownership, coordinate = type = schema key.
5. **Boot pipeline re-decomposed GROUND / GATEWAY / APPLY** — by real capability, not by function;
   "we don't mix the two worlds at every level" (user). But verified on the code: GATEWAY is FRAMED
   BY the boot-run-close, not a flat sibling topic (see "verified facts" below).
6. **Sequencing:** (a) open/ingress config as Document = closes the CRASH, preview green; (b) the 2
   seam stages as `consult(Document)` + the egress multiplexor; (c) APPLY = pure host transport.

## Verified facts on the real pipeline code (2026-06-27, before writing the spec)

The pipeline has THREE nested levels, NOT three flat topics:
- L1 `ApplicationPipeline`: `environment` → `bootstrap` → `outputs`.
- L2 inside `bootstrap`: `BootstrapStage.runBootstrapPipeline()` calls `BootPipeline.embedded().during(...)`
  — boots Felix. **`BootPipeline.embedded()` owns the boot-run-close lifecycle: it closes Felix on
  return, ALWAYS (even if the tail throws).** So everything that needs the framework MUST stay under
  that one `during`. GATEWAY cannot be a first-rank sibling topic — it lives INSIDE the boot-run-close.
- L3 `BootstrapPipeline`: `admitPatient` (register MedicalRecordRegistry + InterventionLedgerWriter,
  await HealthSystem) + `resolveSystemdRuntimeStatus` (await) + `resolveClusterReadinessContact`
  (await) — the 4-crossing BURST — THEN 5 stages: preflight → bbox → incus provisioning → systemd
  adapter → bootstrap resources → collectOutputs.

**Which stages cross the seam (grepped):** only **2 of 5** — `SystemdAdapterStage` (35 doctor/OSGi
refs) and `ResourcesStage`/bootstrap-resources (6). `PreflightStage` = 0. `bbox` and `incus
provisioning` are host-pure (incus = a host edge that provisions a VM, nothing to say to OSGi). So
GATEWAY is NOT a contiguous slice: it is the `open` (ingress config + register/await burst) + the 2
seam stages (`consult(Document)`) + the egress. The 3 host-pure stages run in the SAME boot-run-close
(they need Felix open so the 2 seam stages can consult) but don't talk to OSGi themselves.

**Runbook:** owned by `BootstrapStage`, a `ReportModel` + `ConsultationLog`, rendered by
`RunbookRenderer` in the `finally` of the boot-run-close tail — BEFORE Felix closes, so a CRITICAL
stop still produces a runbook. Stays there, unchanged, in the new decomposition.

## Real call sites / counts (corrected vs stale spec)

- `consult()` live sites: **2** (SystemdAdapterStage:198, ClusterReadinessStage:226) — spec said 5.
- `toOutputMap|OUTPUT_KEY|fromOutputMap` in `osgi/` src/main: **~14** (CONCENTRATION CHECK baseline,
  target 0). 10 `toOutputMap` + 2 `OUTPUT_KEY` in doctor-records, 3 `fromOutputMap` in the 4 `*Reader`
  classes in doctor-port.
- `import com.pulumi` in `osgi/`: **0** (type-level invariant holds; only the WORD "pulumi" lingers
  in javadoc of Patient/StackCoordinate).
- doctor-records: ~35 exported types; ~19 appear in seam (`-port`) signatures, ~14 are internal-only.
  StackCoordinate still in doctor-records (spec wanted it host-side); Patient javadoc still says
  "a Pulumi stack".

## What is BUILT vs NOT (the multiplexor roadmap, steps 1-6)

BUILT (committed): step 1 doctor-records `type=record` + purity guard; step 2 HealthSystem
@Component; step 3 specialists distributed + host switch to OSGi HealthSystem; step 4 assess/prescribe
split. NOT built: DomainDagMapper, DomainDagMultiplexor, DomainDagAdapter, Document contract (zero
files). The converged design REVISES steps 5-6: it is no longer "egress-only" — it adds the ingress
endpoints (environment config, bootstrap observation) the multiplexor spec never had.

## PIVOT (2026-06-27, second session): gate-first, not crash-first

A 2nd session (post-compaction) re-investigated the staging gate (systematic-debugging) and the
sequencing CHANGED. Root cause of the missed drift, CONFIRMED from code: the 3 gates each check a
bundle's EXPORTED surface (RecordPurity verifies doctor-records exports only records → GREEN, rightly).
NONE checks the CONSUMER direction. The invariant "nothing flat references a bundle-only type" was a
RUNTIME notion (deriveSystemExports) only — never a build gate. So the build compiled and the crash
surfaced at runtime. Also confirmed: doctor-port AND cluster-port built manifests carry
`Import-Package: io.seedmatic.rke2lab.doctor.records` (leak #1, manifest-visible); the host crash
(`ControlplanePolicy.from`→`Severity.parse`) is leak #2, NOT manifest-visible (the host is the FLAT
jar, no Import-Package — needs BYTECODE/ASM to see).

Decisions converged WITH the user (figured in claude-preview each round, recos-with-questions):
- **Sequencing reversed**: do NOT "restore visibility / re-shade" (= disabling the contract). Instead
  GATE-FIRST: lay the guard in WARN → migrate incrementally (worklist shrinks) → flip ERROR at the end.
  doctor-records STAYS bundle-only throughout; nothing re-exposed.
- **4th gate = `REALM_BOUNDARY`** (user's "ASM côté host", extended to BOTH realms on the user's "jouer
  la gate côté OSGi pour être sûr qu'on ne fuit pas déjà dans le monde OSGi pur"). One ASM law,
  TWO-REALM model: a class may reference only types reachable in its OWN classloader realm. FLAT realm
  (exec classes + flat tail + seams, system-exported) → carries the worklist; BUNDLE realm (per
  isDomain bundle) → proves OSGi-pure world is clean (comes back GREEN for our drift: bnd derives
  Import-Package from bytecode). **Auto-attribution** (user's point): the realm a violation falls in IS
  the label — flat = host/seam leak, bundle = OSGi-internal leak. Reads METHOD BODIES (not SKIP_CODE —
  the drift is an invokestatic in a body) + scans the exec's OWN `target/classes` (the extension never
  read its own project output before, only resolved dep jars). Jurisdiction = our root (isOurs). Only
  `org.ow2.asm:asm` CORE on the ext classpath (no asm-commons) → core ClassVisitor + SignatureReader.
- **`Gate` → `StagingGate`** rename (user: "gate" is overloaded — readiness gates
  SeedSystemdAdapterEndpointGate/ConfigEntryGate/ManifestUpdateGate, network gateways). New value =
  `StagingGate.REALM_BOUNDARY`.
- **`production` → `live`** rename (user: no env tiers, only baremetal hosts nikopol+bioskop;
  "production" always means real-run-vs-test-fake; aligns with existing `liveProbe` field). Uniform,
  lands first as Task 0.
- **Two plans**: the gate's WARN output IS the migration worklist, so don't guess the ~19 host edits.
  Plan 1 = gate + 2 renames (WRITTEN). Plan 2 = the Document migration, written FROM the worklist.

## STATE (2026-06-27, written to FS — NOT committed) — resume exactly here

Specs from session 1 still on FS (world-gateway-spec, deletions, atlas, redirections — see git status).
NEW/UPDATED this session, all uncommitted:
- WRITTEN `wip/plans/2026-06-27-realm-boundary-gate.md` — Plan 1, 5 tasks (T0 rename live, T1 rename
  Gate→StagingGate, T2 RealmBoundary+ReferencedTypes law, T3 wire into enforceGates over both realms +
  read exec target/classes, T4 govern WARN + POM deps). Self-reviewed: placeholder-clean, type-
  consistent; fixed a real bug (enforceGates needs MavenSession threaded in — its sig today is
  `(List<ResolvedBundle>, Path)`, no session). Verified facts baked in: doctor-port HAS a package-info
  (just @Version) and does NOT dep domain-annotations (T4 adds both); seed-master has NO package-info
  and NO dep (T4 adds both); cluster-port already deps + poses SPEC_COVERAGE=WARN.
- UPDATED `docs/architecture/osgi/staging-gates-governance-spec.adoc` — now FOUR gates; Gate→StagingGate
  throughout; added the REALM_BOUNDARY row + a detailed two-realm/auto-attribution/reads-bodies+exec-
  classes subsection; C4 diagram gained RealmBoundary; fail-at-end notes the WARN→ERROR lifecycle.
- UPDATED `docs/architecture/osgi/seed-broker-spec.adoc` — invariant now names REALM_BOUNDARY as the
  load-bearing guard (greps demoted to eyeballs); Sequencing rewritten gate-first/flip-last + the
  "two plans" note; Related-docs link to the governance spec.
- `.claude/claude-preview.adoc` — the gate-design figures (scratch).

NEXT, in order:
1. (optional) user re-reads Plan 1 + the two updated specs; amend if needed. Per writing-plans, then
   offer execution: subagent-driven (recommended) vs inline.
2. EXECUTE Plan 1 (each task green + commit). Its T4 step-5/6 PRINTS the worklist.
3. `writing-plans` for Plan 2 (the Document migration), written FROM that worklist:
   (a) ingress `environment` config as Document (host stops Severity.parse); (b) 2 seam stages →
   consult(Document) + drop doctor.records import + egress multiplexor (DomainDagMapper @Component +
   DomainDagMultiplexor as DomainDagSource seam + host DomainDagAdapter) + delete 4 *Reader/toOutputMap;
   (c) APPLY host transport; (d) flip REALM_BOUNDARY to ERROR; (e) schema build check. Runtime validation
   = YAGNI.
4. THEN commit the whole checkpoint.
Note: cdk8s-carrier staging fix (376e7d95 + 9e57ba82) ALREADY committed session 1 — done, separate.

See [[multiplexor-two-models-design]] (the prior, now-revised design) [[cdk8s-carrier-flat-jar-pattern]]
[[maven-build-cache-and-staging-verify]] [[options-always-as-c4-diagrams]] [[hub:diagram-preview-file]]
[[object-graph-navigability-principle]].
