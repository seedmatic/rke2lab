---
name: federated-unitrepo-p2p-design
description: "Out-of-band design (2026-06-27): separate the OSGi world from the host's shared JVM as a daemon, talked to via RSA/ECF over the SAME Document seam at every topology. Target = PEER-TO-PEER FEDERATED PROVISIONING (units CLONE, repository PULL); de-risked via a 3-rung ladder. Whiteboard in .local.d (ephemeral); THIS is the durable record."
metadata:
  node_type: memory
  type: project
---

A long out-of-band brainstorm (2026-06-27, NOT in the cluster-edge refactor context) on "can we
separate the Host world from the OSGi world via OSGi Remote Services, and what for?" — it travelled far
and the conclusion reframes several existing chantiers. The live whiteboard lived in
`.local.d/osgi-remote-edge-preview.adoc` (git-ignored, per-worktree → EPHEMERAL); this note is the
durable record so later steps restart from the conclusion, not the whole detour.

**THE SPINE (read this first):** one `Document`-on-`-port` seam, talked to via RSA/ECF, realised at
THREE topologies of the SAME contract — (1) same JVM = today's embedded, classpath entangled,
REALM_BOUNDARY is a lint; (2) **separate JVM, same/colocated host** = the user's core want, classpath
clean, REALM_BOUNDARY becomes PHYSICAL; (3) remote pod = the cluster step. Moving between rungs is a
change of TOPOLOGY, not a rewrite. The deepest motive the user crystallised is **classpath hygiene**
(stop embedding bundles `provided` into the host), not performance or dynamism.

**The trajectory (each frame reframed the previous):**
1. *Separate Host/OSGi via RSA* → embedded OSGi never pays its dividend (the dynamism — services
   coming/going, a registry that SURVIVES — is rewound every `pulumi up`). A daemon would pay it.
2. *Recruit a specialist* → the consumer is itself OSGi → RSA at full value.
3. *Peer-to-peer, the UNIT moves* → and the user corrected "move" → **CLONE**: source keeps it, the
   needing peer gets a COPY → two distinct units, separate identity/lifecycle, same capability.

**THE conclusion — three planes that must NOT be confused (this is the load-bearing model):**
- **Peer discovery** — A learns B exists. Mechanism: k8s services-by-label / ECF discovery / gossip.
  Open.
- **Repository query — RSA's real job, the "missing brick" the user pointed to.** The OSGi `Repository`
  (R5, `org.osgi.service.repository.Repository`, `findProviders(requirements)→capabilities`) **IS a
  service**, so RSA distributes it: A imports B's `Repository` as a proxy and asks its catalogue
  remotely. This is where RSA/ECF belongs.
- **Unit transport** — the bytecode travels by **clone** via a URL (HTTP/OCI), **never RSA**, out of
  band.

**Why CLONE (not call) is the target, and what it does to RSA:** cloning DECOUPLES in time — after the
clone A runs locally and autonomous, B may vanish. So RSA does NOT federate the *business capability
call* (that would couple permanently). RSA couples A↔B **only during the shopping** (B must answer when
A queries its catalogue), not during execution — a transitory, acceptable coupling. So "RSA recedes"
was MY over-correction; the right statement: **RSA recedes from the data plane, returns for the control
plane.** The whole ECF investigation was not a detour — it found the control-plane brick.

