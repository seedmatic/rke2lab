---
name: nullaway-jdk25-recipe
description: The working error-prone + NullAway version pair and javac module-export recipe to run null-analysis on this repo's JDK 25 toolchain — plus the -Pnullaway opt-in profile in build-parent.
metadata:
  type: reference
---

Running NullAway (which reads JSpecify `@NullMarked`) on this repo's **JDK 25** (Zulu 25) toolchain,
established 2026-07-01 during 2G. The version pairing is the fragile part.

## THE WORKING PAIR (verified by smoke-test + real Maven build)
- error-prone **2.50.0** (`com.google.errorprone:error_prone_core`)
- NullAway **0.13.7** (`com.uber.nullaway:nullaway`)
- JSpecify **1.0.0** (`org.jspecify:jspecify`)

WRONG pairs found the hard way:
- ep 2.42.0 → works but older than the released 2.50.0.
- ep 2.50.0 + nullaway **0.12.10** → `NoClassDefFoundError:
  com/google/errorprone/predicates/type/DescendantOf` (ep 2.50 removed/moved that internal; 0.12.x
  expects it). NullAway 0.13.x is the aligned line for ep 2.50.

## WHY IT'S TRICKY ON JDK 25
error-prone hooks javac internals, so it needs the full `--add-exports`/`--add-opens` set or it dies:
- missing them → `IllegalAccessError: ... module jdk.compiler does not export
  com.sun.tools.javac.processing`.
- manual `javac` also needs `-XDcompilePolicy=simple -XDshould-stop.ifError=FLOW` or it throws
  `InvalidCommandLineOptionException: --should-stop=ifError ... pass FLOW`.
Required javac module flags (10 exports + 2 opens): api, file, main, model, parser, processing, tree,
util, code, comp (exports); code, comp (opens). The maven-compiler-plugin passes them as `-J...`
compilerArgs — they only take effect because the flox **toolchains-plugin forks javac** (`fork=false`
in config, but log says "javac [forked ...]"), so the `-J` reach the forked compiler JVM.

## HOW IT'S WIRED (build-parent)
- jspecify declared once in **build-parent** `<dependencies>` (provided) → inherited by ALL modules
  (osgi via bundle-parent, host/exec via build-parent). Removed the duplicate from bundle-parent.
- `errorprone.version`/`nullaway.version` props in build-parent.
- Opt-in profile **`-Pnullaway`**: overrides maven-compiler-plugin with error-prone+nullaway as
  `annotationProcessorPaths` + the compilerArgs (compilePolicy, should-stop, the `-Xplugin:ErrorProne
  ... -Xep:NullAway:WARN -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true`, and
  the 12 `-J` module flags). OFF by default — normal builds are untouched.
- `OnlyNullMarked=true` scopes analysis to `@NullMarked` packages only → today doctor + world-gateway;
  every other module compiles clean regardless.
- No `dependency:analyze` execution is bound anywhere in the parent chain, so jspecify `provided`
  (referenced only by package-info) is never flagged "unused declared dependency". If that plugin is
  ever bound, add jspecify to `ignoredUnusedDeclaredDependencies`.

## COMMANDS
Look at warnings on the annotated scope:
`flox activate -- ./mvnw -Pall-worlds,nullaway -fae test-compile -pl :doctor-records,:doctor-core,:doctor-port,:doctor-spi,:world-gateway,:gateway-document-codec -am -Dmaven.build.cache.skipCache=true`

Confirm the gate bites: drop a `String boom(@Nullable String s){return s.trim();}` into an annotated
package, `-Pnullaway compile` → `[NullAway] dereferenced expression s is @Nullable`.

## STATE (2026-07-01)
Step 1 (doctor + world-gateway PROD): 0 warnings — null-clean.

