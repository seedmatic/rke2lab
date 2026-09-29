---
name: foundations-before-domain-migration
description: "Load-bearing sequencing principle (user, 2026-07-09): reach the TARGET on foundations + runtime FIRST, then attack the business/domain migration. Mixing the two was the flaw — every cluster attempt hit a foundation gap mid-domain-migration. Two strata, not one continuum."
metadata:
  type: feedback
---

**User insight (2026-07-09):** "il faut travailler sur les fondations et le runtime, arriver à notre
cible, avant de s'attaquer à la partie métier. c'est ce qui nous manquait dans notre approche — on
mélangeait les deux."

**Why it matters:** the seed-broker / BDD migration kept feeling problematic on cluster because we
attacked a DOMAIN (cluster) while its FOUNDATION was still incomplete — so we hit plumbing gaps
(jGiven two-path, pipeline-port's three conflated natures, the verifier's host anchors) in the middle
of business work, and the two concerns contaminated each other. The fix is to STRATIFY:

- **Strate 1 — foundations + runtime → reach target FIRST.** scenario-engine complete (broker + graft
  SHIPPED); jGiven reduced to ONE path (kill the redundant pipeline-jgiven wrap — scenario-engine
  already provides jGiven at runtime-scope, the system capability); synthesis-port extracted
  (Topic.Execution out of pipeline-port); pipeline-port reduced to only what dies WITH the seed
  (Topic.Checkpoint/Pipeline). Plus the domain-agnostic verifier cuts (see cluster scan below,
  fibers 1-5). All mechanical, each removes one import, independently safe.
- **Strate 2 — the business/domain migration.** cluster then the other domains, RESTING on a stable
  foundation. Only fibers 6-7 below. Never started until strate 1 reaches target.

**How to apply:** before touching a domain migration, ask "is the foundation it rests on at target?"
If not, finish the foundation first. Do NOT interleave a foundation cut with a domain cut in the same
increment. This supersedes the plan's ordering where cluster (chantier 2) came right after the graft —
the pipeline/jGiven/synthesis foundation work now comes BEFORE cluster. See
[[pipeline-migration-strategy-revised]] [[pipeline-modules-destined-to-disappear]].

## jGiven two-path (the first foundation cut, user-chosen 2026-07-09)

jGiven reaches modules by TWO paths — the redundancy to kill:
- `pipeline-jgiven` (BSN io.seedmatic.rke2lab.jgiven.wrap): wraps jgiven-core+junit5 2.0.3 as an OSGi
  bundle, `Export-Package: com.tngtech.jgiven.*`. Test-scope wrap.
- `scenario-engine`: carries jgiven-junit5 at runtime-scope (the dogfooding promotion) — the system
  capability now.

Measured dependents (2026-07-09):
- take BOTH pipeline-jgiven AND scenario-engine: `cluster-bdd`, `pipeline-testkit`.
- take pipeline-jgiven only: `doctor-core-test`, `manifests-core-test`.
- take raw jgiven-core/junit5: `seed-master` (+ scenario-engine), `pipeline-probe-test`.
- pipeline-port itself does NOT depend on jGiven (domain-annotations + slf4j only) — the port is clean;
  jGiven lives in the sibling pipeline-jgiven/testkit/probe modules.
Target: modules get jGiven via scenario-engine (the one system capability); delete pipeline-jgiven.
`Topic.Checkpoint` (the jGiven-playing nature) has ONLY ONE prod user: `ClusterSeedTopic` (seed-master)
— so Checkpoint dies WITH the cluster migration, it is not a separate prep step.

## CLUSTER SCAN (2026-07-09) — the tangle mapped, the 7 fibers to cut

