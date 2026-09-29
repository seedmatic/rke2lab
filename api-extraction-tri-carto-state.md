---
name: api-extraction-tri-carto-state
description: "DESIGN/CARTO (read-only on integration @a100b75d, 2026-06-19): the API-extraction sort, the prerequisite to R4. The principle (user): the host must NOT see OSGi impl classes — fail-fast at BUILD, via dedicated *-api modules. But there are TWO kinds of API distinguished by WHO consumes (confirmed against integration-atlas.adoc §'two spaces'): (1) BRIDGE api — consumed by the HOST (and implemented by an OSGi bundle) → belongs to the HOST world (host owns the port, OSGi implements it; DIP, both arrows point at the host api); OSGi consumes host interface classes via system.packages.extra from the system bundle (R1's single-exporter, atlas P2). (2) INTRA-OSGi api — consumed ONLY by other bundles, never the host → stays an api bundle in the OSGi world. Sort criterion = does the host import this type? Carto found: unitrepo = pure intra-OSGi (host imports nothing); netplan = host imports only ClusterNetworkBlueprint (from the IMPL package …netplan, NOT …netplan.api — the existing split is mis-oriented vs who-consumes); manifests = 17 host-imported types SORTED (settled with user 2026-06-19): contract set = the 3 SPIs + 4 gateway records + NodeEnvContext + NodeEnvContributor (re-exported to OSGi) + ManifestDomainPolicy/Catalog/Annotations + 2 profiles records; STAY OSGi (invert at R4) = ManifestYaml, NodeEnvContributorRegistry, FloxRuntimeAssets. netplan contract = ClusterNetworkBlueprint. Per-type sort DONE (api = ports + gateway records + shared value/constant types ONLY). ★ NAMING SETTLED: role-suffix, space=dir never in artifactId; BRIDGE port = `-contract` in host/ (`host/manifests-contract`, `host/netplan-contract`), impl stays `-core`/its name in osgi/ (`manifests-core` UNCHANGED — NOT `-impl`, cf. unitrepo-core), intra-OSGi api = `-api`/`-handler-api`, cli = `-cli`. READY to become a codable prerequisite slice BEFORE R4 (own worktree). NOT coded yet."
metadata:
  node_type: memory
  type: project
---

## The element we were missing (user, 2026-06-19; confirmed in the atlas)

Two separate worlds, a SHARED knowledge of the domains (else no bridge possible), and the HOST is the
entry point (`main()` → `Pulumi.run`) so the HOST does the bridging. `integration-atlas.adoc` §"The two
spaces" already says it: *"the only edge that legitimately crosses is the bundle/host contract"*, and a
pure model reaching into host code is *"a crossing in the WRONG direction, a decomposition defect to
invert."*

So API extraction is NOT one move. There are TWO kinds of API, told apart by WHO CONSUMES:

