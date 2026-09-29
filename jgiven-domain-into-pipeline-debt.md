---
name: jgiven-domain-into-pipeline-debt
description: The osgi/jgiven aggregator (jgiven-wrap/jgiven-testkit) should disappear — pipeline is the natural owner of the jGiven wrapping, the same way manifests owns the cdk8s carrier. Future migration, not scheduled.
metadata:
  type: project
---

**The jgiven "domain" is not a domain — pipeline owns the jGiven wrapping.** Just as cdk8s is a
synthesis SUBSTRATE owned by manifests (the carrier bundle, [[cdk8s-carrier-flat-jar-pattern]]),
jGiven is a scenario-expression substrate whose natural owner is the `pipeline` bundle. The fluent
pipeline grammar (`FluentTopicRunner`, `during`/`then`) IS the scenario-expression role; `jgiven-wrap`
only makes jGiven installable as an OSGi bundle. So `osgi/jgiven/` (jgiven-wrap + jgiven-testkit)
should be ABSORBED into `pipeline` — "c'est lui qui wrap jgiven dans les faits" (the user) — leaving no
top-level jgiven domain, jGiven a private dependency of pipeline.

**Why it's debt, not done:** the jGiven-OSGi wrap shipped as its own `osgi/jgiven/` aggregator
([[jgiven-osgi-testkit-shipped]]) before the pipeline-owns-scenarios framing crystallised. The carrier
pattern (owner domain wraps the substrate, fragments/consumers attach) makes the right home obvious in
hindsight.

**How to apply (when scheduled, mirror the cdk8s move):**
- Fold `jgiven-wrap` (jGiven-as-a-bundle) into the `pipeline` bundle's ownership — pipeline either
  embeds/exports the jGiven packages or carries the wrap as its concern. `jgiven-testkit`
  (the `JGivenTestkit.felix()` boot helper + shared `jgiven-fragment.bnd` include) follows.
- Every `-test` fragment that includes `${.}/../../jgiven/jgiven-testkit/src/main/resources/jgiven-fragment.bnd`
  and depends on `jgiven-testkit`/`jgiven-wrap` (doctor-core-test, doctor-port-test, the coming
  manifests-core-test) re-points to the pipeline-owned location.
- Watch the same anti-split-package rule: pipeline-owned jGiven packages keep jGiven's own
  `com.tngtech.jgiven.*` names (third-party), exported once by the wrap — no second exporter.

Scheduled AFTER the current work (cdk8s carrier done; manifests-core-test Phase 2 next). The user
flagged it as a known future migration while reviewing [[cdk8s-carrier-flat-jar-pattern]].

**ABSORBED by the aggregator layout spec (2026-06-28):** [[osgi-aggregator-layout-spec-state]] tranches
this — its target layout dissolves `osgi/jgiven/` INTO `pipeline` (jgiven-testkit→pipeline-testkit,
jgiven-probe→pipeline-probe), exactly the move described here. This debt is now subsumed by that
post-merge layout increment.

See [[jgiven-osgi-testkit-shipped]] [[bootstrap-pipeline-contributable-vision]] [[pipeline-orchestration-osgi-vision]].