Step 2 (ALL OSGi modules @NullMarked, 73 packages): extension done, build + all tests green,
severity still WARN. `-Pnullaway package` now surfaces **123 NullAway warnings** to triage:
- split ~**60 prod / 63 test-fixture** — many test warnings are tests passing `null` ON PURPOSE to
  exercise guards (e.g. GatewayVocabularyTest's 4) → annotate the test params `@Nullable` or exclude
  test sources, NOT bugs.
- by kind: 57 `passing @Nullable where @NonNull` (mostly tests), 17 `initializer does not guarantee
  @NonNull field` (uninitialized fields — OSGi @Component / lazy init, need @Nullable field or
  lazy-init handling), 16 `returning @Nullable`, ~13 `dereferenced expression` (the REAL bug
  candidates — look first).
- by module: doctor 51, manifests 43, boot 11, junit-testkit 5, world-gateway 4 (all the test),
  netplan 4, launcher 2, unitrepo 2, systemd 1.

bnd gotcha hit + fixed: the OSGi-wide `Import-Package: !org.jspecify.annotations, *` OVERWRITES an
Import-Package that a bnd `-include:` contributes. The 3 jGiven `-test` fragments (doctor-core-test,
manifests-core-test, pipeline-probe-test) include `jgiven-fragment.bnd`, whose forced
`com.tngtech.jgiven.impl*` imports got clobbered → fragment stuck INSTALLED (state 2), not RESOLVED
(4), JGivenTestkitGuardTest failed. FIX: put `!org.jspecify.annotations` as the first entry INSIDE
the shared `jgiven-fragment.bnd` Import-Package list (single source), leave the 3 fragments
include-only. `-includeresource` bnd (cluster-edge, manifests-cdk8s, dbus-systemd-edge) have no own
Import-Package, so the inline `Import-Package: !org.jspecify.annotations, *` is safe there.

## TRIAGE PROGRESS (2026-07-01, commits 57b10c27, 55a23503, 1f807570)

The DISCIPLINE the user drove hard (adopt it, don't re-litigate each time):
- **Eliminate the null, don't document it.** Our return/field that can be absent → `Optional`
  (says the truth, chains via map/orElse, no NPE path). NOT `@Nullable` to hush the checker.
- **`Optional` as a FIELD is good** — even in enums (`InstallPhase` constants pass
  `Optional.empty()`/`Optional.of(x)`), the accessor becomes a trivial getter.
- **`Optional` as a PARAMETER is fine here too** — under `@NullMarked` the Optional param is
  itself @NonNull, so `Optional.empty()` is explicit + chainable; the user PREFERS it over a
  nullable param. (Setter `manifestDomainPolicy(Optional<..>)`, `buildDomainRegistry(Optional<..>)`.)
- **`@Nullable` reserved for**: externally-imposed contracts we don't own, third-party-null
  absorbed at the boundary (wrap with `Optional.ofNullable` ONCE), genuine builder scratch fields.
- **No `x == null` / `Objects.isNull` in our logic** — it's a smell now; the only legit null
  contact is `Optional.ofNullable(thirdPartyCall())` at the boundary. Prefer chaining
  (`.map(...).orElse(...)`) over `isEmpty() || equals(...)` boolean disjunctions.
- **Don't widen a `parse(String)` to `@Nullable` for a test's sake**: analyze the PROD callers.
  If prod always passes non-null (enum slug, `.get()`, `@NullMarked` wire component), the contract
  stays `@NonNull` and the impossible `parse(null)` TEST assertion is removed instead.

Config lever (the biggest debruiter): `-XepOpt:NullAway:ExcludedFieldAnnotations=` now lists
jGiven (`ProvidedScenarioState`/`ExpectedScenarioState`/`ScenarioState`), Mockito
(`Mock`/`InjectMocks`/`Captor`), and OSGi SCR (`org.osgi.service.component.annotations.Reference`).
Framework-injected fields are not "uninitialized".

DONE — prod null-clean: **doctor, world-gateway, and the manifests slice above** (InstallPhase,
ManifestSynthesisRequest builder, buildDomainRegistry, ApiObjectRef+NamespaceRef/ConfigMapRef/
SecretRef+Cdk8sApiObjectResolver). EntryFailure/EntryReadException `when`→Optional, `failure()`
@NonNull accessor (not JDK's @Nullable getCause).

NEXT SLICE (remaining ~28 manifests prod + netplan 4 + launcher 2 + unitrepo 1 + systemd 1 + ~45
test):
- **manifests "initializer does not guarantee" family** = the CDK8s/pipeline LAZY-INIT pattern:
  pipeline scratch-State inner-class fields (DefaultManifestSynthesisService$SynthesisPipeline$State
  app/chart/…, BootstrapInfrastructureSynthesizer's State onFailure/toolsStage/bootstrapStage),
  the `*Assets` builder fields (KdnsAssets, FloxRuntimeAssets, NriPluginArchiveAssets,
  RuntimeCloudConfigAssets, RuntimeDaemonsetScriptPolicyAssets), ReplicatorManifestsUnit.replicatorVersion
  (set at doSynthesize entry, read after), SystemdSynthesisContext, ManifestSynthesisResult,
  BootstrapStage/ToolsStage. These are mutable scratch state assigned across stages — `Optional`-as-
  mutable-field is an anti-pattern, so `@Nullable` field + local-narrow is the honest tool here (this
  is the builder-scratch exception, NOT our-API-return). DECIDE per site.
- **manifests `passing null` (11) + `returning null` (6 done? recheck)**: UpstreamYamlInclusion
  (namespace/annotations from parsed YAML — third-party map, absorb with ofNullable→Optional),
  KdnsManifestsUnit:375-376 (null literals), DefaultManifestExplodeService.textOrNull /
  UpstreamYamlInclusion.stringField (return Optional, absorb JsonNode/Map.get null once).
- **netplan**: Net2PlanEndpoint (baseUri initializer + a returning-null), ClusterNetworkBlueprint
  builder fields (clusterName/nodeName — builder scratch → @Nullable).
- **launcher**: BootPipeline.onFailure initializer, LaunchConfig passing-null.
- **unitrepo**: UnitResolver.java:56 passing `filter` null.
- **~45 test warnings**: mostly ConsultationReportCodecTest/DoctorRecordsTest raw-map builders
  (`raw.get("plan")` @Nullable → the test builds maps by hand; cast or restructure), and
  OutOfContainerFrameworkExtension/ScrDiagnostics (testkit framework fields/returns). Lower priority.

Re-measure after each slice with:
`./mvnw -Pall-worlds,nullaway -fae package -pl :seed-master -am -Dmaven.build.cache.skipCache=true -DskipTests=true`
(use `package` + `-DskipTests=true`, NOT test-compile — the seed-master staging goal needs package;
NullAway warnings still print at compile). Then flip `-Xep:NullAway:WARN`→`ERROR` once 0.
See [[world-gateway-2e-annotations-plan]].

## STATE 2026-07-01 (later session — gate now default-ON, exec in progress)

**MEASUREMENT GOTCHA (cost me a false "0 prod"):** `package` WITHOUT `clean` lets the build
cache restore `.class` files, so javac skips modules → NullAway never analyses them. ALWAYS
measure with `clean`: `./mvnw -Pall-worlds -fae clean package -pl :seed-master -am
-Dmaven.build.cache.skipCache=true -DskipTests=true`. `clean compile` fails on seed-master
(the `stage-embedded-bundles` dependency goal needs `package`) — that BUILD FAILURE is NOT a
NullAway/code error; use `package`.

**GATE IS NOW ON BY DEFAULT (committed).** build-parent `nullaway` profile:
- Activation flipped to `<property><name>!nullaway.skip</name></property>` — ON regardless of
  which `-P<world>` is selected (no more `-Pnullaway` needed). Survives `-Pall-worlds`.
- Severity is `${nullaway.severity}` property, default `ERROR`. Override: `-Dnullaway.severity=WARN`
  to troubleshoot without breaking the build; `-Dnullaway.skip` to disable the profile entirely.
  Verified all three modes bite correctly.
- `XepExcludedPaths:(.*/src/test/.*)|(.*-test/src/.*)|(.*-testkit/src/.*)` — test sources are OUT
  of scope (passing null to exercise a guard is legit there). The gate polices the live surface.

**DONE + committed since:** ALL OSGi prod (c7bd328a), gate scope+flip (280aa73a), host `pulumi.edge`
+ boot-discovery + gate-default-on (17196cba). Generated SDK `com.pulumi.incus` (175 files) is NOT
ours — never annotated, `OnlyNullMarked` ignores it.
- boot-discovery SOURCE FIX: `BundleManifest.from` → `Optional<BundleManifest>` (was fabricating a
  null-laden record); `symbolicName`/`embed`/`fragmentHost` components → `Optional`;
  `BundleLocation.readManifest` → `Optional<Manifest>`. The user's principle: **fix null AT THE
  SOURCE, in our own code — that's where the error is, and it makes consumers explicit.**

**EXEC IN PROGRESS (NOT committed, ~79 warnings left of 90):** 15 `package-info @NullMarked` added
across exec (seed-master + 2 CLIs). Done via Optional: GitMetadataExtractor (extract/generateBuildId/
openRepository → Optional, head via ofNullable), SeedLog.parseNamedLevel → Optional, ConfigLoader
walk/asMap → Optional, ProvisioningTargetRegistry.getReloadPolicy → Optional.
- **UNCOMMITTED @Nullable AWAITING USER REVIEW** (user tightened the rule mid-session — see below):
  SeedLog.pulumiLogSink (`volatile @Nullable`, genuine runtime-optional mutable state, now read via
  `currentSink()`→Optional), SeedNodeBootstrapWatcher.Builder (6 scratch fields + 3 helper params
  `@Nullable Object`), ConfigLoader.asMap param. User may want these revisited.
- **IncusResourceBootstrap (45 warnings, half the total) LEFT UNTOUCHED** — it's mostly
  `BootstrapPaths.Builder` (18 fields) + `ApplyState` (10 fields) scratch → all `@Nullable` territory.
- **ResourceCreationPipeline (11)** = 100% builder scratch (`PulumiResourceBuilder`/`StandaloneResourceBuilder`).

**RULE TIGHTENED MID-SESSION (user, verbatim intent):** "tu vas trop vite avec les @Nullable, j'ai
pas le temps de suivre." → **NO @Nullable without submitting it FIRST, one by one, with the why.**
Optional / third-party-null absorption stays free-hand. I go too fast on @Nullable — slow down.

**BLOCKED ON A DESIGN DECISION** (user: "on a leve un vrai probleme de design"): the
`snapshot()→Map<String,Object>` flatten-too-early problem. See [[flatten-at-edge-observation-layer]]
— to brainstorm before resuming exec. This is why exec is paused.
