---
name: document-seam-cannot-expose-jackson-jsonnode
description: ROOT CAUSE (proven by Felix DEBUG trace 2026-06-28) — a type=seam bundle (gateway-port) must NOT expose a bundle-only type in its API. Document.payload() returns com.fasterxml.jackson.databind.JsonNode, but jackson is a BUNDLE while the seam is FLAT → two JsonNode realms → LinkageError in-container. Fix = Document.payload becomes a String (Option B); prevent recurrence with a SEAM_PURITY staging gate.
metadata:
  type: project
---

## What happened (and the WRONG first diagnosis)

2B Task 4a enriched `Generalist.consult(Document)` (doctor-core) to embed the structured
`ConsultationReport.toOutputMap()` sub-trees in the consultation Document. The IN-CONTAINER harness
`DoctorCoreInContainerTest` (indices [15]/[16]) then failed:

```
java.lang.LinkageError: loader constraint violation: loader 'app' wants to load abstract class
com.fasterxml.jackson.databind.JsonNode. A different abstract class with the same name was previously
loaded by org.apache.felix.framework.BundleWiringImpl$BundleClassLoader ...
```

I FIRST blamed `new ObjectMapper().valueToTree(...)` and tried building nodes from the seam node's
factory instead. **That was wrong** — removing the ObjectMapper did NOT fix it. Lesson: do not guess;
turn on the trace.

## The PROVEN root cause (Felix `@FrameworkLog(DEBUG)` on the in-container test)

The resolver WIRE lines showed:
- `[doctor.core 25] com.fasterxml.jackson.databind -> [jackson-databind BUNDLE 28]` — jackson is a
  BUNDLE (the user's standing decision: "jackson arrives in OSGi via a bundle, not the JCL"). Correct.
- `[doctor.core 25] io.seedmatic.rke2lab.gateway.port -> [felix.framework 0]` — the seam is FLAT
  (system-exported). Correct.
- gateway-port's BUILT manifest: `Import-Package: com.fasterxml.jackson.databind, .node` +
  `Provide-Capability: type=seam`.

The defect: **`Document` lives in the FLAT seam but its `payload()` returns `JsonNode` — a type that,
for a bundle like doctor-core, resolves to the BUNDLE jackson [28], while the flat `Document`'s own
`JsonNode` resolves to the flat JCL jackson.** Two `JsonNode` classes meet when doctor-core calls
`Document.newPayload()`/`payload()` → `LinkageError`. It is NOT the seam wiring (imports aren't
over-exported) and NOT the ObjectMapper — it is that **a type=seam exposes a bundle-only type in its
API**. A seam (flat, shared host+OSGi) may only carry types that are themselves shared
(system-exported / JDK / other seams).

Latent since 2A (Document(JsonNode) shipped), revealed by the FIRST in-container test that makes a
bundle manipulate a Document payload (DocumentTest/ReadinessAuthorityTest run FLAT = one jackson =
green; the flat `-Dtest=GeneralistConsultDocumentTest` also runs flat, which is why I missed it).

## The fix — Option B (user-approved 2026-06-28)

`Document(String domain, String coordinate, String payload)` — payload becomes a **String** of
serialized JSON/YAML, NOT a live `JsonNode`. gateway-port DROPS its jackson dependency; no jackson
type crosses the seam. Each world (re)serializes/parses with ITS OWN jackson (the bundle one in OSGi,
the host's on the flat side — or the host just transports the String). LinkageError impossible by
construction. Aligns with the design-of-record ("everything crosses as a Document/YAML" = text). The
`JsonNode` payload of 2A was a shortcut that pierced the flat/bundle barrier.

Touches the 2A foundation: `Document` (+ drop `Document.newPayload()`), `ReadinessAuthority.assess` /
`DefaultReadinessAuthority` (serialize verdict to String), `SystemdAdapterStage` (parse String),
`Generalist.consult` (parse in / serialize out, with doctor-core's bundle jackson), and all tests
that read `payload()` as a JsonNode.

## Prevention — the SEAM_PURITY staging gate (user-approved, do in the wake of Option B)

Add `SEAM_PURITY` to `StagingGate` (sibling of RECORD_PURITY / REALM_BOUNDARY in
`maven-embed-staging-ext/staging-extension`): for each bundle with `Provide-Capability:
io.seedmatic.rke2lab.embed; type=seam`, its `Import-Package` may name ONLY packages that are
system-exported (other seams) / JDK / OSGi-framework — NEVER a package provided by a non-seam bundle
(type=model/edge/record or a third-party lib bundle like com.fasterxml.jackson.*). A forbidden import
= ERROR at build time. This would have failed 2A's build the moment Document(JsonNode) made
gateway-port import jackson — the barrier instead of the crash two increments later. The gate goes
green exactly when Option B drops jackson from gateway-port.

## Test-time filet (already a standing lesson)

Any change to a seam / Document / jackson MUST be verified via the `*InContainerTest` harness (two
realms), NEVER via a flat `-Dtest=`. A flat run has one jackson and hides the collision. `@FrameworkLog`
(Felix stdout resolver trace) is the lever that proved this; it needs no slf4j backend.

See [[world-gateway-2b-zone1-egress-knot]] [[world-gateway-2a-execution-state]]
[[realm-boundary-gate]] [[bundle-on-jcl-is-wrong-classpath]].
