---
name: cdk8s-carrier-flat-jar-pattern
description: How flat (non-OSGi) jars like cdk8s/constructs are brought into the OSGi world — a carrier bundle that embeds them in its Bundle-ClassPath and exports their packages, NOT host-flat system-packages. Plus the owner-vs-host role-inversion rule for fragments that contribute a rendering.
metadata:
  type: project
---

**Flat jars belong on the BUNDLE side of the two-world boundary, not host-flat.** cdk8s and
`software.constructs` are plain jars (no `Bundle-SymbolicName`). They were imported by manifests-core
and satisfied host-flat via `FRAMEWORK_SYSTEMPACKAGES_EXTRA` (the BootPlanner "host-flat package,
mirrored from a model import" path) — the host world leaking into OSGi. Fixed (commit d5a221fa) by a
**carrier bundle**:

- `osgi/manifests/manifests-cdk8s` (BSN `io.seedmatic.rke2lab.manifests.cdk8s`) embeds the flat closure
  INTACT into its `Bundle-ClassPath` via bnd `-includeresource: cdk8s-*.jar;lib:=true, constructs-*.jar;lib:=true,
  jsii-runtime-*.jar;lib:=true, annotations-*.jar;lib:=true` and `Export-Package: org.cdk8s, software.constructs`.
  The `lib:=true` directive nests each jar AND adds it to the Bundle-ClassPath (same idiom as
  `dbus-systemd-edge`). Only the EXPORTED packages need listing; the internal closure
  (software.amazon.jsii.*, org.jetbrains.annotations) stays private to the bundle classpath.
- **Which jars to embed:** only the ones with NO `Bundle-SymbolicName` (cdk8s, constructs, jsii-runtime,
  jetbrains annotations). Real OSGi bundles in the closure (jackson-datatype-jsr310, javax.annotation-api)
  stay IMPORTED and wire bundle-to-bundle. Check with `unzip -p <jar> META-INF/MANIFEST.MF | grep -i symbolicname`.
- Once a bundle EXPORTS org.cdk8s, `BootPlanner.deriveSystemExports` removes it from system-packages
  (`exports.removeIf(bundleExportedPackages.contains(...))`) — it becomes a domain concern wired
  bundle-to-bundle, the correct side of the seam. No split package, no host leak.
- cdk8s is NOT a domain (no `-port`, no business contract): it's a synthesis SUBSTRATE. manifests is
  "the rke2lab view on cdk8s", so manifests OWNS the carrier. Naming: `manifests-cdk8s` = prefix says
  owner-domain, suffix says the tech brick. No top-level `osgi/cdk8s/`.

**Owner-vs-host role inversion (the load-bearing design call):** when domain A renders ITS OWN
vocabulary using domain B's substrate, A OWNS the rendering and CONTRIBUTES it to B's carrier as a
fragment. Example: the cdk8s rendering of systemd units (SystemdChart/Service/Unit/Target/DropIn) is a
SYSTEMD concern (it's the systemd unit vocabulary, just expressed as cdk8s Constructs), so it lives at
`osgi/systemd/systemd-cdk8s-manifests` (owner=systemd) but is a `Fragment-Host:
io.seedmatic.rke2lab.manifests.cdk8s` (attaches to the manifests carrier). Module name reads
owner→tech→target: `systemd-cdk8s-manifests`. The "it doesn't depend on systemd-port" argument for
manifests-ownership was WRONG — that's a code debt, not evidence; the vocabulary it renders decides
ownership. This mirrors the distributed-contribution pattern everywhere (doctor specialists,
NodeEnvContributors): the producing domain owns and contributes; the consumer hosts.

**Anti-split-package rule it enforces:** a contributed fragment must put its classes under a root
package OWNED by the contributing domain. Renamed `io.seedmatic.rke2lab.cdk8s.systemd` →
`io.seedmatic.rke2lab.systemd.cdk8s` so the leaf sits under the `systemd` root (alongside
`systemd.port`), never overlapping the manifests host's packages. A split package = same package name
exported by two bundles; a fresh leaf under the owner's root makes that impossible by construction.

See [[refactor-statics-on-touch]] (same "use the OSGi model, two worlds separate" discipline) and
[[manifests-tests-pre-osgi-debt]] (the Phase 2 in-container fragment whose Felix resolve is the live
runtime proof that the cdk8s bundle-to-bundle wiring works).
