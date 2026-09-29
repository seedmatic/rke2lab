---
name: jgiven-osgi-wrap-spike-verdict
description: "MEASURED VERDICT (spike/jgiven-osgi-bundle, 2026-06-21) + ADOPTED: jGiven (jgiven-core + jgiven-junit5 2.0.3) wraps as a first-class OSGi bundle with a LOCAL cost — NOT viral. All 3 paliers green on real Felix 7.0.5. The user DECIDED to pivot the test model to full-OSGi (fragment-tests). This unblocks doctor Placement 2 integration. The invariant osgi-system-export-resolution-only is REFINED (not refuted): a header-less jar whose deps are all bundles has a THIRD option beyond flat system-export — a local wrap — chosen when it must PARTICIPATE in OSGi (host fragments)."
metadata:
  node_type: memory
  type: project
---

## The verdict — and the decision

Spike (branch `spike/jgiven-osgi-bundle`, off `origin/main`) measured, against real embedded Felix
7.0.5, whether jGiven can be a first-class OSGi bundle and at what cost. Result: **wrap-propre, LOCAL —
not viral.** All 3 paliers green. Report: `docs/architecture/osgi/jgiven-osgi-wrap-spike-report.adoc`.
**The user ADOPTED it (2026-06-21): we pivot the test model to full-OSGi.** Rationale: testing OSGi-world
code from the bare-JVM JCL is a world-incoherence — the doctor `ReferralReplies` Maven cycle is its
symptom. Fragments restore coherence (test = its host's classloader). See [[doctor-internal-edge-debt]].

## The decisive fact (why the cost is local)

EVERY jar in jGiven's dependency graph ALREADY carries a `Bundle-SymbolicName` — guava + its
failureaccess companion, gson, paranamer, jansi, jakarta.annotation, byte-buddy; slf4j is owned by
pax-logging. ONLY `jgiven-core` and `jgiven-junit5` lack OSGi headers. So there is no
not-designed-for-OSGi TAIL to embed: the wrap stamps headers on the two jgiven jars
(`Export-Package: com.tngtech.jgiven.*`) and imports everything else as stock bundles. No shade, no
relocation, no maven-dependency staging, no per-dep wrap.

## Total OSGi cost (the virality measure = bounded, enumerable)

1. *One framework boot-delegation*: `org.osgi.framework.bootdelegation=sun.misc` — byte-buddy's
   `ClassInjector.UsingReflection` reaches `Unsafe` reflectively WITHOUT importing it (no import to
   wire, so a system-export can't serve it). Added as a reusable `bootDelegation(...)` verb on the
   testkit `FelixFrameworkExtension` — *the one non-throwaway code change, generally-correct, keep it.*
2. *Exactly one forced fragment import*: `com.tngtech.jgiven.impl.intercept` — the runtime-generated
   stage proxy implements `StageInterceptorInternal`, invisible to bnd's bytecode analysis, so the
   wildcard drops it. byte-buddy packages need NO forcing (bnd computes them from bytecode).

No `DynamicImport-Package: *` anywhere. Why: decompiled `ByteBuddyStageClassCreator` defines the proxy
via `ClassLoadingStrategy.INJECTION` into the *stage's own classloader* and never looks stages up by
name → bounded, not viral.

## How it refines the invariant (NOT a refutation)

[[osgi-system-export-resolution-only]]'s criterion ("not designed for OSGi → system-export") still holds
for PRODUCTION code. The spike adds a THIRD lever for a header-less jar: if its whole dependency tail is
already bundles AND you need it to PARTICIPATE in OSGi (host fragments, in-container resolution), wrap it
locally into the bundle world instead of flattening it. jGiven is "not playable as a production
dependency, but cleanly wrappable as a test-time bundle" — test-scope only; NO production bundle imports
jGiven.

## The fragment-test model, demonstrated (idea #2)

Palier 3 realised [[osgi-testkit-framework-injection-idea]]'s sibling idea end-to-end: a pure host POJO
bundle (package-private `balance`) + a `Fragment-Host` test fragment authored in the host's package
reads the package-private field WHITE-BOX and runs a full Given/When/Then in-container through the host
classloader. (Gotcha: standalone `Scenario` needs `setModel(new ReportModel())`, normally JUnit-injected.)

## Where the throwaway lives

`osgi-spike/` — top-level sibling of `osgi/`/`host/`, pulled in ONLY under root `-Pspike`; default builds
never see it. Modules `jgiven-wrap` / `jgiven-wrap-fixture-core` / `jgiven-wrap-fixture-test` /
`jgiven-wrap-tests`. Run: `./mvnw -pl :jgiven-wrap-tests -am test -Pspike -DskipTests=false`. When the
fragment-test chantier starts, the `jgiven-wrap` bnd recipe + the fragment shape are the reusable
templates; the throwaway fixtures are not.

See [[osgi-system-export-resolution-only]] [[osgi-testkit-framework-injection-idea]]
[[doctor-internal-edge-debt]] [[bdd-jgiven-test-strategy]].
