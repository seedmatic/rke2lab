---
name: derive-incontainer-graph-from-imports-backlog
description: In-container test probes hardcode the bundle install/resolve graph (the GRAPH list in ManifestsCoreInContainerTest). It should be DERIVED from Import-Package analysis (the shared BundleIndex.closeOverImports the BootPlanner already drives), not hand-listed.
metadata:
  type: project
---

**Backlog (raised 2026-06-26):** the `-test` probes name their host's whole runtime graph by hand —
`ManifestsCoreInContainerTest.GRAPH` lists manifests-cdk8s, the systemd fragment, the ports, pipeline,
unitrepo-core, the jackson stack, snakeyaml, commons-compress. That list is a manual transcription of
manifests-core's `Import-Package` closure: brittle (a new import = a silently-failing resolve), and it
duplicates a fact the bundles already declare. doctor-core-test has the same hand-list.

**The fix:** derive the install/resolve set by analysing the host bundle's `Import-Package` and closing
over the index — exactly what `BundleIndex.closeOverImports` already does (the SHARED frame the prod
`BootPlanner` and the testkit's `startScr()` both drive; see [[bundle-on-jcl-is-wrong-classpath]] and
the boot-decomposition work). The probe would: take the host BSN, walk its imports against the test
classpath index, install the closure WITHOUT starting, resolve the whole set in one pass. The order is
already irrelevant (resolve is order-independent); what's missing is auto-DISCOVERING the membership.

Note the start-vs-resolve distinction the manual version forced us to learn: bundles with a lifecycle
that have their providers already up can `installFromClasspath().start()`; a graph with cross-bundle
imports (and fragments, which have NO lifecycle) must be install-without-start + resolve-as-one-set.
The derived plan must respect that — fragments and not-yet-satisfiable bundles go in the resolve set,
not the start loop.

This is a sibling of [[dependency-analyze-gate-backlog]] (both about reading the dependency graph the
bytecode already encodes instead of restating it). Belongs to the junit-testkit /
OutOfContainerFrameworkExtension. See [[manifests-tests-pre-osgi-debt]] [[cdk8s-carrier-flat-jar-pattern]].