**Control plane has TWO candidates (to settle later):** (a) `Repository` distributed by RSA/ECF — live,
dynamic, fits "peers discover dynamically"; (b) an R5 repository.xml served over HTTP, re-fetched, zero
RSA (OBR/Maven-style) — simpler, static. Dynamism leans (a); simplicity leans (b). **If (a): do NOT
distribute the raw `Repository`** (Requirement/Capability may not marshal; Aries TCP = Java
serialization) — put a `Document` seam in front (`requirements in → capabilities+urls out`), coherent
with the world-gateway contract. To verify (don't presume): does ECF help federate a Repository, or is
it just "Repository is a service, RSA distributes it"; + Requirement/Capability marshalling on ECF.

**RSA implementation facts (verified June 2026, manifest-level):**
- **ECF Remote Services = the only live, viable choice.** 3.16.8 (Apr 2026), OSGi R8 RS/RSA TCK
  reference impl, EXPLICITLY supports Felix ("may be run on other frameworks e.g. Felix").
- ECF's required Eclipse bundles (`equinox.common`, `equinox.concurrent`, `core.runtime.jobs`) are
  **pure-OSGi / Felix-safe** — no framework hooks, no Equinox-internal casts, no extension registry, no
  p2 — via the `org.eclipse.equinox.supplement` shim that backfills `org.eclipse.osgi.*` packages.
  **PIN the ECF-recommended versions (equinox.common 3.9.x, Import-Package-based); `≥3.16` switches to
  `Require-Bundle: org.eclipse.osgi` which won't resolve on raw Felix.**
- **Aries RSA** = clean (zero Equinox, all on Central, TCP+fastbin generic transports) but DORMANT
  (R7, 1.16.1, Sep 2021); its only REST provider is the dead CXF-DOSGi (2017/archived). **CXF-DOSGi** =
  archived 2022. **No HTTP/REST requirement here** → the dead REST providers don't matter; generic
  transports (Aries TCP/fastbin, ECF generic) are what counts.
- **Karaf = REPORTED, not rejected**: actively maintained (4.4.11, Apr 2026) and its static
  build-time assembly (`karaf:assembly`, features baked at build, no runtime `mvn:`) would satisfy our
  "frozen bundle set" discipline — BUT **no released Karaf boots on JDK 25** (4.4.x enables the
  Security Manager, JDK 24+ forbids it per JEP 486; PMC says JDK 25 lands in the unreleased 4.5.0).
  rke2lab is JDK 25 → embedded Felix 7.0.5 sidesteps it (no Karaf launcher). Reconsider Karaf when
  4.5.0 ships.

**The classpath-hygiene motive (the third, and most immediately-payable, engine).** Independent of
federation and cluster: today the host embeds OSGi bundles at `provided` scope → shared classpath = the
very entanglement REALM_BOUNDARY exists to POLICE. A separate JVM for the OSGi world means the host
classpath carries ONLY the `-port` seams; no bundle-only type lives in the host process, so the leak is
not linted, it is IMPOSSIBLE. REALM_BOUNDARY keeps value as a build-time PROOF but the breach becomes
unconstructable. A separate JVM is NOT a pod — it's a subprocess, so NO chicken-and-egg and **RSA pays
from bootstrap onward** (correcting an earlier wrong claim that "embedded seed-master never uses RSA").

**Topology decisions taken:** seed-master STAYS OSGi-separate-not-embedded going forward (a subprocess,
not a shared JVM). It bootstraps the cluster → no cluster yet to host a pod (chicken-and-egg only
applies to the POD form, not the subprocess form). At the cluster step the HOST world itself is
transposed into a k8s workload (the system becomes self-hosted: the cluster runs its own orchestrator).
"Two distinct units, same thing" = the existing `exec/` faces model extended — one source, multiple
packagings (bootstrap-exec ↔ cluster-workload). Daemon supervisor varies by rung: colocated-on-bioskop
= launched by seed-master itself (unix-domain-socket transport, ECF 3.16.5 added uds); on bioskop-nixos
= NixOS systemd, EXACTLY like incus already runs there (LAN/TCP transport — uds is colocated-only);
in-cluster = Kubernetes (Deployment/probes), discovery = k8s Service DNS.

**The incus-shaped topology the user concretised:** the OSGi world = a PERSISTENT ENDPOINT on the NixOS
host `bioskop-nixos`, sitting beside the incus daemon that already lives there; `seed-master` runs on
`bioskop` (the operation box) and talks to the endpoint as a CLIENT — exactly mirroring how it talks to
incus via `sdks/incus/`. The RSA/ECF client is "the new SDK", incus is the proven template (same host,
same LAN, same client-daemon shape, code mould already exists).

**PULL, not push — the convergence (the satisfying payoff).** At a Pulumi command the updated bundles
reach the daemon by the daemon PULLING from a repository (the unitrepo), seed-master only DESIGNATES the
version (a control message over RSA); seed-master never carries the bundles. This makes ONE brick
(repository + resolver + pull) serve EVERYWHERE — the three engines (classpath-separation hot-swap,
in-cluster specialist recruitment, p2p federation) are revealed to be the SAME mechanism at different
topologies. It also sharpens the control/data split: PULL is the join — control says "update to vX"
(RSA), the daemon runs the data plane itself (fetch from repo).

**The de-risking LADDER (the user's key structural insight — two versions, two roles):**

- *SIMPLE (operational mode for seed-master, NOW):* operator builds → gets the impacted bundles →
  RELAUNCHES an up-to-date daemon ON BIOSKOP (colocated) → runs the Pulumi command against the fresh
  system. No hot-swap (relaunch), no PULL (the build supplies the bundles), no remote endpoint. **Yet it
  already delivers the main prize: classpath hygiene.** Colocated → unix-domain-socket transport. This
  is sufficient for seed-master's operational mode.
- *RICH (the real PROOF OF CONCEPT, BEFORE the cluster):* persistent endpoint on bioskop-nixos + PULL
  repository + RSA + hot-swap. Value is ARCHITECTURAL not operational — it PRE-VALIDATES the cluster's
  operational model on a simple substrate (1 daemon, 1 fully-controlled host) BEFORE the cluster adds
  its own layer. The user explicitly sees this as the POC needed before committing to the cluster.
- *Why it de-risks:* the step to cluster stacks TWO risks — (1) distributed provisioning (endpoint,
  PULL, RSA, hot-swap) and (2) k8s orchestration (pods, multi-peer, reconciliation). The RICH rung
  isolates risk (1) on a mastered host; the cluster then adds only risk (2) on a proven mechanism. Never
  both at once. **Recommendation:** the SIMPLE rung should already use the RSA seam (over unix-socket),
  so SIMPLE→RICH is a topology change, not a rewrite (same contract, N topologies).

**This realises the federation that these chantiers anticipated:**
[[unitrepo-design-unification-state]] (units = `UnitResource`, resolver in prod, bundles in embedded
Felix — THIS is its federated form), [[fragment-contribution-mediation-model]] (peer-to-peer through
the registry; MEANING distributed vs TIME in the executable),
[[osgi-frontier-underpopulated-chantier]] (the "peer-to-peer / clone / fragment" model presupposed),
[[pipeline-orchestration-osgi-vision]] (orchestration = capability, host ports stay host),
[[r4-resolver-service-ification]] (the injected Resolver is already there). The recruitment use-case
also closes [[medecin-conseil-efficacy-analyst-design]]'s "renvoi à recruitment".
