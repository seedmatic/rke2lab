---
name: serviceloader-specialist-spi
description: "PARKED (post-merge): the doctor's specialist roster is an extension point — but the REAL target is integrating with the user's EXISTING federated OSGi system (dogfeeding propagation), NOT a local ServiceLoader. My first ServiceLoader take was under-informed."
metadata:
  node_type: memory
  type: project
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

Architecture question the user raised (2026-06-09) while building the medical-record chantier:
Pulumi runs seed-master as an executable (shaded) jar — would OSGi bundles, or a modern Java
alternative, be a better design than plain JARs for plugins?

**MAJOR REVISION (2026-06-09, after the user corrected me):** my first answer below ("ServiceLoader,
not OSGi") was scoped too narrowly — to seed-master seen as an ISOLATED batch fat-jar. The user then
revealed they ALREADY RUN a federated OSGi system that this should likely integrate with, which
changes the recommendation. Read this revision first; the section below is the original (narrower)
analysis, kept because its axis breakdown is still correct as far as it goes.

**The user's existing system (what I didn't know):** a federation of OSGi apps where **each app that
loads a plugin acts as a repository for its neighbours**; needs are declared in OSGi manifests
(Require-Capability / Import-Package with version RANGES); updating a plugin makes the resolver
auto-re-wire dependent apps per their declared needs; structured with git concepts (DAG, content
addressing / propagation). **It already implements the dogfeeding pattern** — capabilities propagate
through the federation.

**What I conceded was wrong:** axes 1 (dynamic lifecycle) and 2 (multi-version coexistence) — which I
dismissed as "not needed" — are the WHOLE POINT of the user's real system. OSGi there is not solving
non-problems; it is the propagation engine they already operate.

**The Pulumi obstacle largely DISSOLVES (key technical unlock):** "OSGi fights Pulumi" only holds for
the `pulumi up` mode where the CLI launches a jar. The medical-record work already uses the
**Automation API inline** (proven by wip/sandbox: the program runs IN-PROCESS and drives Pulumi). In
that mode seed-master OWNS its JVM bootstrap → it can host the OSGi framework and DRIVE Pulumi, instead
of being launched by it. Control direction inverts; the fat-jar/OSGi antinomy goes away.

**The beautiful connection (validates the user's instinct):** "recruit a specialist"
([[doctor-remediation-model]]) IS publishing a bundle into the federation. A doctor hitting a symptom
no specialist treats = an app declaring a Require-Capability on that symptom domain; the federation
resolves and propagates the new specialist to the doctors that declared the need. The OSGi dogfeeding
loop and the doctor recruitment loop are THE SAME loop, reified in infrastructure the user already has.

**Revised recommendation:** ServiceLoader covers only axis 4 (local SPI) — no versions, no propagation,
no repository-between-neighbours — so it is too poor if the target is federation participation. The
real direction = INTEGRATE the specialist roster with the existing OSGi federation. (ServiceLoader
stays a fallback only if a future need is genuinely a single isolated app.) Tensions to verify before
committing: gRPC/Netty under OSGi (classloading / split-packages — workarounds exist, must test on
1.28.0); cost of an embedded OSGi framework cohabiting the Automation-API host. Still PARKED to a
post-merge branch.

---
ORIGINAL (narrower) analysis — kept for the axis breakdown, superseded in conclusion by the revision
above:

**Conclusion (decided to PARK until after the medical-record merge):** neither OSGi nor a full JPMS
migration — the real need is a **domain SPI via `java.util.ServiceLoader`**, and the user confirmed
ServiceLoader is **already used elsewhere in this codebase**, so it is coherent (extend an existing
mechanism, not introduce a new one). [SUPERSEDED: this assumed seed-master isolated; the user's
federated OSGi system makes integration, not a local SPI, the real target.]

**The reasoning (four orthogonal axes "plugin" conflates):**
1. dynamic lifecycle (hot install/start/stop) — OSGi's core; seed-master does NOT need it (it is a
   batch program: `pulumi up`, build the graph, exit).
2. multi-version coexistence (two versions of a lib in one process) — OSGi; not needed.
3. strong encapsulation (JVM-enforced non-exported packages) — JPMS gives this; the project already
   approximates it with Maven module boundaries + package-private discipline ([[package-private-sweep]]).
4. SPI / extension point (discover + plug implementations) — **this is the only axis seed-master truly
   has**, and `ServiceLoader` covers it on a plain classpath, no framework, no module-info.

**Why not OSGi:** it solves axes 1+2 (which we don't have) at high cost (Import/Export-Package
manifests, Felix/Equinox, a classloader per bundle, bnd tooling) AND fights Pulumi head-on — Pulumi
launches a flat fat-jar (shade), the antithesis of OSGi's per-bundle classloader isolation.

**Why not a full JPMS migration now:** friction on this stack — gRPC/Netty split-packages break the
module path; the shade-plugin flattens module boundaries (and Pulumi wants a runnable jar, i.e. the
classpath+shade world); high migration cost for an encapsulation gain we largely already have.

**The actual extension point = the doctor roster:** `Specialist` / `SpecialistDomain`, and the
executors behind `RemediationProgramRef`. That is literally a service registry. The "recruit a
specialist" loop ([[doctor-remediation-model]]) IS plugin registration. So:
`ServiceLoader<Specialist>` + `META-INF/services/...` (or `provides ... with ...` if JPMS ever lands)
gives "drop a plugin, it is discovered" on the fat-jar Pulumi already runs. NEXT (post-merge, own
branch): sketch in C4/UML how ServiceLoader<Specialist> wires into the Generalist's roster. Relates to
[[medical-record-query-api-state]], [[seed-vcluster]].
