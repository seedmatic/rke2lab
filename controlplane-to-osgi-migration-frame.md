---
name: controlplane-to-osgi-migration-frame
description: "Converged frame (2026-07-07, brainstorm on feature/cluster-seed-scenario, NOT yet spec'd): migrate ALL host-side controlplane/ logic into OSGi services the host CALLS; only pulumi-edge (com.pulumi Output<T> wiring + resource registration) stays host. The host↔OSGi frontier is a BIRTH CANAL — crossed once at master seed, then SEALED (living master is peer-to-peer OSGi). Governs the 6-other-pipelines BDD migration too."
metadata:
  type: project
---

## The user's frame, tied through 4 questions (each verified at source)

Goal: migrate ALL logic still played host-side (`exec/seed-master/.../controlplane/`) into the OSGi
world; leave ONLY the `pulumi-edge` host-side. This is a superset of the earlier "incus + fakes"
frontier chantier — and it governs the BDD migration of the 6 other pipelines (same patterns).

## The cut line — the two-worlds invariant decides, not us

`grep -ri pulumi osgi/ == 0` ⇒ `com.pulumi` CANNOT enter Felix. So migration is NOT "move packages",
it's **cutting along the plan/render seam**. Verified inventory (2026-07-07):

- `controlplane/` = **104 files**; exactly **17 import `com.pulumi.*`** (grep-verified).
- The 17 = resource **registration** (`*Resource`, `IncusResourceBootstrap`, `ConfigLoader`/`Rke2labConfig`)
  + `Output<T>` wiring → **STAY host** (they ARE the edge; the invariant forbids them in Felix).
- The other **87 are Pulumi-free** (config DTO/registry, policy, readiness-verifier, the 33 BDD,
  orchestration) → **MIGRATE to OSGi**. Verified: `BboxReconciliationOrchestrator`, `ResourceManager`,
  `ClusterBootstrapReadinessVerifier` import ZERO pulumi.
- Still 100% host-side with NO osgi module: **incus** (`controlplane/incus/`) and **bbox**
  (`controlplane/bbox/`; a dedicated client repo `java-bbox-api-client` already exists).
- NOTE: the old [[osgi-frontier-underpopulated-chantier]] "3 domains + 2 edges" count is STALE —
  `osgi/domains/` now has 7 domains (cluster, doctor, manifests, netplan, ssh-to-age, systemd, unitrepo).

## The frontier is UNI-directional AND EPHEMERAL — the load-bearing insight

The user doubted ("host can call OSGi via a remote façade, but the inverse?"), then answered himself,
correctly. Two mechanisms, NOT symmetric (verified):
- **host → OSGi = a CALL** (the "remote façade"): `awaitService(X.class)`, synchronous, host PULLS.
  Sites: `IncusResourceBootstrap:1006`, `SystemdAdapterStage:105/120/138`. Proven in prod by
  `cluster-edge` (`isApiReady(kubeconfig)→boolean`) + `dbus-systemd-edge`.
- **OSGi → host = NOT a call, a DATA RETURN**: OSGi never calls back. It produces a flat `Document`
  (`readiness-verdict` carrying `Action.STOP`/`CONTINUE_DEGRADED`, or the canonical `intervention`);
  the host — which initiated — reads it and acts.

**The frontier is a BIRTH CANAL: crossed ONCE, at master's seed, then SEALED.** The host is a midwife —
`Pulumi.run(ctx)` always crosses FIRST (nature of Pulumi), materialises master, then its job is done.
Everything that LIVES after birth is OSGi (peer-to-peer). Consequences:
- Nothing to design for a reverse/persistent/repeated crossing: it is ONE-SHOT → the CURRENT gateway
  (`Document`/codec/`awaitService`) suffices, zero new machinery.
- "Which channel for an OSGi→host intent (new coordinate? SeedRun?)" was a MIS-POSED question (user:
  "we can see it another way"). There is NO intent to PUSH from OSGi; the host CALLS a service and gets
  a flat value back from the call.
- The peer-to-peer OSGi↔OSGi is PHASE 2, out of scope — [[world-gateway-2c-peer-model-design]] (RSA,
  converged, NOT built).

## The hard knot — fat classes (edge FIN decision)

~5 classes interlace decision AND `Output<T>` in one file — chiefly `IncusResourceBootstrap` (3000+
lines; `Output<String> ensuredProjectName/ProfileName/ImageFingerprint` at 195/339-341/416-426/1028;
monolithic `apply()` prepare→provision→launch). **User decided EDGE FIN**: the *plan decision over FLAT
values* (which slots, which image, which config) migrates to a callable OSGi service; the *`Output<T>`
wiring* (which IS the resource graph, not a decision input) stays host. This is exactly the
`cluster-edge` gesture already shipped ([[cluster-edge-built-state]]): the verifier lost ~150 lines of
kubectl mechanism but kept the host orchestration. Pattern:
`host awaitService(IncusProvisioningPlanner) → flat plan back → host wires com.pulumi.incus.* with Output<T>`.

## Rough chantier order (NOT frozen — for writing-plans)

1. Migrate the cleanly-separable Pulumi-free first (config, policy, readiness-verifier, the 33 BDD) →
   their OSGi domains. Populates the frontier fast.
2. Create the missing domains (incus, bbox).
3. Cut the fat classes (flat plan OSGi ↔ `Output<T>` wiring host).

Prerequisite still holds: incus/bbox have no osgi module yet — this frame SUPERSEDES the narrower
"incus edge + fakes" ordering by absorbing it into a full controlplane→OSGi sweep. Figures + prose in
`.claude/claude-preview.adoc` (§HANDOFF — MIGRER TOUT LE CONTROL-PLANE EN OSGi).

See [[osgi-frontier-underpopulated-chantier]] [[per-domain-osgi-fakes-chantier]]
[[world-gateway-2c-peer-model-design]] [[cluster-edge-built-state]] [[cluster-seed-execution-state]].
