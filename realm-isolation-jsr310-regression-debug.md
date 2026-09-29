---
name: realm-isolation-jsr310-regression-debug
description: RESOLVED + COMMITTED (2026-06-30, feature/cluster-edge, commit ae46278b). The realm-library-isolation increment broke the deployed exec-jar (pulumi preview: "ServiceConfigurationError: JavaTimeModule not a subtype"). Fixed by nesting jsr310 in the cdk8s carrier + Cdk8sApps TCCL-pin factory; slf4j-api un-staged via a boot-stack bound. Reproducing test GREEN, gates 0/0. NOTE: a SEPARATE pre-existing breakage (seed-master BDD test-compile fails at HEAD — RecordInterventionCommand/MedicalRecordDump/ObservationView/ClusterReadinessPhase) was unmasked by disabling the build-cache; NOT ours, another chantier.
metadata:
  type: project
---

## RESOLUTION (2026-06-30, commit ae46278b)

Fixed exactly as planned. Three code moves + a derived slf4j bound:
1. `osgi/.../manifests-cdk8s/bnd.bnd` — added `jackson-datatype-jsr310-*.jar;lib:=true` to
   `-includeresource` (jsr310 classes AND its META-INF/services now on the carrier Bundle-ClassPath,
   the loader that imports the databind bundle → one Module view). Exported the new package.
2. `Cdk8sApps.create(AppProps)` (NEW, in the carrier) — pins TCCL to the carrier's classloader for
   the `new App()`, so ServiceLoader resolves the nested jsr310 not the host-flat copy. The carrier
   gained `src/main/java` (was a pure jar-carrier); pom comment + spec doc updated. `getClass()` in
   the carrier IS `App.class.getClassLoader()`, so the factory lives where the embedding is owned.
3. `DefaultManifestSynthesisService` Cdk8sSetupStage — `new App(...)` → `Cdk8sApps.create(...)`.
4. slf4j 2nd prong: `StagingClosure.isRealmLibrary` now takes `bootStackExports` and skips a
   candidate whose package the boot-stack already provides (pax exports org.slf4j). slf4j-api no
   longer staged on ANY of the 3 exec-jars (verified); pax sole in-framework provider. New test
   `aRealmLibraryIsNotStagedWhenTheBootStackAlreadyProvidesItsPackage` (StagingClosureTest 3/3).

Verified: `EmbeddedBundlesBootTest.embeddedSynthesisRunsTheCdk8sSetupPathOnTheStagedJackson` GREEN
(was the reproducing RED). Gates: duplicate-realm 0/0, realm-boundary 0/0, spec-coverage 0 error.
Two-phase build gotcha re-confirmed (disable staging-ext in .mvn → rebuild aggregator → re-enable).

⚠️ The full-reactor `-Pall-worlds` WITH tests cannot go fully green until the PRE-EXISTING seed-master
BDD breakage is fixed (separate chantier — NoSuchMethod/Unresolved on RecordInterventionCommand.record,
MedicalRecordDump.toYaml, ObservationView.status/symptom/summary, ClusterReadinessPhase). Proven
pre-existing by `git stash` + test-compile at HEAD caea2d7b. The build-cache had been masking it.

Pulumi preview: ✅ FIXED end-to-end (user confirmed). After ae46278b the synthesis completed and the
pipeline advanced to a SECOND, latent host-side bug — "Classpath resource root not found:
META-INF/io.seedmatic/.../incus/manifests/systemd/systemd-scripts" at ClasspathTreeCopier.copy. The
resource IS in the exec-jar; the copier resolved it via the THREAD CONTEXT classloader, which under
the Pulumi runtime (pipeline worker, after Felix boot + cdk8s synth) does not see the host uber-jar
(resolves fine standalone — neither Felix nor jsii calls setContextClassLoader, so the exact cause of
the differing TCCL is unproven, but irrelevant). Fixed in commit 0185b32a:
ClasspathTreeCopier.class.getClassLoader() (deterministic, co-located with the resource) instead of
the ambient TCCL. preview now completes past host-state.

