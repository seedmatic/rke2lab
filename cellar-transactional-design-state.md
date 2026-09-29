---
name: cellar-transactional-design-state
description: "DONE (2026-07-16f, commit e5b058386) — transactional cellar overlay + in-container resolution fix, incus+bbox green 3/3. Remaining: user's -Pall-worlds full build."
metadata:
  type: project
---

**★★ DONE (2026-07-16f) — committed e5b058386 on feature/cluster-seed-scenario.**

The cellar work is LANDED. Both regressions from the a1bfbf37c checkpoint are fixed and the cellar is
a real transaction. incus-bdd-test + bbox-bdd-test in-container: 3/3 green each (isolated build). The
2 "FAILED" lines in output are the deliberately-failing scenarios (build-fails / row-refused) played
in-container — the runbook's FAILED status, NOT JUnit failures.

WHAT LANDED (e5b058386, code+nothing-else — .claude and Pulumi.* left out, per checkpoint discipline):
- *In-container resolution* (the runner would not boot for ANY withJUnitRunner proxy):
  (a) COUTURE — `@SeedScenario` + `ScenarioCellarExtension` moved base → `.container` (the exported
      in-container half; a scion imports @SeedScenario and bnd follows @ExtendWith to the extension,
      so both must be reachable; the extension touches base seam only in private bodies → no leak).
  (b) ORDRE — `beforeAll` installs ALL then starts ALL (two passes), tolerating a package-only bundle
      (scenario-engine, no Activator) whose imports arrive with the host closure in the test body.
      Install order now irrelevant (the user's key insight).
  (c) `com.tngtech.jgiven.impl` → OPTIONAL in scenario-engine bnd (a pure-JUnit proxy installs the
      runner without jGiven, never triggers the cellar).
  (d) `resolve()` post-mortem skips optional requirements (it was hiding the real cause).
- *Cellar = full read-write OVERLAY* (the user's real ask, was an accumulator): store→tag (write
  side); `fetch` (both peek + timeline) reads the run's own set FIRST, durable fallback (read-your-
  writes); `withdraw` records a TOMBSTONE Entry (durable is append-only history — mark empty, never
  destroy), replayed at drain as a durable withdraw; `neighbours` pure delegation. Drain replays the
  set IN ORDER (store→store, tombstone→withdraw). `Entry` gained `boolean tombstone`; `entriesOf` is
  now package-private (drain is sole external reader). The 2 scion tests read back via the GENERIC
  `cellar.fetch(parcel, coordinate, type)` over a ScenarioCellar wrapping the rebuilt model — NOT by
  decoding tags (answers the user's very first question "pourquoi pas les méthodes génériques").
- *Shared driver* `InContainerScenarios.drive(felix, runnerFqn, provisioner)` — the resolve→start→
  run→map block dedup'd across ~10 proxies; each keeps a `Provisioning` lambda (fixtures + closure +
  startWholeGraph). `@FrameworkLog(WARNING)` uniform on all proxies (flip to DEBUG to troubleshoot).

REMAINING: the user runs the full profiled build `-Pall-worlds,nxmatic -DskipTests=false` (their
profile, not mine — seed-master's stage-embedded-bundles needs it; it fails in an unprofiled isolated
build, NOT a code regression). The other migrated proxies (cluster/systemd/doctor/manifests
-bdd-test) compile clean isolated but were not RUN here — the full build exercises them.

---
**★★ (2026-07-16e) blocker DIAGNOSED — SUPERSEDED by the DONE state above; kept as the reasoning trail.**

★ DIAGNOSTIC COMPLET (2026-07-16e), remplace le "FIX RETENU" fragment ci-dessous (écarté) :
- CAUSE : commit a1bfbf37c a introduit `ScenarioCellarExtension.currentModel()` →
  `import com.tngtech.jgiven.impl.ScenarioHolder`. Avant, scenario-engine (main) ne référençait
  `com.tngtech.jgiven.impl` NULLE PART (seul `report.*`, tous `resolution:=optional` dans son bnd —
  exprès, pour que le runner résolve dans des mondes sans jGiven). Désormais bnd calcule un
  Import-Package MANDATORY sur `com.tngtech.jgiven.impl`. bnd.bnd INCHANGÉ vs HEAD — c'est le CODE.
