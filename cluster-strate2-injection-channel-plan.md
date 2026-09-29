---
name: cluster-strate2-injection-channel-plan
description: "Plan for cluster strate-2 (fibers 6-7): the cluster-readiness scenario has NO way to receive what it needs sown into it — ClusterBddScenarios.run() takes zero params, hardcodes kubeconfig + empty controllers. Fiber 7a (give the scenario a seed to grow from) comes BEFORE fiber 6 (the real logic). The poll becomes a provided-state the scenario reads. Vocabulary: seed / sow / scion / graft, NOT cross-world/realm."
metadata:
  type: project
---

**Where strate-1 left it (2026-07-09):** the host verifier `ClusterBootstrapReadinessVerifier` is
cluster-port-only (fibers 1-4 shipped). The descent (fibers 5→6→7) is strate-2. This plan is the
result of scanning the actual wiring before coding — decide on concrete, not projection. Vocabulary is
the shipped register: the host SOWS a seed, it grows into a SCION (the scenario played in the OSGi
world), the scion is GRAFTED onto the host runbook at its ROOTSTOCK step. See
[[foundations-before-domain-migration]] and the seed-spec / seed-broker-spec.

## The blocker the scan found: the scenario has nothing sown INTO it

`ClusterBddScenarios.run()` (the entry that grows the scion) takes ZERO parameters. The scenario
(`ClusterReadinessScenario`) resolves its contact from the bundle registry, and HARDCODES the rest —
`kubeconfig() = Path.of("/srv/host/kubeconfig")` (fixed marker), `controllers = List.of()` (empty →
vacuously ready). So the host cannot pass in what a real readiness run needs: the `ReadinessInput` and
the projected `List<ControllerRef>`. The scenario is MORE PRIMITIVE than the seed host-side path, which
already has a full seeding channel.

## The pattern to reuse: HostSeeder (host-side, already shipped for the seed)

Host-side, `ClusterSeedTopic` seeds its scion via `HostSeeder` (seed-master/controlplane/bdd/): the
driver puts what the scenario needs into the JUnit session store (keys under `HostSeeder.NS`:
HOST_FACTS, CONNECTION, PROBES, CLUSTER_PROBE, RUN_MODEL, OUTPUTS_SINK); `HostSeeder`
(TestInstancePostProcessor, declared BEFORE JGivenExtension) reads the store and pushes ONE
`StageContext` carrier into the value-DAG via `executor.readScenarioState(carrier)`; the phases resolve
their `@ExpectedScenarioState` from it. RUN_MODEL/OUTPUTS_SINK use the inject-the-holder idiom (driver
holds an AtomicReference, the run fills it, the driver reads — no harvest-back, no static). This is the
typed seeding channel the seed already has; the cluster scion needs the same, scaled to its own seed.

## The plan — fiber 7 (the seeding channel) BEFORE fiber 6 (the logic)

**Fiber 7a — let the host sow what the cluster scion grows from (the HostSeeder twin).**

- `ClusterBddScenarios.run(...)` takes the seed (a `ReadinessInput` + the projected
  `List<ControllerRef>` + a poll policy — see below) and puts it in the launcher session store.
- A cluster-bdd inbound extension (the HostSeeder twin) reads the store → pushes a carrier into the
  DAG → the scenario's `Given`/`When` read `ReadinessInput`/`controllers`/`PollPolicy` as
  `@ExpectedScenarioState` instead of hardcoding them. Today `ClusterReadinessScenario` already uses
  `@ProvidedScenarioState`/`@ExpectedScenarioState` for kubeconfig/controllers/contact — the change is
  WHERE they come from (sown by the host, not hardcoded).
- What the seed carries across the seam: `ReadinessInput` (kubeconfigPath, timeout) and `ControllerRef`
  are cluster-port types (system-exported seam), so they can be seeded as live objects — unlike the
  scion's runbook coming back, which is JSON (the graft's currency, already handled by RunbookEnvelope).
  Confirm the seam export makes them safe to seed live; if not, the seed crosses as a Document.