ASIDE the user raised (backlogged, NOT done): host systemd UNITS are synthesized in the OSGi world
(ManifestSynthesisService+explode), but host systemd SCRIPTS are still copied as static classpath
assets — the host/OSGi double-path already noted in [[realm-library-isolation-state]] ("consolidate
host-side manifest synthesis into the OSGi manifests world").

---
## (historical) original debug notes below

## Situation

The realm-library-isolation increment (6 tasks, commits 8bc21cce..caea2d7b on feature/cluster-edge)
built BUILD SUCCESS and the full-reactor gate was green (duplicate-realm-class 0/0, realm-boundary
0/0, in-container tests pass). BUT the user's `pulumi preview` — which boots the DEPLOYED exec-jar —
FAILS. Log at /private/tmp/pulumi.log. Two linked errors, one root cause:

1. **FATAL:** `java.util.ServiceConfigurationError: com.fasterxml.jackson.databind.Module:
   com.fasterxml.jackson.datatype.jsr310.JavaTimeModule not a subtype` — topic "cdk8s setup",
   in DefaultManifestSynthesisService (ForkJoinPool worker).
2. `org.osgi.framework.BundleException: Unable to resolve slf4j.api [8] ... missing requirement
   osgi.extender=osgi.serviceloader.processor` — slf4j-api was staged as a bundle but slf4j 2.x needs
   a ServiceLoader-processor extender nothing provides.

## ROOT CAUSE (confirmed at source)

jackson discovers its modules via **ServiceLoader<com.fasterxml.jackson.databind.Module>** (not a
static Import-Package). My increment staged `jackson-databind` as a BUNDLE (its `Module` class loads
on the bundle classloader), but `jackson-datatype-jsr310` stayed FLAT (verified: JavaTimeModule.class
only in the flat uber-jar, never under META-INF/bundles/ — because no bundle statically imports it;
it's runtime-discovered). So flat `JavaTimeModule extends` the FLAT `Module` ≠ the BUNDLE `Module` →
"not a subtype". Same fracture hit slf4j-api (staged but pax-logging-api was already its in-framework
provider).

The deep insight: jackson's cross-jar ServiceLoader SPI REQUIRES one shared classloader for all
jackson jars. My realm-library rule (based on static imports) is both too broad (staged slf4j, which
pax provides) and incomplete (missed jsr310, runtime-discovered). The original "jackson flat" choice
was correct for a reason deeper than static-import analysis.

## THE CODEBASE ALREADY SOLVES THIS (the pattern I ignored)

`osgi/domains/manifests/manifests-cdk8s/bnd.bnd` and `osgi/domains/systemd/dbus-systemd-edge/bnd.bnd`
embed ServiceLoader-SPI jars as WHOLE NESTED JARS on the consumer bundle's **Bundle-ClassPath** via
`-includeresource: foo-*.jar;lib:=true`. The manifests-cdk8s comment SAYS IT VERBATIM: "jackson-
datatype-jsr310 ... NOT nested — they are genuine OSGi bundles, so jsii-runtime's imports of them are
computed by bnd and wired bundle-to-bundle at runtime." dbus-edge comment: "The transport
ServiceLoader and the TcpTransportProvider it discovers then resolve through THIS bundle's
classloader, each jar keeping its own META-INF/services intact." jsr310 worked BEFORE because
databind was flat+system-exported → one Module view; my staging split it.

## WHY THE GATE MISSED IT (the user's sharp question)

`EmbeddedBundlesBootTest` (exec/seed-master) DOES boot the staged topology (`BootPipeline.embedded()`,
real META-INF/bundles/) — so it's NOT the "tests resolve jackson flat" story I first told. The real
gap: it only `awaitService(...)` (asserts SCR publishes the port services). It NEVER exercises the
"cdk8s setup" synthesis that triggers `ObjectMapper.findAndRegisterModules()` → ServiceLoader<Module>
→ the jsr310 failure. Coverage gap, not classloader gap. (Separately, the DOCTOR in-container tests
DO resolve jackson flat: OutOfContainerFrameworkExtension uses BundleIndex.ofClasspath() +
system.packages.extra, the reactor-classpath topology — so those can't catch staged-path bugs at all.
testkit: osgi/runtime/junit-testkit/.../OutOfContainerFrameworkExtension.java + container/
InContainerJUnitRunner.java.)

## USER DECISION (2026-06-30): KEEP staging, FIX THE GATE FIRST

Not revert. The plan: (1) close the gate hole — make an in-container/staged-boot test EXERCISE the
cdk8s/jackson synthesis path so it REPRODUCES "JavaTimeModule not a subtype" as a BUILD failure;
THEN (2) fix on that basis. systematic-debugging: get a reproducing test before the fix.
The likely fix direction (from the codebase pattern): a single jackson carrier bundle that
`-includeresource;lib:=true` ALL jackson jars (core/databind/annotations/dataformat-yaml/jsr310/
snakeyaml) on ONE Bundle-ClassPath, exporting the packages — so the ServiceLoader resolves within
that one classloader. AND stop staging slf4j-api (pax provides org.slf4j in-framework). But confirm
via the reproducing test first.

## Production trigger to exercise in the reproducing test

The failing path: "cdk8s setup" topic → DefaultManifestSynthesisService
(osgi/domains/manifests/manifests-core/.../DefaultManifestSynthesisService.java). jackson module
registration lives in manifests-core YamlMapper.buildMapper() (line 182) and/or
SopsAgeMaterialResolver / RuntimeRke2ConfigManifestsUnit. The in-container assertion must call the
ManifestSynthesisService (already published per EmbeddedBundlesBootTest) on a real synthesis so
findAndRegisterModules runs against the STAGED jackson-databind.

## State / commits

Increment commits on feature/cluster-edge: 8bc21cce(t1) 17e67a8a(t2) 843d3220(t3) fb1decb9+441105e4(t4)
f10a81b7(t5 controlplane WARN) caea2d7b(manifests-cli WARN, the final-review twin fix). Plan:
wip/plans/2026-06-30-realm-library-isolation.md. Spec: 3aaa66fb. Ledger:
.superpowers/sdd/progress.md. The deployed jar (exec/seed-master/target/seed-master-*-exec.jar) was
built at the green gate; it is the artifact pulumi preview boots and that fails.

Build gotcha (cost a loop): building the maven-embed-staging-ext aggregator while the extension is
ACTIVE in .mvn/extensions.xml self-poisons (emits empty bnd-read jar). Disable in .mvn → reinstall →
re-enable. See [[realm-library-isolation-state]] [[osgi-staging-extension-chantier]]
[[reconsider-choices-when-revisiting]].

## NEXT (resume here)

STEP 2 DONE (2026-06-30): the reproducing test EXISTS and is RED. Added
`embeddedSynthesisRunsTheCdk8sSetupPathOnTheStagedJackson` to EmbeddedBundlesBootTest — it
`synthesize(ManifestSynthesisRequest.ephemeral())` on the staged bundles and fails with exactly
`TopicFailure cdk8s setup: ... JavaTimeModule not a subtype` (same stack as pulumi.log:
org.cdk8s.App.<init> → JsiiObjectMapper.findAndRegisterModules → ServiceLoader<Module>). Run it with
`./mvnw -pl :seed-master -am package -DskipTests=false -Dtest=EmbeddedBundlesBootTest -Pall-worlds`
(package phase REQUIRED — stage-embedded-bundles binds to generate-resources and copies the packaged
sibling bundle jars; `test` phase alone fails reactor resolution). The gate hole is closed.

STEP 3 (now): make it GREEN.
- Fix: jackson single-carrier-bundle (Bundle-ClassPath, all jackson jars incl. jsr310) + un-stage
  slf4j-api; OR (fallback) revert jackson to flat keeping Task 4. Re-run the reproducing test GREEN +
  the full gate + ask the user to re-run pulumi preview.