- PAS un package privé : jgiven-wrap EXPORTE déjà `com.tngtech.jgiven.impl` (Export-Package:
  com.tngtech.jgiven.*, MANIFEST vérifié, avec uses:). Donc "fragment qui troue un privé" = FAUX
  problème, piste ABANDONNÉE.
- LE VRAI BUG = ORDRE dans `OutOfContainerFrameworkExtension.beforeAll` (lignes ~333-340) :
  étape 1 `classpathBundles` → `installFromClasspath(x).start()` installe ET DÉMARRE scenario-engine
  (il est dans `JUNIT_RUNNER_BUNDLES`, donc bundle [22]) ; étape 2 `reactorBundles` →
  `install(WRAP_BSN).start()` pose jgiven-wrap APRÈS. Donc scenario-engine[22].start() tente de
  résoudre jgiven.impl AVANT que jgiven-wrap soit posé → BundleException → beforeAll avorte →
  jgiven-wrap n'a jamais d'id (absent de la trace WIRE, qui s'arrête à junit-jupiter-engine[21]).
- PORTÉE : les 10 users de `withJUnitRunner` (tous installent scenario-engine[22]). incus[37]+bbox[42]
  sont les SEULS -bdd-test que le réacteur `-pl :seed-master -am` compile → seuls eux ont tourné et
  échoué (identique). doctor/cluster/systemd/manifests -bdd-test + les 3 `builder()`-nu
  (ClusterEdgeBoot, DbusSystemdEdgeBoot, DoctorContract) n'ont PAS tourné → 0 preuve, casseraient pareil.
  Masqué depuis a1bfbf37c car ce réacteur ne compile pas les -bdd-test.
- STAGING sous META-INF/bundles ÉCARTÉ (dangereux) : les -test sont des FRAGMENTS bnd, code en
  src/main→target/classes, et bnd construit le jar depuis target/classes → stager jgiven-wrap+queue
  là ⇒ bnd les EMBARQUE dans CHAQUE jar de fragment (9-10 copies). `META-INF/bundles` marche pour les
  EXEC-JARS (uber-jar unique), pas pour des fragments multiples. `BundleIndex.ofStagedBundles` existe
  (précédent BootPlannerTest) mais ne s'applique donc pas ici.
- DUPLICATION CLASSPATH (jgiven-wrap embarque 556 .class = jgiven-core amont) : elle EXISTE déjà dans
  les 9 BDD (jgiven-wrap `test` + jgiven-junit5 via bundle-test-parent+transitif scenario-engine),
  mais INERTE : byte-identique (Stage.class même SHA 2a79e04a…), version 2.0.3 UNIQUE via BOM → quel
  que soit le jar que le classloader plat choisit, même bytecode. jgiven-wrap n'est de toute façon
  jamais class-loadé par la JVM de test — juste localisé comme FICHIER par `locateBySymbolicName`
  puis remis à Felix. NE PAS traiter maintenant (préexistant, pas la régression).

★★ FIX RETENU (user, 2026-07-16e) = MINIMAL, "ordre + driver", 3 gestes :
1. ORDRE : jgiven-wrap installé AVANT scenario-engine. Décidé : "withJUnitRunner porte jGiven" →
   `withJUnitRunner()` installe la clôture jGiven (byte-buddy/guava/gson/paranamer/jansi/jakarta +
   jgiven-wrap) AVANT `JUNIT_RUNNER_BUNDLES` (qui contient scenario-engine). Runner auto-suffisant.
   NB factoriser : cette liste est déjà dans `JGivenTestkit.felix()` — éviter la double source.
2. 3 `builder()`-nu : leur donner jgiven-wrap `<scope>test</scope>` comme les 9 BDD (ils appellent
   tous withJUnitRunner donc installent scenario-engine[22] → ont besoin de jGiven au classpath pour
   que withJUnitRunner le localise). classpath INCHANGÉ pour le reste (dup inerte conservée).
3. DRIVER PARTAGÉ dans scenario-engine base pkg (host-side, jGiven-agnostique, atteignable par les 10
   car tous dépendent de scenario-engine ; PAS dans pipeline-testkit — les builder()-nu n'en dépendent
   pas). Signature : `drive(felix, RUNNER_FQN, Function<felix,Provisioning> provision)` où
   `Provisioning(Bundle host, List<Bundle> toResolve, boolean startWholeGraph)`. Absorbe le bloc
   dupliqué 6-7×: resolve→(fail avec UNRESOLVED states dérivés du runnerFqn)→[startAll si graph]→
   host.start()→loadClass(fqn).getMethod("run").invoke(null)→List<String>→toDynamicTest. run() est
   TOUJOURS no-arg (vérifié 7 drivers) → pas d'Optional<args>. Message d'échec DÉRIVÉ du runnerFqn.
   Variance capturée par la lambda : #fixtures (1 scion / 2 doctor), args de installImportClosureOf
   (host seul / contract,host,core.fragment()), startAll (seul manifests-bdd = graphe SCR réel).

---


State since 16c (all UNCOMMITTED, on top of a1bfbf37c): scion migration DONE in code —
- `@SeedScenario` composite created (`scenario-engine`, = `@ExtendWith(JGivenExtension)` +
  `@ExtendWith(ScenarioCellarExtension)`; NOT `@Scenario`, clashes with jGiven's `impl.Scenario`).
  `@SeedRuntime` stays separate, only the host root wears it (owns the connection).
- `ScenarioCellarExtension.resolve` is DUAL-REALM: host-flat → class-store connection; in-container
  scion → `ScenarioRegistry.of(instance).require(type)` (the route resolveCellar used before).
- `CellarReceiver<C extends Cellar>` generic (SeedReceiver pattern): bbox binds `<Cellar>` (store only),
  incus binds `<ScenarioCellar>` (reads transactionId() for the sub-sow). Extension casts unchecked.
- bbox `BboxReconciliationScenario`: `@SeedScenario`, `implements CellarReceiver<Cellar>`,
  `@MonotonicNonNull Cellar cellar` + receiveCellar, dropped `resolveCellar()`, `the_harvest_is_stored(cellar, …)`.
- incus `IncusProvisionScenario`: `@SeedScenario`, `implements CellarReceiver<ScenarioCellar>`,
  `@MonotonicNonNull ScenarioCellar cellar`, dropped resolveCellar(); `consulting_manifests_through(broker,
  soil, cellar.transactionId().orElse(""))`, When has `@ExpectedScenarioState String txId`, sub-sow
  `broker.sow(RunbookCoordinate("manifests"), amended, txId.isEmpty()?empty:of(txId))`.
- `ScenarioCellar.transactionId()` now returns `Optional<String>` (empty = non-transactional play).
- Tags converge on `ScenarioTag` interface (type()/of()); `GraftTag implements ScenarioTag`; the cellar's
  tags are the NESTED enum `ScenarioCellar.Tag` (ENTRY, TRANSACTION) — the cellar is the SOLE tag writer.
- `ClusterSeedScenario`: condensed to `@SeedScenario` + `@SeedRuntime`.
- test call-sites fixed: `BboxBddScenarios.run(Optional.empty())`, `IncusBddScenarios.run(input, Optional.empty())`.

Host build `./mvnw -pl :seed-master -am clean package -DskipTests=false` = BUILD SUCCESS, 23 tests green
(but does NOT build the -bdd-test modules — they aren't seed-master deps).

**★ THE BLOCKER (real, runtime OSGi) — solve first after compaction:** `IncusBddInContainerTest` fails at
`OutOfContainerFrameworkExtension.beforeAll` with `BundleException: Unable to resolve scenario-engine:
missing requirement osgi.wiring.package (osgi.wiring.package=com.tngtech.jgiven.impl)`. CAUSE:
`ScenarioCellarExtension` uses `com.tngtech.jgiven.impl.ScenarioHolder` (to read the current thread's
ReportModel lazily) → bnd computes a MANDATORY Import-Package on `com.tngtech.jgiven.impl` (a jGiven
INTERNAL package), unsatisfied in-container. FIX OPTIONS (pick after compaction): (a) don't touch
`impl.ScenarioHolder` — get the model another way that doesn't import an internal pkg; (b) make the bnd
import optional/dynamic for `com.tngtech.jgiven.impl` in scenario-engine's bnd (like jgiven-junit5 was
kept off Import-Package until the seed vertical landed); (c) ensure the jgiven bundle EXPORTS
`com.tngtech.jgiven.impl` (it may not, being internal). Verify which via the staged jgiven bundle's
manifest. This is the "levé par construction" timing path — the model access is the sensitive bit.

★★ FIX RETENU (user, 2026-07-16d) — un FRAGMENT jGiven qui troue CHIRURGICALEMENT: contribuer un fragment
bundle (`Fragment-Host` sur le bundle jgiven → il a accès aux PRIVATE packages de jGiven, privilège du
fragment), MAIS qui n'exporte PAS `com.tngtech.jgiven.impl` entier. Le fragment contient un WRAPPER (code)
qui, depuis l'intérieur du host jgiven, atteint `ScenarioHolder.get()...getModel()` et ré-expose JUSTE
l'accès au ReportModel courant dont on a besoin — un mélange code + directives bnd. Donc: fragment = le
véhicule (accès internals), l'export = chirurgical (notre seul besoin, pas tout leur internal).
`ScenarioCellarExtension` dépend alors du wrapper du fragment, plus de `impl.ScenarioHolder` en direct, et
scenario-engine n'importe plus le package interne. NB ce n'était PAS "wrapper dans notre api" ni "exporter
le package entier" — les deux ont été écartés ; c'est bien un fragment + trou spécifique. Chercher un
précédent Fragment-Host dans les bnd du repo au retour.

**★ ALSO PENDING (semantic, after the blocker):** the in-container tests assert `cellar.stored.size()==1`
on the RecordingCellar — but the scion now store→TAGs (it's FRAGMENT in its isolated test, never drains),
so the RecordingCellar (now only the durableReads side) receives NO store. The test must instead assert the
`ScenarioCellar.Tag.ENTRY` tag on the reaped runbook. Decision pending (was mid-AskUserQuestion): verify
tag vs play-as-ROOT-to-drain vs rethink. RecordingCellar stays as the durableReads registration
(registerService(Cellar.class) is NOT dead — the injected cellar's durableReads resolves it).

Files: ScenarioCellar/ScenarioCellarExtension/SeedScenario/CellarReceiver/RunRole/RunRoleSeed/TxIdSeed/
ScenarioTag in scenario-engine; the 5 *BddScenarios.run(+txId); the 10 handlers; SeedRun(+txId); Main(+UUID
+RunRoleSeed+TxIdSeed); Gardening.over/sow(+txId); SowAndGraftStage(+txId); bbox/incus scenarios + tests.

---
**★★ (16c) checkpoint COMMITTED `a1bfbf37c`; scion migration (D) then left.**

Since 16b: RunRole/txId fully wired + committed. RunRole seeded by Main (ROOT) via RunRoleSeed; the
extension reads it (default FRAGMENT — a sown scion opens its own session, root's store doesn't reach
it). txId = root-minted UUID on SeedRun, threaded Main→GIVEN→SowAndGraftStage→Gardening.sow→broker.sow;
the 5 RunbookHandlers relay it into the in-container run via TxIdSeed; the ScenarioCellar (its
lifecycle-mate) posts it as the TRANSACTION tag (the cellar is the SOLE tag writer, not the extension).
Tags now converge on a ScenarioTag seam interface (type()/of()): GraftTag (LIVE_ROOT) + nested
ScenarioCellar.Tag (ENTRY, TRANSACTION) — no magic-string. `SeedCodec` stays `new` (see
[[document-codec-instance-in-2d-backlog]] — treated in 2D, not an oversight). Commit a1bfbf37c staged
code+docs only (Pulumi.*/.claude left out). All modules compile; ton packaging-with-tests passes.

LEFT — step D only (migrate scions off resolveCellar → injected cellar, store→tag ACTIVE):
- bbox `BboxReconciliationScenario`: implements CellarReceiver + field; drop `resolveCellar()`
  (registry lookup at :195); the `@Hidden Cellar` steps (`the_harvest_is_stored`) take the injected
  cellar. RecordingCellar mock (bbox-bdd-test) + the test's `registerService(Cellar.class)` at :148
  becomes dead (inject via CellarReceiver instead).
- incus `IncusProvisionScenario`: same; PLUS `the_manifests_are_cultivated` reads
  `cellar.transactionId()` and passes `Optional.of(txId)` to `broker.sow(RunbookCoordinate("manifests"),
  amended, …)` (closes transitive correlation — the sub-scion inherits the tx). Note :159
  `addTag(GraftTag.LIVE_ROOT)` STAYS (narration axis, not cellar).
- `ClusterSeedScenario` implements CellarReceiver (host root stores via the injected cellar in THEN).
- Then build gouvernance (`-Pall-worlds,nxmatic -DskipTests=false`, spec-coverage) + fix regressions.

**★★ (pre-16c) engine coded & compiling; wiring + migration left.**

DONE this session (compiles, not committed): (1) `SeedBroker.sow`/`SeedHandler.handle` gained
`Optional<String> txId` — threaded through `DefaultSeedBroker`, all 10 handlers (5 RunbookHandlers receive
it, 5 reflectors ignore), 2 sow call-sites + test mocks (RecordingBroker). (2) Docs aligned:
`seed-broker-spec §cellar-transactional` (RunRole discriminant + lifecycle), `atlas/seed.adoc` Diagram S
rewritten + supersession NOTE answering the dependsOn/FRUIT question, `host-cellar-realisation-spec
§every-scion-contributes` deferral note. (3) In `scenario-engine`: `ScenarioCellar` (store→tag Entry(parcel,
envelope), fetch/neighbours delegate to a lazy durable `Supplier<Cellar>`, model read lazily via
`ScenarioHolder`), `container/RunRole` (ROOT|FRAGMENT enum + STORE_KEY), `container/CellarReceiver`
(injection hook, twin of SeedReceiver), `ScenarioCellarExtension` (Before: inject cellar if CellarReceiver;
After: if ROLE=ROOT + no exception, resolve OpaqueCellar via BaseWorldExtension.CONNECTION connection,
drain ENTRY_TAG_TYPE tags → OpaqueCellar.store). pom: added seed-broker-port + seed-broker-codec deps.

LEFT (4 steps): (A) `ClusterSeedScenario` `@ExtendWith(SeedRuntime + ScenarioCellarExtension)`, implements
CellarReceiver, GIVEN stops `Gardening.open()` → consumes connection from BaseWorldExtension class-store.
(B) Seed RunRole: Main + each `*BddScenarios.run` seed ROOT; the sow-graft path seeds FRAGMENT. (C)
Propagate real txId: Main mints ROOT UUID via SessionSeed-like; RunbookHandlers relay txId into the
in-container run (BddScenarios.run(input, txId) + seedInput); replace the provisional `Optional.empty()` in
`Gardening.sow` + `IncusProvisionScenario` (2 sows). (D) Migrate bbox/incus scions off
`ScenarioRegistry.require(Cellar.class)` → injected cellar via CellarReceiver, `cellar.store` stays (now
tags); fold `IncusProvisionScenario:159` addTag into store; RecordingCellar mocks become local tag cellars.
Then build gouvernance (`-Pall-worlds,nxmatic -DskipTests=false`, spec-coverage).

KEY DESIGN (graved, do not re-litigate): ScenarioCellar UNIVERSAL + injected (not registry-resolved);
tags-only, ROOT drains at boundary (atomic); RunRole from LAUNCHER (not type — a scion is ROOT in its own
in-container test, FRAGMENT when sown); txId = root-minted UUID for AUDIT only (no longer discriminant, no
map); lifecycle = host adopts BaseWorldExtension (owns the connection, fixes a pre-existing leak); drain
resolves OpaqueCellar via the registry (one resolution route). Whiteboard `.claude/claude-preview.adoc` is
current. Old form below is SUPERSEDED — kept only as the "avant".

---
OLD (pre-2026-07-16b, SUPERSEDED — was: design converged, code not started):

The transactional cellar — a jGiven-report-model-backed, tag-supported transactional face over the
two-face cellar ([[seed-broker-contribution-model]]; OpaqueCellar seam + typed Cellar via CodecCellar,
host impl PulumiCellar). Design done over a long figure-first session (user drove every load-bearing
call). Spec `docs/architecture/osgi/seed-broker-spec.adoc §cellar-transactional` REWRITTEN 2026-07-16
(old form — "subtype only the root receives", "no rollback needed" — explicitly superseded). Figures in
`.claude/claude-preview.adoc` (5 figures, whiteboard scoped to THIS topic only).

**Decisions graved (all user-settled):**
- *ScenarioCellar UNIVERSAL* — every scenario (host AND scion) gets the SAME one; it is the SOLE writer
  of a tag on the ReportModel (`store → addTag`). Overturns the earlier "role by injected type". `fetch`/
  `neighbours` delegate to durable backend (no cache).
- *begin/commit/rollback are calls to the SeedRunLedger, NOT the model* → no timing coupling with
  JGivenExtension (begin/end touch no model; commit drains tags `store` already posted; verdict from
  JUnit `getExecutionException()`, not jGiven's sealed ExecutionStatus).
- *Shared OSGi framework* (user corrected my per-world isolation). `sow` is SYNCHRONOUS → child plays
  inside parent's sow call on host thread; graft (sow+graftUnder) is the BRACKET; depth = host-thread
  stack, NO distributed counter. What breaks is IDENTITY → a `txId` minted by root, PROPAGATED by the
  graft through the sow TRIGGER, used by child as LDAP key to resolve its ledger/parcel/cellar.
- *(A) isolation only* — single all-or-nothing flush at root, spec atomicity kept; NOT sub-transactions.
- *tag not attachment* for the durable envelope (round-trip verified, narrated).
- *SeedRunLedger is the ScenarioCellar FACTORY* (single source: holds txId+model+PulumiCellar);
  `SeedRunLedgerExtension` is the JUnit BRIDGE — open(txId) at BeforeTestExecution, inject cellar into a
  FIELD via TestInstancePostProcessor (the SessionSeed pattern — NOT a ParameterResolver: jGiven narrates
  every @Test param, verified in ArgumentReflectionUtil 2.0.3), commit/rollback at AfterTestExecution.
  Cellar reads the model LAZILY (first store) → post-processor order vs jGiven is a non-issue.

**4-step foundation to CODE next (nothing committed):**
1. `SeedRunLedger` (seam, `osgi/foundation/seed-broker-port`) — open/commit/rollback + txId map, the factory.
2. `ScenarioCellar extends Cellar` (`osgi/runtime/scenario-engine`, has jgiven compile dep; needs
   seed-broker-port added to its pom) — store→addTag, fetch→delegate, lazy model.
3. `SeedRunLedgerExtension` (scenario-engine) — the 3 callbacks.
4. txId propagation through the sow trigger (graft side — `SowAndGraftStage`/`Gardening.sow`/`ScenarioGraft`).

Key files: ScenarioGraft.java:91 (tag fold), IncusProvisionScenario.java:159 (the ONE dispersed addTag to
migrate), ClusterSeedScenario.Given:165-183 (where Parcel/RunGate/OpaqueCellar are published today),
CellarStage (OpaqueCellar bookends), ScenarioRegistry.require (how scions resolve). See
[[collaborative-design-method]] [[cluster-seed-execution-state]] [[options-always-as-c4-diagrams]].
