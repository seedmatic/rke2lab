---
name: dbus-systemd-edge-spec-state
description: "SHIPPED to design/pre-integration (squash merge 2026-06-23) — dbus-systemd-edge, external edge #3, the first BOTH extracted from host AND migrated into OSGi. Durable model lives in docs/architecture/osgi/dbus-systemd-edge-spec.adoc + the keystone section of port-edge-domain-ownership.adoc. This note keeps only the cross-edge taxonomy (sondes vs portes) that guides the remaining edges, and the open backlog."
metadata:
  node_type: memory
  type: project
---

## Shipped — see the docs for the what/why

dbus-systemd-edge is **SHIPPED to design/pre-integration (squash merge, 2026-06-23)**. The systemd
contact (`DbusSystemdProbe`, the only file importing `de.thjom.java.systemd`/`org.freedesktop.dbus`)
is now `osgi/systemd/dbus-systemd-edge`, a `@Component` over dbus-on-TCP with the 3 dbus-java jars
embedded whole (`lib:=true`) so the `ServiceLoader` transport discovery resolves in the bundle
classloader (proven by `DbusSystemdEdgeBootTest`). Durable references:

- **`docs/architecture/osgi/dbus-systemd-edge-spec.adoc`** — the before/after model (six problems).
- **`docs/architecture/patterns/port-edge-domain-ownership.adoc` § the keystone** — knowledge runs
  downward; the control-node mediates between facets BY PORTS, never internals.
- **`frontier-playability-model.adoc`** — dbus reclassified host→playable (only Pulumi stays host).
- Decisions: `SystemdRuntimeProbe`/`SystemdStatusSnapshot` stay in `systemd-port` (systemd IS a domain
  — legitimate asymmetry with pulumi, which is not); `DbusTcpSpecialist`→doctor-core; typed `SystemdUnitId`.

## The cross-edge taxonomy — the lens for the remaining edges (incus / cluster / host-fs)

Two edge species, told apart by whether they produce a persisted FACT a domain later reasons over:

- **SONDE** — observe → snapshot → fact persisted to a stack → a doctor specialist reads it later.
  Mediated by the control-node (produce → translate → consult). The port + the reasoning live in the
  CONSUMER (doctor), never in a domain named after the target. Examples: pulumi, dbus-systemd.
- **PORTE** — egress / conversion; failure = exception; NO persisted fact, consumed inline by callers.
  Examples: ssh-to-age.

Applied to what's left (verified in the reactor 2026-06-23):

- **cluster-edge = a SONDE, and it GETS its own `cluster-port`** (correction: cluster is like systemd,
  NOT like pulumi). The discriminant is NOT "one consumer vs two" — it is "does the subject carry a
  domain vocabulary, facts ABOUT it that the consumer reads but does not own?". cluster does: the
  readiness phases (kubeconfig / api / controllers) are facts about the CLUSTER, not doctor vocabulary.
  So a `cluster-port` is warranted (symmetric with systemd-port), even if small. It is NOT speculative —
  it RE-HOMES vocabulary currently scattered/squatting:
  · `ClusterReadinessProbe` (the seam the edge implements) — promote from seed-master `controlplane/bdd/`.
  · `ClusterReadinessPhase` — MOVE from doctor-port (it squats the doctor's membrane today; that was a
    latent debt, not a precedent).
  · a single-sourcing enum binding each phase ↔ its `SchemaRef` ("cluster/kubeconfig/v1", "cluster/api/v1",
    "cluster/controller/v1") — today loose literals in `ClusterSpecialist` (doctor-core); this is the
    SystemdUnitId move (the producer=edge ↔ consumer=specialist relation made a type).
  The kubectl CONTACT (`ClusterBootstrapReadinessVerifier` + `ProductionClusterReadinessProbe`) → the
  `cluster-edge`. `ClusterSpecialist` STAYS in doctor-core (consumer of the port). NO `cluster-core`.
  manifests `units/cluster` (`ClusterRefs`, namespace synthesis) is a DIFFERENT cluster concern
  (resources, not readiness) — do NOT touch it; the shared `"cluster/"` string prefix is a naming
  collision, not shared vocabulary. **Name stays `cluster`** (one k8s cluster — unambiguous; k8s-cluster
  rename rejected). [Earlier note here wrongly said "no cluster-port, like pulumi" — superseded.]
- **host-fs = UNDETERMINED, likely NOT a sonde.** No filesystem specialist or observation exists in
  doctor (grep clean). The seed-master filesystem touch is DIFFUSE (config load, git metadata,
  checksums) — not one neat class like DbusSystemdProbe. Before building it: brainstorm whether there
  is a single extractable contact at all, and whether it is a porte or just host-internal plumbing
  that should NOT become an edge.
- **incus-edge** — the SDK already exists under `sdks/incus/`; playable (ProcessBuilder). Brainstorm
  contact / port-by-concern / sonde-vs-porte first, like every edge.

Each remaining edge still needs a short brainstorm first (contact? port-by-concern? sonde/porte?
snapshot→fact?), the pattern now being established + documented.

## OPEN backlog (carried, not lost)
- [[dbus-systemd-probe-poll-backlog]] — the probe re-opens a connection each poll tick; systemd emits
  D-Bus SIGNALS → a persistent connection + subscription is the right model. Same edge; fold when next
  touched.
- [[dependency-analyze-gate-backlog]] — (raised this chantier).
- Doctor-side: single-source the `SchemaRef` `dbus-tcp/*` literals (assessment-schema ids, doctor
  domain — NOT SystemdUnitId).
- ★ deferred proof obligation (seam Class identity across JCL/BCL) — from [[boot-decomposition-state]].

See [[external-edges-chantier-handoff]] (parent) [[felixframeworkextension-renamed-outofcontainer]].
