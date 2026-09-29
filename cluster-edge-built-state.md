---
name: cluster-edge-built-state
description: "DONE (2026-06-26) — the cluster domain's port+edge built in 4 green steps (Option B): cluster-port seam (ClusterReadinessPhase rapatriated from doctor-records, ClusterSchemaRef, ControllerRef, ClusterReadinessContact), cluster-core swaps loose literals, cluster-edge = KubectlClusterContact @Component (pure ProcessBuilder, zero jars), seed-master injects the contact + thins the verifier. Committed cafa65db/1d8e966b/b07afe8e/42bf7b8b. Full reactor green, gates 0 error."
metadata:
  node_type: memory
  type: project
---

## What was built

The fourth external edge (after pulumi/ssh-to-age/dbus-systemd), spec'd in
`docs/architecture/osgi/cluster-edge-spec.adoc` (now status IMPLEMENTED). Built the CLASSIC
edge way — NOT the speculative "fragment-contribution mediation model" from the parked
[[fragment-contribution-mediation-model]] design (that said "NO cluster-core"). The user
decided Option B and we shipped a normal `cluster-core` + `cluster-port` + `cluster-edge`
triad, mirroring `dbus-systemd-edge`.

- **cluster-port** (new, `type=seam`): `ClusterReadinessPhase` (moved out of doctor-records —
  the doctor stops carrying cluster vocabulary; 5 seed-master files re-import from
  `io.seedmatic.rke2lab.cluster.port`), `ClusterSchemaRef` (single-sources the four
  `"cluster/*/v1"` literals), `ControllerRef` (neutral kind/name/namespace record), and
  `ClusterReadinessContact` — the Option-B contact seam: `isApiReady(kubeconfig)` +
  `areControllersEffective(kubeconfig, List<ControllerRef>)`, both STATELESS single-shot
  booleans. Deps: domain-annotations + doctor-records.
- **cluster-core**: `ClusterSpecialist` swaps `SchemaRef.of("cluster/*/v1")` →
  `ClusterSchemaRef.*.ref()`; gains a cluster-port dep. `ClusterSpecialistTest` is a PLAIN
  JVM unit test in `cluster-core/src/test` (its acts are pure — no white-box fragment; I
  briefly chased a phantom `cluster-core-test`, the user corrected me). It asserts the
  literal `"cluster/kubeconfig/v1"`, now a cross-check the enum maps right.
- **cluster-edge** (new, `type=edge`, `@Component`): `KubectlClusterContact` provides
  `ClusterReadinessContact` by shelling kubectl — pure ProcessBuilder, **zero embedded jars**
  (bnd has NO `-includeresource`; simpler than dbus which embedded three). `isApiReady` =
  `get --raw=/readyz`; controller check = `rollout status --watch=false` (point-in-time, no
  blocking wait). `ClusterEdgeBootTest` (`@Osgi`, out-of-container): SCR publishes the contact
  typed; the contact returns `false` deterministically vs an unreachable cluster.
- **seed-master**: `ProductionClusterReadinessProbe` injects the contact, resolved once from
  the registry via `resolveClusterReadinessContact` (mirrors `resolveSystemdRuntimeStatus`)
  and threaded PipelineState → BootstrapPipeline → ResourcesStage → ResourceManager →
  ResourceCreationPipeline → probe. `ClusterBootstrapReadinessVerifier` LOSES its kubectl
  mechanism (runCommand/CommandResult/private ControllerRef + the two kubectl wait bodies,
  ~150 lines) but KEEPS the host orchestration it owns: systemd/bootstrap gate, kubeconfig
  NIO poll, policy→ControllerRef projection (`requiredControllers`), the retry loops, and the
  `VerificationResult` output contract. The host owns the wait; the edge answers one
  point-in-time question per poll. cluster-edge embedded at runtime scope (like dbus edge).
  `EmbeddedBundlesBootTest` gains a 3rd test proving the embedded edge publishes the contact
  typed from the deployed `META-INF/bundles/` topology.

## Key discipline confirmed

The edge makes the contact and returns a raw fact; the host translates into a doctor fact
(the `Observation` projection stays whole in `ProductionClusterReadinessProbe`). The edge
never imports `ControlplanePolicy` — the host projects policy→refs and passes the neutral
`ControllerRef`s. "edges do not diagnose / doctors do not open doors."

The `-Posgi` profile sets `surefire.groups=osgi`, so only `@Osgi`-tagged tests run under it;
plain unit tests (ClusterSpecialistTest) run under the DEFAULT profile. `install` is banned
(no-snapshot-install enforcer) — use `package` for full-reactor verification.

See [[external-edges-chantier-handoff]] [[dbus-systemd-edge-spec-state]]
[[prefer-osgi-edge-three-reasons]] [[fragment-contribution-mediation-model]].
