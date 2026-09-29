---
name: jgiven-dissolution-is-a-realm-change-backlog
description: The SAFE half of osgi-aggregator-layout §5.4 ("dissolve jgiven into pipeline") HAS NOW SHIPPED (commit 46c7cdf0, 2026-06-30) — jgiven was regrouped under a pipeline/ aggregator as pipeline-port (grammar seam), pipeline-jgiven (wrap), pipeline-testkit, pipeline-probe, pipeline-probe-test. LAYOUT-ONLY, NO export fusion. The DANGEROUS half — making the pipeline SEAM export com.tngtech.jgiven.* — remains DEFERRED. pipeline-port exports ONLY io.seedmatic.rke2lab.pipeline (type=seam); pipeline-jgiven stays a separate bundle exporting com.tngtech.jgiven.*. Export-fusion would put jGiven in two realms → LinkageError (DUPLICATE_REALM_CLASS forbids). The module-layout complaint ("jgiven as top-level peer") is RESOLVED; the backlog narrows to ONLY the export-fusion realm change. See [[jgiven-domain-into-pipeline-debt]] [[osgi-aggregator-layout-spec-state]] [[document-seam-cannot-expose-jackson-jsonnode]].
metadata:
  type: project
---

## The trap (found while planning the layout, 2026-06-30)

The user asked "did we forget to integrate jgiven into pipeline?" — which surfaced that §5.4 of the
aggregator-layout spec was never executed AND that executing it as written is dangerous.

`osgi/pipeline/bnd.bnd` today exports ONLY `io.seedmatic.rke2lab.pipeline` and carries
`Provide-Capability: io.seedmatic.rke2lab.embed; type=seam` → it is a SEAM: system-exported, FLAT,
read by the host out of the framework typed (BootPipeline, manifests-core, seed-master import
`io.seedmatic.rke2lab.pipeline` flat).

`jgiven-wrap` has NO embed capability — it is installed as a normal bundle by `JGivenTestkit`
(`installFromClasspath(...)` for jGiven's whole tail + `installBundles(WRAP_BSN)`), and the `-test`
fragments (doctor-core-test, jgiven-probe-test, manifests-core-test) include `jgiven-fragment.bnd` and
dep `jgiven-testkit`/`jgiven-wrap` at TEST scope.

If `pipeline` (the seam) absorbed jgiven-wrap and exported `com.tngtech.jgiven.*`, jGiven would be
BOTH system-exported flat (via the seam) AND installed as a bundle (via the testkit) = two exporters
of the same package = two realms = LinkageError. This is exactly the jackson-JsonNode bug 2B fixed
([[document-seam-cannot-expose-jackson-jsonnode]]) and the condition the DUPLICATE_REALM_CLASS gate
detects.

## The decision (user, 2026-06-30) — SAFE regroup shipped, fusion deferred

The layout increment shipped the SAFE half of §5.4 (commit 46c7cdf0). It regrouped `osgi/jgiven/`
under a `pipeline/` aggregator as pipeline-port (grammar seam, was `pipeline`), pipeline-jgiven (was
jgiven-wrap), pipeline-testkit, pipeline-probe, pipeline-probe-test — **layout-only, NO export
fusion**. pipeline-port exports ONLY `io.seedmatic.rke2lab.pipeline` (type=seam); pipeline-jgiven stays
a separate bundle exporting `com.tngtech.jgiven.*`. Packages and BSNs unchanged (jgiven name survives
in package/BSN; only Maven artifactIds renamed). The module-layout complaint ("jgiven as a top-level
foundation peer") is **RESOLVED**. §8 ("strictement le layout, aucune fusion") governed this increment
over the contradictory §5.4.

## The deferred increment (its own analysis, later)

Dissolving jgiven into pipeline must FIRST re-architect how jGiven enters the framework: if pipeline
exports jGiven flat, the testkit can no longer install it as a bundle (it would have to systemPackage
it, or jGiven stays a bundle and pipeline must NOT export it). That is a boot/testkit redesign, not a
`git mv`. Trace JGivenTestkit.felix() + jgiven-fragment.bnd + the 3 -test fragments before designing.
This is the [[jgiven-domain-into-pipeline-debt]], now with the realm constraint made explicit.

## Also fix the spec while here

`osgi-aggregator-layout-spec.adoc` is internally contradictory: §5.4/§3/§6 prescribe the dissolution,
§8 forbids module fusion. The layout increment's Task 6 adds a NOTE to §5.4 recording the deferral so
the next reader is not misled.
