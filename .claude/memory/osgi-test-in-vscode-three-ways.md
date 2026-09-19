---
name: osgi-test-in-vscode-three-ways
description: "How to write an OSGi test that is BOTH typed (no reflection) AND runnable from VSCode Test Explorer — proven empirically 2026-06-17. Winner: plain embedded Felix + org.osgi.framework.system.packages.extra. The two canonical OSGi-test routes (org.osgi.test @InjectService; bnd tester) both FAIL the VSCode-clickable criterion."
metadata:
  type: reference
---

Building the osgi-bench (Step 2 slice 2) we needed a test that is at once (a) TYPED — no
`java.lang.reflect` to read an OSGi service — and (b) launchable from VSCode Test Explorer (a plain
JUnit5 `@Test` the redhat.java/vscode-java-test extension shows a gutter for). These pull in opposite
directions because of classloader isolation; three approaches were spiked against the real jars.

**The classloader problem:** an installed jar-bundle (e.g. `org.apache.felix.metatype`) loads its OWN
copy of an API package (`org.osgi.service.metatype`), distinct from the test app-classpath copy →
`getService` returns an object NOT castable to the test's type → `ClassCastException`. Reflection
crosses the boundary but is ugly.

**The three ways, with verdicts (all empirical, on Felix 7.0.5 / osgi.core 8.0.0 / JDK 25):**
1. *Reflection* — works, VSCode-runnable, but loses type safety / readability. (Our P2 used this first.)
2. *`org.osgi.test.junit5` `@InjectService` + `@ExtendWith(ServiceExtension.class)`* (the canonical
   OSGi-Test lib, 1.3.0 = latest stable) — TYPED but NOT VSCode-runnable. Root cause:
   `ContextHelper.getBundleContext(Class)` calls `FrameworkUtil.getBundle(testClass)`, which returns
   `null` when the test class is on the app classpath. It REQUIRES the test packaged as a bundle and
   run inside the framework — i.e. the bnd launcher. Rejected.
3. *bnd tester* (`.bndrun` + `-tester: biz.aQute.tester.junit-platform` + bnd-resolver/bnd-testing
   maven plugins) — TYPED in-framework, but the bnd launcher is NOT drivable by VSCode Test Explorer
   (Eclipse/Bndtools has a launch delegate; VSCode does not). Rejected for our criterion.
4. *Pax Exam* — NOT viable: `pax-exam-junit5` does not exist (4.14.0 ships only `pax-exam-junit4`);
   no JUnit5/Jupiter runner → not a modern Test-Explorer test. Rejected without building.

**THE WINNER — plain embedded Felix + `system.packages.extra`:** start an ordinary embedded Felix
(`FrameworkFactory.newFramework(config)`) with
`config.put("org.osgi.framework.system.packages.extra", "org.osgi.service.metatype;version=1.4,org.osgi.service.log;version=1.4")`.
That EXPORTS the API package from the SYSTEM bundle (= the test's app classloader), so the installed
felix.metatype imports it from there → ONE copy shared with the test → `getService` casts cleanly,
TYPED, no reflection. The test stays a plain JUnit5 `@Test` (a Jupiter extension boots Felix in
`@BeforeAll`) → VSCode-clickable. This is the bench's reusable pattern, folded into the testkit's
`FelixFrameworkExtension(String systemPackagesExtra)` ctor + `installFromClasspath(artifactId)`.
OSGi Connect (R8, `ConnectFrameworkFactory` + a ModuleConnector feeding the classpath) is an
alternative that also works, but `system.packages.extra` is simpler and needs no Connect plumbing.

**How to apply:** for any bench test that must read a service typed from a runtime bundle, construct
`new FelixFrameworkExtension("<api.package>;version=<v>")`, install the runtime + your bundle via the
testkit, get the service typed. Keep tests plain JUnit5 + `@OsgiSpike` so they run in VSCode AND
surefire. The bnd *builder* (bnd-maven-plugin) is fine and in use; only the bnd *test runner* is
incompatible with VSCode — don't conflate the two axes. See [[step2-decomposition-state]],
[[test-tag-taxonomy-by-zone]], [[hub:check-osgi-standard-before-modeling]].
