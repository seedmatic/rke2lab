---
name: capn-cert-ownership-incoherence
description: "NEXT domino after the R4 seam: capn-client.crt is read from the classpath by an OSGi-world unit but lives in the host-world jar; 3 contradictory design statements about which world owns it — resolve before fixing"
metadata:
  node_type: memory
  type: project
---

The R4 seam is proven (commit d60b0ee8). The preview now fails further down at **incus
provisioning / domain registry**: `Client certificate resource not found: /incus/capn-client.crt`.
This is the next domino — and the user's steer is to **resolve the underlying incoherence**, not just
move a file to make the error disappear.

## The incoherence (three contradictory statements in the codebase)
`capn-client.crt` establishes the **`capn-provider@bioskop`** identity (a service identity, distinct
from the operator's personal `nxmatic@bioskop` client cert in `~/.config/incus/client.crt`). It is
read by `IncusIdentitySecretManifestsUnit.readClientCertFromClasspath()`
(`osgi/manifests/manifests-core/.../units/clusterapi/`) via `getClass().getResourceAsStream(
"/incus/capn-client.crt")`, to build the `<cluster>-incus-identity` K8s Secret in `capn-system`.

1. **The C4 diagram** in `docs/architecture/bootstrap/bootstrap-identity-provider.adoc` classes the
   cert under **"Operator Environment" (filesystem-resident)**, same rank as `.secrets` and
   `~/.config/incus/` — a runtime file read, NOT an app resource.
2. **Step 3 of the same doc** says: `cp capn-client.crt manifests/src/main/resources/incus/` —
   "shipped with the application and read from classpath during synthesis." (App resource.)
3. **The code** reads it from the classpath (matches #2) — BUT the cert physically lives in
   `exec/seed-master/src/main/resources/incus/capn-client.crt` (host world, committed since step-5
   aggregation 68e9f741), while the unit that reads it lives in `manifests-core` (OSGi world). Pre-R4
   the flat shaded classpath masked the gap; the isolated bundle now can't see the host jar's resource.

The unit's 3 OTHER inputs all come from the RUNTIME/operator filesystem (serverCert + remoteAddr from
`~/.config/incus/`, clientKey from `.secrets` at repo root) — only the client cert is classpath-fixed.

## The real question to settle FIRST (who owns the cert?)
Which world owns the `capn-provider` identity is a question of WHO AUTHENTICATES with it:
- If **seed-master (Stage A, host)** uses it to talk to Incus during bootstrap → it belongs to the
  host world; the cert placement in `exec/seed-master/resources/` is right, and the OSGi UNIT is
  misplaced — the host should PASS the cert to the unit (via BootstrapIdentity/context, like it
  already passes clusterName + incusRemoteName), not have the bundle read it.
- If it is only consumed by the **in-cluster CAPN controller (Stage B)** via the generated Secret →
  the cert is synthesis input data → belongs in `manifests-core/resources/` (placement is the bug).
- If it is **operator environment** (per the C4 diagram) → read it from the runtime filesystem like
  its 3 neighbours, embed it in NO jar.

## SETTLED 2026-06-20 (investigation done)
**Ownership: the host owns the cert.** `IncusProviderContext` authenticates seed-master to Incus via
`configDir(~/.config/incus)` + `generateClientCertificates(false)` — i.e. the operator identity
`nxmatic@bioskop`, NOT `capn-provider`. The `capn-provider` cert is **handoff data**: the unit reads
it to build the `<cluster>-incus-identity` Secret (keys `server`/`server-crt`/`client-crt`/
`client-key`) that the in-cluster CAPN controller (Stage B) consumes via `LXCCluster.spec.secretRef`
(`stagea-stageb-handoff-contract.adoc` §"Identity secret compatibility"). So the cert belongs to the
HOST world (seed-master holds it and hands it off); its placement in `exec/seed-master/resources/` is
RIGHT. The defect is the **OSGi unit reaching across worlds** to fetch it.

**Wider finding:** ALL FOUR Incus identity materials the unit reads are host-world data fetched by
the bundle in reach-around style — `clientCert` (classpath `getResourceAsStream`, the one that BREAKS
under isolation), `clientKey` (`Path.of(".secrets")` — relative to CWD, brittle), `serverCert` +
`remoteAddress` (`~/.config/incus/` via `user.home`). The 3 filesystem reads still "work" by accident
(the FS is not classloader-scoped); only the classpath read fails. But all 4 violate the same rule.
Contrast: the HOST already reads `.secrets` PROPERLY — `BboxSecretsReader.readBboxCoordinates(Path
worktree)` takes the worktree as a param and passes back structured `BboxCoordinates`; `BootstrapPaths`
has a typed `secretsFile()`. The bundle just re-improvises its own access.

## DECISION — the coherent host→OSGi channel (settled with user 2026-06-20)
Follow the **`ImageState` exemplar** (same module, same destination Stage A→B, doc literally says it
"hands a static identity to the in-cluster CAPN provider, which has no access to Stage A's Pulumi"):
a payload record in **manifests-port**, built by the HOST, passed via `ManifestSynthesisRequest.with*()`
→ `ManifestSynthesisContext` ThreadLocal, READ by the unit. The channel ALREADY EXISTS
(`ManifestSynthesisRequest` carries BootstrapIdentity/NetworkTopology/ComponentVersions/ImageState);
add one payload for the Incus identity materials.

- New record `IncusIdentityMaterial` (manifests-port): `serverAddress`, `serverCert`, `clientCert`,
  `clientKey` (+ UNKNOWN sentinel + default, like ImageState/BootstrapIdentity).
- HOST (seed-master/IncusResourceBootstrap) assembles it: reads `.secrets` via the typed
  `BootstrapPaths.secretsFile()` it already owns, `~/.config/incus/` (its world), the cert from ITS
  resources (host world). Legitimate — the host touches the host filesystem.
- `ManifestSynthesisRequest.withIncusIdentity(...)`; unit reads `...current().incusIdentity()` and
  ONLY renders the Secret — delete `Path.of(".secrets")`, `getResourceAsStream`, `user.home` from the
  bundle. Lift the WHOLE incoherence (4 materials), not just the cert that breaks.

## Handoff deliverables (design to document, per project doc discipline + master-workspace handoff)
1. Document the `IncusIdentityMaterial` payload pattern (mirror how `ImageState` is documented).
2. Fix the 3-way contradiction in `bootstrap-identity-provider.adoc` (C4 diagram "Operator
   Environment/Resources" + Step 3 "ship in resources/ read from classpath" → the host assembles the
   materials and the unit receives them via the context; the cert is host-held handoff data).

See [[osgi-runtime-r4-resume-state]] [[null-arg-is-a-rule-violation]] (no cross-world reach-arounds)
[[worktree-provisioning-handoff]] (the other master-workspace handoff payload).