**The poll as a PROVIDED-STATE (user idea, 2026-07-09).** Rather than bury wait loops in the scenario
(against the BDD-as-engine design — the temporal poll is meant to be a Jupiter EXTENSION, the spike's
`PollUntilReadyExtension`) OR leave them host-imperative: sow the poll POLICY as one more part of the
seed carrier. The host provides "poll with retry R until deadline D" as a provided-state; the
scenario/extension reads it and applies it around the point-in-time contact calls. The scenario stays
point-in-time (its virtue); the WAITING is a sown concern, not scenario code. This is the live form of
`PollUntilReadyExtension`, reached through the seeding channel.

**Fiber 6 — fill the 3 live gaps (needs 7a's channel first).**
Once the scenario grows from a real seed, bring the host verifier's real logic in:
1. kubeconfig: replace `!= null` with the rich NIO check (exists, non-empty, `apiVersion:`+`clusters:`)
   — the logic is in the host verifier's `waitForKubeconfigPublished` body; the point-in-time half is
   what the scion carries (the wait half is the sown poll policy).
2. controllers: use the sown projected `List<ControllerRef>` (not `List.of()`); the vacuous-empty path
   stays valid when none are required.
3. systemd ordering: the phase-0 systemd gate stays HOST (fiber 2 put it in LiveClusterReadinessProbe);
   the scion does NOT gain a systemd dep — the host runs the gate BEFORE it sows the readiness scenario.
   Confirm the ordering is preserved (host gate → then sow).

**Fiber 7b — grow the scion from the host pipeline + delete the avant.**
Today `ClusterBddScenarios.run()` is grown ONLY by `ClusterReadinessScenarioInContainerTest`, never by
`ResourceCreationPipeline`. Wire the host to: project controllers (`RequiredControllers.from(policy)`),
build `ReadinessInput` from its config, run the systemd gate, then sow the cluster scenario and
`graftUnder` the returned scion runbook (ScenarioGraft). Then DELETE the host avant: the verifier's
reasoning half, `bdd/ClusterReadinessScenario`+`Stage`+`LiveClusterReadinessProbe`+`Simulated`. KEEP
host: the pulumi-irreducible `ClusterReadinessResource`, `ReadinessOutputMapper`, `VerificationResult`,
`RequiredControllers` (host projection), the broker sow, and the graft. The verifier's
`VerificationResult` factories (ready/failed/skipped, already static, host-facing) stay; only its
reasoning half becomes the scion.

## The seam is ONE, bidirectional (user correction 2026-07-09) — not a separate "channel"

Earlier framing (a seeding CHANNEL separate FROM the graft) was over-engineered. Nothing prevents
`run()` from carrying the seed IN: the crossing that grows the scion is already the seam (the reflective
`ClusterBddScenarios.run()` call through the bundle loader). It just gains a parameter. So:

- outbound (sow the seed): `ClusterBddScenarios.run(seed)` takes what the scion grows from —
  `ReadinessInput`, the projected refs, the poll policy.
- inbound (graft the scion): the grown scion's runbook comes back and is grafted onto the rootstock
  (`ScenarioGraft.graftUnder`), as today.

The HostSeeder store→DAG pattern is NOT a second mechanism — it is just HOW, once the seed has crossed,
it reaches the phases in-container (the driver puts it in the store, the inbound extension pushes it
into the value-DAG). Two constraints remain (not obstacles): (a) what the seed carries across —
`ReadinessInput`/`ControllerRef` are system-exported seam types so they cross live, else String/Document,
same currency constraint as the runbook returning; (b) the two TWINS stay conceptually distinct — the
seed is execution SETUP, the runbook is OBSERVABILITY, so `ScenarioGraft` (which speaks report-model)
does NOT carry the input; `run(seed)` sows, the graft reports. One seam, two directions, two twins.

Note the seed-BROKER `sow(wanted, seed)` is a THIRD, unrelated crossing — the Document→Document
execution verb (readiness-checkpoint → verdict / consultation) the doctor consult uses. Readiness setup
does NOT go through broker.sow; it is the seed grown by `run()`. Broker = a verb crossing; growing the
scion = playing a scenario + grafting. See [[systemd-gate-poll-to-listener]].
