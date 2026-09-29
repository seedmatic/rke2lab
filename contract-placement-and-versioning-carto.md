---
name: contract-placement-and-versioning-carto
description: "DESIGN/CARTO (read-only on integration @4b13a39f, 2026-06-19): a re-placement decision the user surfaced while reviewing the bridge→contract rename. The contract modules (manifests-contract, netplan-contract) are currently PLAIN JARS in host/, and impl bundles import their package UNVERSIONED — the only unversioned OSGi-consumed import. The user's insight: the contract is a PURE MODEL (interfaces + records, no pulumi/grpc) → by the atlas purity axis its nature is osgi/, NOT host/; it should be a VERSIONED OSGi bundle living in osgi/, consumed as a plain Java jar by the host. This also fixes a hexagonal error: the PORT belongs to the DOMAIN (osgi/), not the adapter (host/) — 'host owns the port' was who-consumes confused with what-nature. Runtime model VALIDATED against the osgi.core archetype: a versioned API jar, NOT installed as a bundle, imported with version by bundles AND exported flat via system.packages.extra (R1 single-exporter). CONCLUSION: rename slice should be re-scoped to rename+re-place+bundle-ify together; do NOT merge it as-is (it re-confirms the wrong host/ placement). Name `-contract` stays. ★ TARGET settled with user: each contract lives BESIDE its impl facet by domain (osgi/manifests/manifests-contract; promote netplan FLAT→domain-dir = netplan-core + netplan-contract); each contract versions INDEPENDENTLY on its own semver (per-package @Version, decoupled from the 0.1.0 build, start 1.0.0 each), NOT a shared version."
metadata:
  node_type: memory
  type: project
---

## Origin — the user's challenge during the rename review

Reviewing the bridge→contract rename (in flight, NOT merged), the user noticed the GENERATED manifest of
`manifests-core` imports the contract package UNVERSIONED:
`io.seedmatic.rke2lab.manifests.contract` (no `;version=`), while every other OSGi-consumed import IS
versioned (`unitrepo.core;version="[0.1,1)"`, `org.osgi.resource;version="[1.0,2)"`, jackson `[2.22,3)`).
First read (mine): "unversioned because delivered by the host classloader, correct, no fix." The user
pushed back twice and was right both times:
1. "the contract interfaces arrive via the Java classloader, not versioned" — yes, BUT that is a
   CONSEQUENCE of the plain-jar choice, not a justification; and
2. "maybe this shows an incoherence — the contract modules should actually be OSGi bundles, cross back
   to the osgi/ side, yet be consumed in plain Java on the host side."

That second point is the real design correction. It is right.

## Why the contract belongs in osgi/, as a bundle (three converging reasons)

1. **Purity axis (the atlas).** The atlas: "OSGi space — pure models that DESCRIBE; no com.pulumi, no
   io.grpc." The contract is exactly that — interfaces + records, zero engine imports. By NATURE it is an
   osgi/ model. We placed it in host/ by WHO-CONSUMES (the host imports it), conflating consumer with
   nature. Nature decides the space; consumption is a dependency edge, not a placement criterion.
2. **Hexagonal correctness.** In hexagonal architecture the PORT belongs to the DOMAIN (the core), not to
   the adapter. The host is an adapter; the contract is the domain's port. "The host owns the port" was
   backwards. Correct DIP: BOTH the host (adapter) and the impl bundle depend on the abstraction; the
   abstraction is pure → it lives with the domain (osgi/). Both arrows point into osgi/, neither world
   depends on the other's impl.
3. **Versioning falls out.** As a real bnd bundle with a `package-info.java @Version`, the contract
   package gets a semver; impl bundles then import it WITH a version, removing the lone unversioned
   import. The contract — the most central seam for R4 — becomes a first-class versioned API.

## The runtime model — VALIDATED against the osgi.core archetype (the key check)

The worry: "if the contract is an osgi/ bundle, does it get installed into Felix and cause a double-class
copy with the system.packages.extra export?" Answer: NO, and the archetype proves it. **`osgi.core` is a
versioned bundle JAR that is NEVER installed as a bundle** — it is on the system classpath, imported WITH
a version by the real bundles (manifests-core imports `org.osgi.resource;version="[1.0,2)"`), and exported
from the system bundle. The bench testkit does exactly this (`systemPackages(...)` /
`system.packages.extra`, `exportImportsOf`, [[osgi-runtime-r1-scr-state]] single-exporter rule).

So the contract is: **a versioned bundle at BUILD time (jar + bnd metadata + @Version), delivered FLAT at
runtime** (host classpath → `system.packages.extra`, NOT `installBundle`). The host consumes it as a
plain Java jar (Maven dep) — being "a bundle" does not stop plain-Java consumption (a bundle IS a jar).
This is the standard OSGi shared-API idiom; the proposal ALIGNS us with it. It also keeps R1's
single-exporter invariant: ONE copy of the contract Class, shared host↔bundle, no ClassCastException.

R4 impact (already a pointer in [[osgi-runtime-r4-boot-seam-state]]): the `system.packages.extra` string
must list the contract packages WITH their version once bundle-ified —
`io.seedmatic.rke2lab.manifests.contract;version="1.0"`, `.contract.node`, `.contract.profiles`,
`io.seedmatic.rke2lab.netplan.contract`. The impl bundles' versioned import then constrains against it.

## What this means for the in-flight rename slice

The rename slice (refactor/rename-bridge-to-contract, committed 8f534d5a, NOT merged) did the NAME
correctly (bridge→contract everywhere, the 4 META-INF/services FQNs, build green) — BUT it re-confirmed
the WRONG placement: the contract modules are still plain jars in host/. Merging it as-is then
re-placing to osgi/ would throw away a host→osgi git-mv. So:

- **Do NOT merge the rename as-is.** Re-scope to ONE slice that does rename (done), re-place to osgi/,
  bundle-ify, and version. Name `-contract` settled.

## ★ TARGET (decisions settled with the user 2026-06-19) — each contract BESIDE its impl facet, by domain

The user's rule: **the contract lives next to its impl facet, by domain — NOT grouped in a global
`contract/`.** This is the step-4 domain-DIR rule (a domain's modules live together under the domain
dir). Concretely:

- **manifests** — already a domain dir (`osgi/manifests/` aggregator + `manifests-core`). The contract
  drops in as a sibling: `osgi/manifests/manifests-contract`. No restructuring.
- **netplan** — FLAT today (`osgi/netplan` IS the bundle module, parent `bundle-parent`). Adding a
  sibling contract FORCES the FLAT→domain-dir promotion (exactly the `manifests/manifests`→
  `manifests/manifests-core` move of step 4): promote `osgi/netplan` → `osgi/netplan/` (aggregator
  `netplan-domain`) containing **`netplan-core`** (the impl, ex-`netplan`) + **`netplan-contract`**. The
  impl gains the `-core` suffix — uniform with `manifests-core` / `unitrepo-core` (the impl facet is
  `-core` everywhere; no bare-name impl, no `osgi/netplan/netplan` doubling).
- **versioning — INDEPENDENT PER CONTRACT (user, 2026-06-19).** Each contract versions on ITS OWN
  semver, NOT a shared/global version: `manifests-contract` and `netplan-contract` evolve separately, so
  manifests can bump (or break) its contract without touching netplan's. This IS the OSGi idiom — semver
  is PER API PACKAGE, not per build. Concretely: each contract's `package-info.java` carries its own
  `@org.osgi.annotation.versioning.Version("…")`, and the version is DECOUPLED from the
  `0.1.0-SNAPSHOT` reactor build version (the contract is the published API surface, the osgi.*
  archetype — `org.osgi.service.component` is 1.5, `util.promise` 1.3, each its own). Start each at
  **`1.0.0`** (a fresh published contract); thereafter each moves on its own per the semver rules bnd's
  baseline check enforces (micro = internal, minor = additive, major = breaking). If a contract has
  sub-packages with different stabilities (`…contract` vs `…contract.node` vs `…contract.profiles`),
  each sub-package may carry its OWN `@Version` — package-level, the finest grain OSGi allows. The impl
  bundles then import each with its specific version range.
- **the impl bundles** (`manifests-core`, `netplan-core`) DEPEND on the contract bundle at compile and
  import its package WITH a version; the contract is NOT installed in Felix at R4 (flat via
  `system.packages.extra`). The host Maven dep stays a plain jar dep, just now an `osgi/` GAV.

So the slice's moves: rename (done on the worktree) + `git mv host/manifests-contract →
osgi/manifests/manifests-contract`, `host/netplan-contract → osgi/netplan/netplan-contract` (+ promote
netplan to a domain dir, rename `netplan`→`netplan-core`) + add `bnd.bnd` + `package-info @Version` to
both contracts + re-point GAVs (impl bundles, seed-master, aggregators) + the netplan-core rename's own
fan-out (the netplan→host blueprint feed, flake, netplan-cli). Whether to re-scope the existing rename
worktree or start fresh is a close call — the rename worktree's package/META-INF work is reusable, but
the host→osgi git-mv + netplan promotion are new; lean: start a fresh slice off integration and cherry
or redo (the rename worktree's value is mostly the package-rename sed, cheap to redo). Decide at setup.

## ★ SHIPPED 2026-06-19 (squash merge dfdd1d23)

Executed in the osgi-cleanup slice ([[osgi-cleanup-slice-state]]), with one refinement: the modules
landed as `-port`, not `-contract` — `contract` was judged too generic mid-slice, `port` names the
hexagonal nature ([[rename-contract-to-port-state]]). So: `osgi/manifests/manifests-port`,
`osgi/netplan/{netplan-core,netplan-port}` (netplan promoted FLAT→domain-dir as designed here),
`osgi/systemd/systemd-port`. Each carries its own `@Version("1.0.0")`; the host now imports versioned
(`manifests.port;version="[1.0,2)"` — the lone unversioned import that triggered this carto is gone).
Both open decisions resolved with the user (netplan aggregator shape; baseline-vs-SNAPSHOT →
failOnMissing=false now, see [[osgi-baseline-install-discipline]]). The runtime model (versioned jar,
NOT installed as a bundle, flat via system.packages.extra) is unchanged and feeds R4.

## Sequencing (historical — pre-merge)

This is a design/carto note on integration @4b13a39f. NEXT: take the two decisions above (location +
version) WITH the user, then the rename worktree is re-scoped (or a fresh slice supersedes it) to
rename+re-place+bundle-ify, merged as one. The bench-BSN-drift backlog item and R4 are unaffected.

See [[rename-bridge-to-contract-state]] (the in-flight slice that must be re-scoped, NOT merged as-is),
[[extract-bridge-api-state]] (where the contract modules were born as host plain jars),
[[api-extraction-tri-carto-state]] (the bridge→contract naming + the Pohl finding),
[[osgi-runtime-r4-boot-seam-state]] (system.packages.extra must carry the versioned contract packages),
[[system-space-world-universe-glossary]] (purity = nature, the placement criterion),
the atlas §"two spaces" (OSGi describes / host actualises).