1. **BRIDGE api** — consumed by the HOST, implemented by an OSGi bundle. Belongs to the **host world**
   (the north-bound is the host's, per our earlier decision; the bridge contract rides with it). DIP:
   the host depends on the api to consume it; the OSGi bundle depends on the api to implement it — both
   arrows point at the host-owned api, neither world depends on the other. Fail-fast at BUILD falls out:
   the host compiles against `…-api`, never the impl → a stray `new DefaultX()` in host code does not
   compile.
2. **INTRA-OSGi api** — consumed ONLY by other bundles, never the host (e.g. the unitrepo resolver, and
   `NodeEnvContributor` IF only the bundle-side registry consumes it). Stays an **api bundle in the OSGi
   world**. The user's point: "dans le monde OSGi on a aussi besoin de bundles ne portant qu'une api,
   mais cette api n'est consommée que dans le monde OSGi."

**How OSGi consumes the host's bridge interface classes (user's direct question): via the SYSTEM
classpath.** At R4 boot the host exports the bridge-api package via `system.packages.extra` from the
system bundle; the impl bundle `Import-Package`s it → host and bundle share ONE copy of the interface
(R1's single-exporter rule; atlas P2 proved this typed-access for `org.osgi.service.metatype`). No gRPC
crosses; only the pure contract package is shared.

## The sort criterion (objective)

For each type: **does any HOST module (exec/seed-master, host/*) import it?**
- YES → BRIDGE api candidate → host world (`host/<domain>-api`). BUT verify it is a genuine PORT
  (interface / gateway record), not an impl the host wrongly reaches into (= atlas wrong-direction
  defect → either promote a real port or invert the dependency).
- NO, but consumed by another bundle outside its origin → INTRA-OSGi api → OSGi api bundle.
- NO, consumed only inside its own bundle → impl, stays private.

## Carto findings (host imports, integration @a100b75d)

**unitrepo — PURE intra-OSGi.** The host imports NOTHING from `io.seedmatic.rke2lab.unitrepo.*`. Its api
(`unitrepo-handler-api`, the resolver SPI) never crosses to the host. Simplest case: it already is an
intra-OSGi api bundle; nothing to move to host.

**netplan — the existing api/impl split is MIS-ORIENTED vs who-consumes.** The host imports exactly ONE
type: `io.seedmatic.rke2lab.netplan.ClusterNetworkBlueprint` — which lives in the IMPL package
`…netplan` (NOT in `…netplan.api`). And `NetplanSynthesisService` (the SPI sitting in `…netplan.api`) is
NOT imported by the host at all — it is consumed by `netplan-cli` (exec). So netplan's current
package-level api/impl split was drawn by "interface vs class", NOT by who-consumes. The bridge type the
host actually needs (`ClusterNetworkBlueprint`) is on the wrong side. Re-sort by consumer:
`ClusterNetworkBlueprint` (+ whatever it transitively needs) is the host-facing contract;
`NetplanSynthesisService` is a CLI-facing/own-world port. (netplan→host bridge edge is the
nix-darwin-home blueprint feed — load-bearing, see flake.)

**manifests — ★ FINAL per-type sort (settled with the user 2026-06-19).** Shape + host-usage verified
on @a100b75d; each type placed by who-consumes:

[cols="2,1,2,1",options="header"]
|===
| Type | Shape | Host usage | Verdict

| `ManifestSynthesisService` | interface | service consumed at the seam | BRIDGE-API (host)
| `ManifestExplodeService` | interface | service consumed | BRIDGE-API
| `ManifestUpdateGate` | interface | the gate (R3-deferred) | BRIDGE-API
| `ManifestSynthesisRequest` / `…Result` | record | gateway | BRIDGE-API
| `ManifestExplodeRequest` / `…Result` | record | gateway | BRIDGE-API
| `NodeEnvContext` | interface | host PROVIDES the impl (`new DefaultBootstrapNodeEnvContext()`) | BRIDGE-API (a clean port — host implements, bundle consumes)
| `NodeEnvContributor` | interface | port consumed by host AND intra-OSGi (R3 registry) | BRIDGE-API host, RE-EXPORTED to OSGi via `system.packages.extra` (one definition, shared — user decision)
| `ManifestDomainPolicy` | record | value read | BRIDGE-API
| `profiles.ComponentVersions` | record | value | BRIDGE-API
| `profiles.FloxDebugPolicy` | record | value | BRIDGE-API
| `ManifestDomainCatalog` | final class + builder | shared catalog (`builder().addDefaultDomains()`) | BRIDGE-API (shared value type, not a service impl)
| `ManifestAnnotations` | final class (constants) | `ManifestAnnotations.LOCAL_CONFIG` | BRIDGE-API (shared constants)
| `ManifestYaml` | final class (static util) | `ManifestYaml.writeDocument` / `.mapper()` | STAYS OSGi (impl util) — host usage is a wrong-direction crossing to INVERT at R4
| `NodeEnvContributorRegistry` | class | `forServiceLoader()` (R3 dual-path) | STAYS OSGi (impl) — host consumes as a SERVICE at R4, not by importing the class (INVERT)
| `units.runtime.flox.FloxRuntimeAssets` | final class + builder | `builder().build()` + reads packaged `/runtime/flox` resources | STAYS OSGi (impl/assets) — lifting it host-side would break the bundle-side asset access; INVERT at R4
|===

So manifests contract set = the 3 SPIs + 4 gateway records + `NodeEnvContext` + `NodeEnvContributor` +
`ManifestDomainPolicy` + `ComponentVersions` + `FloxDebugPolicy` + `ManifestDomainCatalog` +
`ManifestAnnotations`. The 3 impl/util types (`ManifestYaml`, `NodeEnvContributorRegistry`,
`FloxRuntimeAssets`) STAY in the bundle; their current host usage is a wrong-direction crossing handled
by INVERSION at R4 (consume a service / pass a port), NOT lifted into the api. This keeps the api a pure
contract — the user's frontier rule: ports + gateway records + shared value/constant types ONLY.

**netplan bridge-api = `ClusterNetworkBlueprint`** (record, currently mis-placed in the impl pkg
`…netplan`). `NetplanSynthesisService` is CLI-facing (exec), NOT host-facing → it is netplan's own-world
port, not a host bridge-api. The netplan→host blueprint feed is load-bearing (nix-darwin-home, flake).

## ★ Naming convention (SETTLED with the user 2026-06-19)

The rule, confirmed against the existing reactor: **`artifactId == leaf-dir-name`; the SPACE
(`osgi`/`host`/`exec`) NEVER appears in the artifactId; the ROLE is carried by a suffix.** User
principle for this decision: *the NAME must show the role at first glance — the directory is only
context, and you don't always have it under your eye.* So the role suffix is explicit even when the
folder would imply it.

[cols="2,1,1,2",options="header"]
|===
| Role | Suffix | Space (dir) | Example
| CONTRACT port (crosses the host↔osgi seam, host-owned) | `-contract` | `host/` | `host/manifests-contract`
| intra-OSGi api (bundles only) | `-api` / `-handler-api` | `osgi/` | `unitrepo-handler-api` (existing)
| impl / core | `-core` | `osgi/` | `manifests-core`, `unitrepo-core`
| CLI | `-cli` | `exec/` | `manifests-cli`, `netplan-cli`
|===

Two consequences:

- **`manifests-core` STAYS `manifests-core`** (NOT `-impl`). Earlier in this session I floated
  `manifests-impl`; that was WRONG — the established convention is `-core` for the implementation/kernel
  (cf. `unitrepo-core`). The module renamed last session is stable; only the contract module is NEW.
- The suffix is **`-contract`**, the atlas's own word ("the bundle/host contract"). It states the role
  (the shared contract that crosses the seam) without the directory. See the Pohl-paper finding below
  for WHY it is NOT `-contract`.

## ★ Why NOT "bridge" — the Pohl & Gerlach paper (read in full 2026-06-19)

The user had us read *"Using the Bridge Design Pattern for OSGi Service Update"* (Pohl & Gerlach,
Fraunhofer FIRST, EuroPLoP 2003) and asked, read-only/introspective: did we reason correctly about our
"bridge-api"? The verdict reshaped the NAME (not the decomposition):

- **The paper's "Bridge" is a RUNTIME object, not a module.** It registers a generated `FooBar_Bridge`
  (holding `Object impl`, delegating `((IFoo)impl).foo()`) INSTEAD of the service, so a bundle can
  `bridge.setImpl(new NewFooBar())` to hot-swap the implementation with NO dangling references and NO
  bundle stop/start. That is the GoF Bridge in the strict sense (Abstraction↔Implementor varying at
  runtime), applied to OSGi service UPDATE. It even rejects listeners ("solely relying on listeners
  shifts the burden on the clients").
- **Our "bridge-api" is NOT that.** It is a Maven module of interfaces + records (a hexagonal PORT / an
  API-bundle), decoupling the host-world from the osgi-world at BUILD + classloader time via
  `system.packages.extra`. No indirection object, no `setImpl`, no hot-swap.
- **So "bridge" is a naming DEFECT, sharper than first thought:** it collides head-on with a canonical
  OSGi paper that uses "Bridge" for a DIFFERENT mechanism in our EXACT domain (OSGi service update). An
  OSGi-literate reader sees `manifests-contract` and expects a `setImpl`/delegation object. After a
  whole session on naming precision (the system/space/world/universe glossary), "bridge" is exactly the
  sin we fight: *the name lies about the role.* → renamed to `-contract`.
- **Did we reason correctly otherwise? YES.** The paper's PROBLEM (dangling refs on hot service
  replacement) does NOT arise in our lifecycle: the host consumes ONCE at boot inside `Pulumi.run`, then
  the process provisions and exits — no long-lived daemon holding references across updates. Hot-swap is
  the v2 horizon (R7+, stage-6 living registry). So NOT building the paper's machinery now is correct,
  not an omission.
- **★ A guardrail the paper names for v2/R7:** IF the living-registry / hot-swap arrives, the host seam
  must NOT cache the raw `getService()` reference in a field — that is exactly Pohl's dangling-reference
  bug. The idiomatic parry: a `ServiceTracker`-rebind (or a registered indirection object). Recorded as
  an R7 design constraint, not for this slice. See [[osgi-runtime-r4-boot-seam-state]].

**Target modules for the extraction slice:**

- `host/manifests-contract` — the manifests ports + gateway records + shared value/constant types
  (the bridge-api column of the per-type table above). NodeEnvContributor lives here too, re-exported
  to OSGi via `system.packages.extra`.
- `host/netplan-contract` — `ClusterNetworkBlueprint` (+ its transitive contract types). NOTE
  `NetplanSynthesisService` is CLI-facing (consumed by `exec/netplan-cli`), does NOT cross the
  host↔osgi seam → it is NOT a bridge-api; it stays an own-world port on the netplan impl side.
- `osgi/manifests/manifests-core` — UNCHANGED name; loses the lifted port types, keeps the impls
  (`Default*Service`, `ManifestYaml`, `NodeEnvContributorRegistry`, `FloxRuntimeAssets`) + depends on
  `manifests-contract` to implement the ports.
- `osgi/netplan` — UNCHANGED name (the impl); depends on `netplan-contract`.
- `unitrepo` — untouched (pure intra-OSGi, already `-core` / `-handler-api`).

Open sub-question for the slice (minor): the Java PACKAGE for the bridge-api — keep
`io.seedmatic.rke2lab.manifests` (the types move module but not package, so no import churn beyond the new
module dep) or introduce `io.seedmatic.rke2lab.manifests.bridge`? Lean: keep the package, move only the
module — minimal churn, and bnd export is by package so the bundle still exports the same names. Decide
when coding the slice.

## Next step

- This is the design/carto output on integration @a100b75d. The per-type sort AND the naming are now
  SETTLED (sections above). This is READY to become a prerequisite slice BEFORE R4: a dedicated worktree
  that creates `host/manifests-contract` + `host/netplan-contract`, moves the bridge-api types into
  them, re-points `manifests-core`/`netplan` (impl) + the host consumers at the new modules, and proves
  the build is green with the host compiling ONLY against `*-contract` (the fail-fast: a host
  `new DefaultX()` no longer compiles). R4's boot seam then lands on a clean api/impl frontier.
- The 3 stay-OSGi types' wrong-direction host usages (`ManifestYaml`, `NodeEnvContributorRegistry`,
  `FloxRuntimeAssets`) are INVERTED at R4, not in this slice — this slice only lifts the genuine ports.
- Touches module layout + the host/osgi boundary; decisions were user-owned
  ([[standing-autonomy-except-runtime-config]]). The design session does design, not impl — the slice
  itself runs in its own worktree.

See [[osgi-runtime-r4-boot-seam-state]] (R4, which this de-risks — the self-contained-jar packaging +
the boot seam), [[osgi-runtime-r3-consume-references-state]] (the dual-path + NodeEnvContributorRegistry
`forServiceLoader`), [[osgi-runtime-migration-state]] (spec §4 the runtime target),
the atlas `docs/architecture/integration-atlas.adoc` §"The two spaces" (the bundle/host contract +
wrong-direction-crossing language this builds on), [[hub:model-substrate-alignment]].
