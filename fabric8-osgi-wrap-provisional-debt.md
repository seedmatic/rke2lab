# fabric8 Kubernetes client — wrapped as ONE bundle (PROVISIONAL), owe the switch to official OSGi

**Date:** 2026-08-07. **Status:** shipped provisional, debt recorded. **Module:** `osgi/runtime/fabric8-wrap`.

## Decision

The fabric8 Kubernetes Java client (7.8.0) is consumed as a SINGLE first-class OSGi bundle
`fabric8-wrap` that embeds the whole fabric8 closure via `lib:=true` and exports `io.fabric8.*`
once. `cluster-edge`'s `Fabric8ClusterContact` imports `io.fabric8.*` from it; seed-master stages
the wrap. It RESOLVES clean (`realm-wiring-integrity: 0 error`, staged set has `fabric8-wrap.jar`
+ `snakeyaml-engine.jar`, ZERO raw `kubernetes-model-*` jars).

**This is PROVISIONAL. The concern to resolve later: switch back to fabric8's OFFICIAL OSGi
packaging.**

## Why the wrap (the investigation, so we don't re-litigate)

fabric8 7.8.0 is NOT cleanly OSGi-stageable as separate bundles:

- The CORE jars — `kubernetes-client`, `kubernetes-client-api`, `kubernetes-httpclient-jdk/vertx` —
  ship with NO Bundle-SymbolicName at all (plain JARs, only an `Automatic-Module-Name`). Not bundles.
- The ~21 `kubernetes-model-*` jars ARE bundles but SPLIT-PACKAGE the shared base packages:
  `io.fabric8.kubernetes.api.builder` is `Export-Package`d by **17** of them,
  `io.fabric8.kubernetes.model.annotation` by **11**. Each jar carries its own copy + self-exports.
  Felix needs one exporter per package; the copies + conflicting `uses:` constraints make the whole
  set unresolvable → "did not resolve" cascade (the error the raw-staging attempt produced).
- fabric8 DOES publish a `:bundle` classifier (`kubernetes-client:7.8.0:bundle`, BSN
  `io.fabric8.kubernetes-client`) — but it is CLIENT-ONLY (0 model classes; it IMPORTS the split
  model packages, so the split is unsolved) AND it `Require-Capability`s
  `osgi.extender=osgi.serviceloader.processor` (aries-spifly). No clean model AGGREGATE bundle
  exists. Documented pain: fabric8io/kubernetes-client #822, #487, #3890, #3774.

The wrap sidesteps both problems: embedding puts all fabric8 on ONE Bundle-ClassPath (one
classloader, plain first-match — no OSGi "one exporter" rule, split dissolved), and the
bnd-recomputed manifest carries NO spifly Require-Capability; the ServiceLoader `HttpClient.Factory`
lookup is bypassed by passing `new JdkHttpClientFactory()` explicitly to `KubernetesClientBuilder`.
So spifly is NOT needed here — it is ORTHOGONAL (a ServiceLoader mediator, not a package-export fix).

## The debt / exit criteria (switch to official OSGi when ALL hold)

Re-base `cluster-edge` onto fabric8's official OSGi artifacts and DELETE `fabric8-wrap` once:

1. A resolvable fabric8 MODEL bundle set exists — either fabric8 ships a deduplicated model
   aggregate bundle, or upstream fixes the `api.builder`/`model.annotation` split (watch the issues
   above / a fabric8 version bump), AND
2. `aries-spifly` is integrated (already on the roadmap) to satisfy the `:bundle` client's
   `osgi.serviceloader.processor` extender requirement — at which point the explicit-factory
   workaround can also relax.

Then: depend on `kubernetes-client:bundle` + the model bundle set (provided in cluster-edge, staged
runtime in seed-master), drop the `lib:=true` embed, remove `fabric8-wrap`.

## Files

`osgi/runtime/fabric8-wrap/{pom.xml,bnd.bnd}`; `bom/pom.xml` (fabric8 BOM import +
`snakeyaml-engine` version — the one new transitive bundle); `exec/seed-master/pom.xml` stages
`fabric8-wrap` (runtime); `osgi/domains/cluster/cluster-edge` imports `io.fabric8.*` (fabric8
provided, compile-only). See [[prefer-osgi-edge-three-reasons]] [[cdk8s-carrier-flat-jar-pattern]]
[[serviceloader-specialist-spi]] [[declare-what-you-import]].