The cluster domain (osgi/domains/cluster/*) is ALREADY Pulumi-blind (grep com.pulumi = 0). The blocker
is NOT Pulumi — it is `ClusterBootstrapReadinessVerifier` (seed-master
.../controlplane/readiness/) FUSING pure phase reasoning with three host anchors, plus a duplicated
scenario the host still needs for live orchestration.

Cluster modules: cluster-port (5 files, the seam: `ClusterReadinessContact` {isApiReady,
areControllersEffective}, `ControllerRef`, `ClusterReadinessPhase`, `ClusterSchemaRef`); cluster-core
(3, `ClusterSpecialist` @Component — doctor-facing reasoning, NOT readiness; NO readiness verifier
today); cluster-bdd (4, the migrated in-container `ClusterReadinessScenario` + `ClusterBddScenarios`
front-door/graft membrane); cluster-edge (3, `KubectlClusterContact`).

Host-side (seed-master): only `ClusterReadinessResource` + `ReadinessOutputMapper` import com.pulumi
(stay host). `ClusterBootstrapReadinessVerifier` imports cluster.port but NO pulumi — pure reasoning
stranded host, the prime candidate. Its anchors, classified:
- pure/movable: `ClusterReadinessContact`, `ControllerRef`, the NIO kubeconfig poll.
- soft anchor: `SeedLog` (per-phase checks already take Consumer<String> logger; drop the fallback).
- BLOCKS the move: `ControlplanePolicy` (requiredControllers() hard-codes the controller catalog —
  kube-vip/cilium/kdns/openebs/headscale — against a host policy type: THE hardest fiber);
  `SeedNodeBootstrapWatcher` + `SeedSystemdAdapterRuntimeStatusSnapshot` (checkKubeconfigPublished
  fuses the systemd-bootstrap gate — host, systemd domain's concern); `BootstrapConfig` (fat host
  record; verifier reads readinessTimeout/kubeconfigRef/nodeName/incusProject — narrow to a small
  port-owned input).

`VerificationResult` = a nested record in the verifier (8 flat fields + asOutputs()). Produced by the
verifier factories, consumed by `ClusterReadinessResource`/`ReadinessOutputMapper` (pulumi) +
ResourceCreationPipeline/ResourceManager. Does NOT cross the seam — host-only projected verdict, stays
host as the Pulumi-facing output contract.

Two `ClusterReadinessScenario`: cluster-bdd's is the migrated "après" (self-contained, plays
in-container, consults the doctor itself, returns RunbookEnvelope) — SUPERSEDES the seed-master thin
"avant" (a DSL skeleton delegating to LiveClusterReadinessProbe→verifier). The 3 gaps blocking deletion
of the avant: (1) the systemd-adapter dependency edge (host phase-0 gate; cluster-bdd's KUBECONFIG
check is trivial kubeconfig!=null); (2) the policy→controllers projection (host passes real refs;
cluster-bdd uses List.of() = vacuously effective); (3) VerificationResult production + graph handoff
(cluster-bdd emits RunbookEnvelope, no VerificationResult).

**The fibers, in safe cut order. REVISED 2026-07-09 (fibers 1-4 SHIPPED; fiber 5 collapses into
strate 2 — see below). 1-4 = strate-1 foundation, mechanical, each drops one host import; the rest =
strate-2 business, depend on the graft being wired into the host pipeline:**

1. ✅ SHIPPED (c17dbb063) Cut SeedLog from the verifier + static→instance (fields, not threaded).
2. ✅ SHIPPED (4b575c0ff) Split the kubeconfig poll from the systemd-bootstrap gate — the gate moved
   UP into LiveClusterReadinessProbe (host); verifier lost runtimeStatus + 2 systemd imports.
3. ✅ SHIPPED (91add82f1) THE load-bearing cut: the `policy→List<ControllerRef>` projection OUT of the
   verifier into RequiredControllers.from(policy) (host); verifier lost ControlplanePolicy.
4. ✅ SHIPPED (492870c5f) Narrow BootstrapConfig → cluster-port record ReadinessInput
   {kubeconfigPath, timeout}; verifier lost its LAST host import. It now depends only on cluster-port
   (ClusterReadinessContact, ControllerRef, ReadinessInput) — READY to descend, but not descended.

**Fiber 5 was mis-scoped and is CANCELLED as a standalone step (2026-07-09).** The plan said "relocate
the pure verifier reasoning into cluster-core." But: (a) cluster-core is SEALED (0 export, crosses only
as the ClusterSpecialist service) and NEITHER seed-master NOR cluster-bdd depends on it — both know
only cluster-port. So a reasoning class in cluster-core is unreachable by its intended consumers.
(b) The point-in-time reasoning ALREADY EXISTS in cluster-bdd's ClusterReadinessScenario (the "après"),
today as a STUB — kubeconfig!=null (trivial), controllers=List.of() (vacuously ready). The host verifier
is the "avant" holding the REAL logic (rich NIO kubeconfig check, projected controllers, retry loops).
This avant/après pair is NOT duplication-to-unify-now — it is the expected transitional state (the "3
live gaps" of the cluster scan). Making the dying host depend on cluster-core just before deleting it
would be throwaway coupling. So the "relocate" IS strate-2 fiber 6 (fill the gaps in cluster-bdd), not a
separate mechanical cut. Strate 1c's real achievement = fibers 1-4: the verifier is cluster-port-only,
ready. (A premature ClusterReadinessReasoning in cluster-core was created then removed — don't recreate
it; the reasoning's home is cluster-bdd's scenario, reached in strate 2.)

**Strate-2 fibers (the descent proper):**
6. Migrate the 3 live gaps into cluster-bdd's scenario (systemd ordering, real controller set via the
   projected refs, the rich kubeconfig check + wait loops) to reach live parity with the host avant.
7. Delete the host avant (bdd/ClusterReadinessScenario/Stage/Probe/Live+Simulated + the verifier's
   reasoning half) once the host consumes the cluster-bdd graft (ClusterBddScenarios.run()) instead —
   keep only the pulumi-irreducible ClusterReadinessResource + ReadinessOutputMapper +
   VerificationResult + RequiredControllers (host projection) + broker sow/graft. Today the graft is
   exercised only by ClusterReadinessScenarioInContainerTest, not by ResourceCreationPipeline.
