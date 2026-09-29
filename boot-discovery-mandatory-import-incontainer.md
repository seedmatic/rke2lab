---
name: boot-discovery-mandatory-import-incontainer
description: "Fix (2026-07-07): the in-container JUnit runner NoClassDefFoundError on boot.discovery/ClassRealm — a socle regression from the ClassRealm chantier (8ccc8ed). scenario-engine imported boot.discovery resolution:=optional but JUnitLauncherCore.wiringOf loads ClassRealm UNCONDITIONALLY; withJUnitRunner must system-export the package."
metadata:
  type: project
---

**Symptom:** any in-container test using `OutOfContainerFrameworkExtension.builder().withJUnitRunner()`
(doctor-port-test, doctor-core-test, manifests-core-test, …) fails at runtime with
`NoClassDefFoundError: io/seedmatic/rke2lab/osgi/boot/discovery/ClassRealm … not found by
io.seedmatic.rke2lab.osgi.runtime.scenario.engine`.

**Root cause (a socle regression, NOT the cluster-seed migration):** the ClassRealm chantier
(`8ccc8ed`) made `JUnitLauncherCore.wiringOf(loader)` call `ClassRealm.of(loader)`
UNCONDITIONALLY (both host-flat and in-container runs pass through it). But `scenario-engine`'s
bnd imported `io.seedmatic.rke2lab.osgi.boot.discovery` with `resolution:=optional` — so the bundle
resolved in-container without a provider, then blew up LATE when wiringOf loaded ClassRealm.

**Fix (2026-07-07):**
1. `scenario-engine/bnd.bnd`: DROP `resolution:=optional` on `io.seedmatic.rke2lab.osgi.boot.discovery`
   (falls onto the `*` wildcard → mandatory). What the code loads unconditionally at runtime must
   NOT be an optional import — it must fail at resolution, not with a late NoClassDefFoundError.
   (The OTHER optionals stay: framework.launch / runtime.framework / osgi.bnd / slf4j are host-flat-
   only classes never run in-container; component.runtime is optional-correct — ScrDiagnostics loads
   it ONLY under withScr. `org.apache.felix.framework.util` was a DEAD optional (no importer) →
   deleted.)
2. `OutOfContainerFrameworkExtension.withJUnitRunner()`: `systemPackages.add(
   "io.seedmatic.rke2lab.osgi.boot.discovery")` — the provider.

**Why SYSTEM-EXPORT, not install-as-bundle (settled with user after two passes):** boot-discovery
has a BSN but its manifest is `Private-Package` (exports NOTHING) — it is a host-flat SOCLE MODEL
(ClassRealm, BundleIndex, BootPlanner…), "the embedded-OSGi boot MODEL shared by prod and tests",
NOT a bundle-to-consume. In prod the launcher runs host-flat (outside Felix) so boot.discovery is on
the flat classpath. In-container, scenario-engine is installed as a bundle and shares this socle
package THROUGH the membrane — exactly the system-export case (like org.osgi.framework), not a domain
to install. `JUNIT_RUNNER_BUNDLES` stays untouched (not a bundle-install).

**Deferred tension (not fixed):** scenario-engine mixes two natures — host-flat socle (OsgiConnection,
the boot.discovery user, the disciplines) AND the in-container runner (container/ + diagnostic/,
exported). It's host-flat in prod yet installed as a bundle in-container. A future socle chantier
might split socle from runner. Out of the cluster-seed migration scope. See [[classrealm-adaptable-pattern]]
[[osgi-system-export-resolution-only]] [[cluster-seed-execution-state]].
