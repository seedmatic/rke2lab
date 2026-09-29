---
name: system-exports-seam-gate-backlog
description: A future build-gate (4th staging law) — guard that FRAMEWORK_SYSTEMPACKAGES_EXTRA only ever carries seam packages (type=seam), never a domain concern that should wire bundle-to-bundle. The automatic guardian of the cdk8s-style "two worlds separate" rule we applied by hand.
metadata:
  type: project
---

**Backlog (user, 2026-06-26): "la prochaine build-gate, ce sont les framework system exports."** A
4th staging law beside RECORD_PURITY / SPEC_COVERAGE / INSTANCE_DISCIPLINE: assert that every package
the framework system-exports (`FRAMEWORK_SYSTEMPACKAGES_EXTRA`, derived by `BootPlanner.deriveSystemExports`
from the model bundles' Import-Package) is a legitimate SEAM — a `type=seam` `-port` membrane the host
and OSGi worlds genuinely share one copy of — and NEVER a domain concern that should resolve
bundle-to-bundle.

**Why now:** this is exactly the cdk8s trap we fixed by hand today ([[cdk8s-carrier-flat-jar-pattern]]).
cdk8s/constructs were leaking host-flat through system-packages; we moved them onto the bundle side
(carrier with Bundle-ClassPath + Export-Package). `deriveSystemExports` already has the seam-guard logic
(it removes bundle-exported packages, keeps only what resolves to null = a real seam), and the comment
there names the failure mode ("would split the class against the bundle's own copy"). The gate would
make that guard a BUILD-TIME assertion with a visible count, not a runtime-only invariant — so a new
flat-jar leak fails the build instead of silently splitting a class at boot.

**Shape (sketch):** read the staged model bundles' Import-Package closure, compute what would land in
system-packages, and flag any entry whose owning bundle is `type=model`/`type=edge` (a domain bundle
is the sole exporter → must be wired, never system-exported). Only `type=seam` packages (and genuine
JDK/host seams) may appear. Mirrors [[osgi-system-export-resolution-only]] (system-export ⟺ "NOT
designed for OSGi") as a checkable law.

**The model call (user, 2026-06-26):** "jgiven, cdk8s ils doivent arriver dans le classpath et pas via
les system exports — c'était une facilité qu'on a utilisée, mais un mauvais choix de modèle." So
system-exports-for-flat-jars is recognised DEBT, not a legitimate pattern: the easy lever (drop the
package in `system.packages.extra`) was the wrong model. The right model is the carrier
Bundle-ClassPath ([[cdk8s-carrier-flat-jar-pattern]]). Two concrete migrations this gate would protect
once done: cdk8s (DONE, d5a221fa) and jGiven → pipeline ([[jgiven-domain-into-pipeline-debt]], the
jgiven-wrap is itself a flat-jar carrier the gate would keep honest). The gate's job is to stop anyone
(including me) reaching for the facilité again — a non-seam package in system-exports = build break.

One of three gate ideas now queued: this one, extend INSTANCE_DISCIPLINE to host space
([[instance-discipline-gate-misses-host-backlog]]), and the dependency-analyze gate
([[dependency-analyze-gate-backlog]]). See [[build-gates-over-review-reminders]]
[[spec-coverage-gate-state]] [[bundle-on-jcl-is-wrong-classpath]].

## The TEST-side twin of this backlog (user, 2026-07-08 — "rendre la règle plus facile à observer")

The gate above guards PRODUCTION (what lands in `system.packages.extra` must be a seam). The user
raised the dual pain from the seat of a test: the seam RULE is invisible where you hit it. Concrete
trigger — adding `Document` (world-gateway, `type=seam`) to `cluster-bdd` broke the out-of-container
test with a bare `BundleException: missing requirement …world.gateway.port`, and the fix was to HAND-ADD
`world.gateway.port` to `OutOfContainerFrameworkExtension.systemPackages(...)`. Measured that day:
**~15 test/testkit sites hand-list seam packages** (`JGivenTestkit`, `OutOfContainerFrameworkExtension`,
every `*InContainerTest` / `*BootTest`), while **`BootPlanner.deriveSystemExports` already derives the
exact same set for the LIVE boot from the `type=seam` capabilities.** The source of truth exists for
production and is COPIED BY HAND for tests. That copy is the debt, not a fatality.

Three complementary axes the user chose (do as a dedicated chantier on a green base, NOT mid-fork-B):

1. **Test-kit derives like the boot (attacks the cause).** `OutOfContainerFrameworkExtension`/
   `JGivenTestkit` gain a `withSeamsFromDiscovery()` that derives system-exports from the discovered
   `type=seam` capabilities — the same derivation as `BootPlanner`. Deletes the ~15 hand lists; a new
   seam is available everywhere with no edit.
2. **Diagnostic that points at the cause (symptom→cause).** A `SeamResolutionDiagnostics` SIBLING of
   `ScrDiagnostics` in `scenario-engine/.../diagnostic/` — the javadoc there literally invites it
   ("siblings: bundle resolution, the service registry, join as they are needed"). On a resolve failure
   whose unmet package is owned by a `type=seam` bundle, it says "this is a seam: system-export it /
   `withSeamsFromDiscovery()`" instead of a bare BundleException. USE THE EXISTING `.diagnostic` HOME —
   the user's own steer 2026-07-08: "on a déjà un endroit prévu pour définir/partager des diagnostics".
3. **A doc-map of the seams (human aid).** A section in `world-gateway-spec.adoc` listing the 10
   `type=seam` bundles + the rule (type=seam → system-export, never installed) vs the domain ports;
   referenced from each bnd. Weakest (a doc can drift from code) — the executable axes (1,2) lead.

The 10 seams as of 2026-07-08 (all `-port` + world-gateway): cluster-port, auth-port, systemd-port,
doctor-port, incus-port, netplan-port, manifests-port, bbox-port, pipeline-port, world-gateway.
See [[osgi-system-export-resolution-only]] (the founding invariant) [[world-gateway-frontier-discipline]].
