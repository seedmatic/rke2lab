---
name: preview-testbed-defects-2026-07-14
description: "The first cluster-seed pulumi preview run as a testbed — 4 packaging/gate defects fixed + the I6 host-tree architectural correction (host-entry CRUD moves OSGi-side)."
metadata:
  node_type: memory
  type: project
---

## ★ UPDATE 2026-07-17 — full-doc staleness sweep DONE (all 94 .adoc audited, 3 agents)

Before coding the GROW, a full-doc audit (3 parallel Explore agents over the ~40 chantier-touching docs)
against the graved truth. Result: NO conception hole blocks the GROW — the core specs are current; the
dette was periphery. Actions taken (all doc, no code):

- *5 LEGACY docs DELETED* (they misdescribed the current arch as if current; git keeps them):
  `bdd/bdd-diagnostic-pattern.adoc` (scenario=Pulumi ComponentResource/drift model — ABANDONED; its
  live §doctor-consulted migrated into `bdd.adoc#doctor-consulted-sub-step`, its severity into
  `doctor/runbook-doctor.adoc`), `systemd/host-slot-management.adoc` (old FS slot model — fully covered
  by host-cellar), `guides/rke2lab-authored-notes-import.adoc` (other-repo provenance),
  `patterns/context-registry-architecture.adoc` (ContextRegistry/BootstrapContext — absent from code;
  its Pulumi-Output register-early/update-late discipline migrated into host-cellar § the-grow-anatomy),
  `patterns/incus-distribution-contract.adoc` (reverse-engineered v1 of the rke2lab source model).
  All inbound links repaired.
- *FIXES in live docs:* append-only→FRIDGE+withdraw (`seed-gardening-lexicon`, `gardening-model-spec`);
  `Cellar`→`OpaqueCellar` at the seam (`atlas/doctor`, `port-edge-domain-ownership`, `README`);
  removed-component names (`bootstrap-contract` scenario-as-ComponentResource block → BDD-as-engine note;
  `manifests-bdd-spec` checksum-glue → nocloud-replace wire; `integration-atlas` DomainDag* removed +
  `SeedBroker.handle(Document)` → `sow(coordinate, Cellar, SeedEnvelope)`).
- *Signature corrected everywhere:* the sow no longer carries `Optional<String> txId` — the CELLAR
  crosses (`sow(SeedCoordinate, Cellar, SeedEnvelope)` / `handle(Cellar, SeedEnvelope)`), and the
  ScenarioCellar IS the transaction (it carries txId + write-set). The transactional model is documented
  in seed-broker-spec § "The cellar is TRANSACTIONAL — a UNIVERSAL ScenarioCellar" (complete, clear).

Doc is now aligned on codebase + vision. NEXT stays: CODE the PREPARE beats (#10 secrets, #2 nocloud)
then the GROW (see whiteboard).

## ★ UPDATE 2026-07-16 — the spec reconciliation is DONE; the GROW body is CARTOGRAPHED

The "reconcile provisioning-slice ↔ I6" NEXT this memory pointed to is DONE (it happened after this
memory's 2026-07-14 date). All three specs already carry the PREPARE→GROW→VERIFY order, the flip
("scion promotes first, host grows last"), and the MENT/SELF-OBSOLETE cures: `bdd.adoc` (l.460-466),
`incus-edge-spec` (l.111-123), `provisioning-slice` (l.436-448). Do NOT re-litigate the order.

The real remaining work is CODE: restore the GROW body (deleted at `342b7c327`, was
`IncusResourceBootstrap` 3789L). Cartographed 2026-07-16 (two Explore passes), graved into
provisioning-slice §deltas + host-cellar § the-grow-anatomy:

- *#10 secrets — NOT host-only (corrected).* Was host only by accident of place (nested in
  IncusResourceBootstrap). It is a file materialisation; the token VALUE is OSGi-resolvable
  (`AuthTokenContact`, an auth-contract port with `CliAuthTokenContact` realisation). → a beat of
  incus PREPARE (`IncusProvisionScenario`, a Then after materialisation): resolve token, upsert
  `<worktree>/.secrets`, before the `worktree.dir` mount (#1) binds it.
- *#2 nocloud — NO transcode.* The 3 files (user-data/meta-data/network-config) are authored in FINAL
  form as manifests-core templates, synthesised into the `cloud-config` ConfigMap
  (`CloudConfigManifestsUnit`) with MACs substituted from netplan's `ClusterNetworkBlueprint`. The old
  `CloudConfigSecretRenderer` merely UNWRAPPED the ConfigMap envelope (+base64) and wrote byte-for-byte
  to `cloudSeedRoot`. The unwrap needs only generic YAML parse (`ManifestDocumentService.parseDocument`
  = `yaml.read(path, Map.class)`, no manifests semantics → NO coupling). Content = manifests+netplan;
  unwrap-for-the-instance = a beat of incus PREPARE. CHANNELS: FS carries the content (incus re-reads
  the synthesised ConfigMap from `runtimeCloudConfigRoot`), ReportModel carries the meta-facts.
- *GROW = host-irreducible core ONLY:* Pulumi graph #1 (Project root → Network/Profile dependsOn,
  Image fingerprint Output threaded, Instance via Output refs, `replaceOnChanges(config,config.*)`,
  `deleteBeforeReplace`) + 13 mounts #8 (source NIXOS host-view `asHostView(NIXOS)` → target
  `HostPathCatalog`; 2 non-catalog: daemonset `GUEST_ROOT_PATH`, nocloud literal) + the nocloud→replace
  wire #7 (cloud-init is the SOLE STATIC target; user-data change → instance recreate; checksum →
  `user.rke2lab.provisioning.slice.cloud-init` on instanceConfig). NOT restored: TargetChecksumPipeline/
  TargetReloadPolicy/5-target registry (I6 routes hot-reload by drift-entry + json refresh).
- *Form:* GROW is a pure-host WHEN (no sow — `com.pulumi` can't enter Felix), fetches `IncusHarvest`
  like `Cellar.fetch`, sits AFTER reconcile/promote (mount rule) and BEFORE systemd/cluster verify.
- *Causality to preserve:* materialise+secrets+nocloud+promote (scion beats) BEFORE any resource;
  then host GROW declares Project→{Network,Profile,Image} and binds the Instance's 13 mounts onto the
  ALREADY-PROMOTED `host.live.d`.

NEXT (code, session neuve): restore a `HostGrowStage` (the pure-host WHEN) + the incus PREPARE beats
(secrets, nocloud) + wire into ClusterSeedScenario between reconcile and systemd. See whiteboard.

## Playing the first preview was a TESTBED — it walked the whole host boot and exposed a defect chain

Running `pulumi preview --stack dev` on the seed-master exec-jar (2026-07-14) is the first time the
full path runs: `com.pulumi` boot → flat JUnit launcher (BDD-as-engine, host-side, outside Felix) →
`ClusterSeedScenario` GIVEN → the WHENs. Each failure peeled to the next; the run is a diagnostic
harness, not a throwaway. Six defects, in the order the preview surfaced them:

**D1 — gson staged bundle-only (FIXED, commit eeebd0efe).** `com.pulumi` NoClassDef'd on
`com.google.gson.Gson` at `Pulumi.run`. Regression since bbox-edge (`1c37ea7ac`): gson entered
`stagedGas()` (bbox client pulls it) AND became a direct compile dep, but `isRealmLibrary` only kept a
third-party bundle flat when a domain IMPORTS its package — bbox-edge does not import gson. Fix:
`ResolvedBundle.directlyDeclared` (grafted from the Aether graph's root children), and a
directly-declared third-party bundle is a realm library (flat + staged), guarded by the boot-stack
exclusion. See [[gson-flat-vs-staged-regression]].

**D2 — junit-platform-* staged only (FIXED, commit 1fa8e26b5).** Launcher host-side NoClassDef'd on
`HierarchicalTestEngine`. `junit-jupiter-engine` was a realm library (direct dep, flat) but its
transitive `junit-platform-engine`/`-launcher`/`-commons` were staged-only, so the flat
`JupiterTestEngine` could not load its bundle-only superclass. Fix: realm-library flag PROPAGATES
transitively over mandatory imports (`StagingClosure.propagateRealmLibraries`) — a flat library needs
its imported packages flat too. Same boot-stack guard.

**D3 — SessionSeed never registered (FIXED, uncommitted at time of writing).** Scenario played but
`SeedRun` was null ("the SeedRun was not seeded"). `SessionSeed` is BOTH the seeder (`into`) and the
`TestInstancePostProcessor` (`receiveSeed`), but `Main` used it only as seeder — it was never a Jupiter
extension on the scenario, so the post-processor never fired. Fix: `@RegisterExtension public static
final SessionSeed<SeedRun> SEED` on `ClusterSeedScenario` (single-sources the channel, `Main`
references it). A field `@RegisterExtension` is needed because the channel carries constructor state
(type + key) `@ExtendWith` can't supply.

**D4 — REALM_BOUNDARY gate blind to type=contract (FIXED, commit 147f21f34) — THE GATE THAT LIED.**
The whole point: the gate that should have caught D5/D6 at build time said nothing, not even a WARN.
Its "forbidden" (bundle-only) package set was built from `isDomain()` carriers ALONE, so
`type=contract` bundles (`manifests-contract`) never entered it — a flat class naming a contract type
leaked past. Fix: a single `isBundleSide()` = `isDomain() || isContract()` at all three decisions
(forbidden set, flat-realm exclusion, per-bundle scan). After the fix the build correctly WARNs 4 real
leaks (HostTreeHead, ManifestLinkPolicy, ControlplanePolicy, EntryGatePolicyEnforcer →
manifests.contract). Lesson: a gate keyed on one nature (`isDomain`) silently ignores the sibling
nature (`isContract`) that shares the same runtime property (staged, not flat).

**D5 — HostTreeHead (I6a, MINE) leaks manifests.contract host-flat — see the CORRECTION below.**

**D6 — EntryGatePolicyEnforcer → ManifestUpdateGate (pre-existing debt).** Same anti-pattern as D5,
documented in `controlplane/package-info.java` as a known WARN backlog. This is the crash that still
blocks the preview at the first WHEN (preflight). Not yet resolved.

## The I6 host-tree CORRECTION — host-entry CRUD is OSGi-side, in dedicated INCUS scenarios

The design (host-cellar-realisation-spec, atlas/seed Diagram V) said the HOST at grow reads the head
and writes the live/drift entries, with `HostTreeHead` folding *host-side*. The preview PROVED that
wrong (D5): `HostTreeHead` lived flat in seed-master and decoded `Host*Entry` from `manifests-contract`
(a `type=contract`, bundle-only bundle) — the very leak the bnd bans.

**The ownership — incus, not manifests (user, 2026-07-14).** First reflex was to give the host-tree
CRUD to manifests (that is where `Host*Entry` sat). WRONG: the HOST TREE (`host.N.staging.d` entire —
`rke2-manifests.d` + `k8s-daemonset.d` + `systemd.d` + cloud-config + env-overlay …) is what the INCUS
instance MOUNTS. incus owns the mount, picks the rotation slot, triggers the grow, measures the drift
of the WHOLE tree. `rke2-manifests.d` is just ONE folder among many — manifests is a CONTRIBUTOR of
content, not the tree's owner. This is the same reasoning the spec already used on the validate side:
"only incus sees the WHOLE of what the instance will mount, so only it can validate global coherence" —
so the tree's CRUD/fold/rotation/drift is incus's too.

**The FINAL design (settled with the user across the whiteboard `.claude/claude-preview.adoc`).**

1. *incus centralises the whole host-tree CRUD* — publish · fold the head · pick the slot
   (HostSlotSelector) · the two deltas (HostTreeDiffer) · promote (live/drift). Homed in a NEW
   `incus-core` (`type=model`, uniform with the six other domains' `-core`; incus had none). The
   `Host*Entry` records → `incus-contract`. manifests keeps ONLY materialising content into the SOIL.
2. *Flow INVERTS:* incus chooses the slot (it owns the tree), creates `host.N.staging.d`, then hands
   `rke2-manifests.d` as the SOIL to manifests. The host only passes `nodeRoot`.
3. *incus publishes ALL entries — manifests never touches the cellar host-tree.* manifests produces one
   folder, can't publish a whole-tree contract; incus publishes after the graft, when the tree is
   complete. The tree IS complete at the graft: `DefaultManifestSynthesisService` writes the whole tree
   under one root (systemd.d included — `synthOutdir.resolve("systemd")`); the later systemd/cluster
   scions only PROBE, write nothing. One checksum of the root covers everything (killed the false
   "systemd.d posé après incus" worry).
4. *ORDERING (user caught a correctness bug):* two distinct "grow"s — the host-tree grow (the
   PROMOTION: `jsync staging → host.live.d`, writes the entries, OSGi/FS) vs the INSTANCE grow (Shape C,
   com.pulumi, mounts host.live.d). Promotion MUST finish BEFORE the instance starts, else it mounts
   stale/empty content. All entries publish in the incus `prepare` scenario, upstream of the instance grow.
5. *Dedicated incus scenarios (via `IncusBddScenarios`, N per domain):* a `prepare` scenario grafts →
   publishes HostStagingEntry → folds → promotes (twin of IncusProvisionScenario's graft→harvest→store
   today). `prepare` and the instance grow stay DISTINCT — `prepare` will gain its own steps (cross-run
   validation, multi-scion aggregation) and diverge; the shared trio is today's coincidence.

**What this deletes:** `HostTreeHead`, `HostSlotSelector`, `HostTreeDiffer`, `HostTreeDelta`,
`HostTreeDeltaRenderer` in seed-master (the I6a/b/c host-flat code, commits 0f5ccca2 / 926c78422 /
a050cac4 / c4c97db9) + `HostTreeChecksummer` in manifests-core — all moved into `incus-core`. The host
keeps ONLY the Pulumi bind (mount `host.live.d`, I6e).

**Graved (2026-07-14):** host-cellar-realisation-spec § "CORRECTION (2026-07-14)" (full final design);
atlas/seed § the CAUTION after Diagram V; the whiteboard.

**Implementation state (IN FLIGHT — tree does NOT compile green yet).** §1 records → incus-contract
DONE (git mv + package/javadoc; manifests stops publishing — ManifestSynthesisScenario Then + its
in-container test stripped). §2 the 5 classes + HostTreeChecksummer → new `incus-core` DONE (module +
pom/bnd/package-info, java-diff-utils dep, aggregator wired, 3 tests moved, HostSlotSelectorTest's
stagingView test dropped — it tested BootstrapPaths, a host-flat concern).

**§3a + §3b DONE (2026-07-15) — the tree compiles green again, tests green.** §3a: `BootstrapPaths`
`git mv`'d whole into incus-core (the realm criterion — Felix embedded in the host JVM sees the same FS,
so the WHOLE topology is computed OSGi-side, NOT split by role — superseded the earlier "split by role"
draft above). Dead `asHostView(BootstrapConfig,…)` deleted (its caller `seedInstanceDevices` died with
the grow); the NFS translation survives as a PURE method `asAutomountView(nfsAutomount, netPrefix)` +
`automountPath(...)` (ex-`BootstrapConfig.pathOn`, now self-contained, no BootstrapConfig import).
`BootstrapConfig` cleaned: `pathOn`/`worktreeDirOn`/`asIncusConfig`/`WorktreeHost`/`normalizeAbsolutePath`
all deleted (dead); kept `localWorktreePath()` (rewritten, no pathOn) + `netPrefix()` (a §3b scalar).
§3b: the INVERSION — the incus scion reconstructs the topology in-world and picks its OWN slot.
`IncusRunbookInput` now carries `@Amendment(Amendment.WORKTREE) Worktree worktree` (a sub-record
`Worktree(worktreeRoot, clusterName, nodeName, nfsAutomount)`, twin of `ManifestsRunbookInput.LinkFacet`
— bundle-only, host fills it BLIND by role via the schema) INSTEAD of the pre-computed `materializationRoot`
SOIL; the scion's `resolveSoil(worktree)` does `fromLocalWorktree → HostSlotSelector.nextStaging →
asStagingView.manifestsRoot()`. New role `Amendment.WORKTREE` in seed-broker-port (a String constant, NOT
a crossing record). The sow pipeline widened `Map<String,String>` → `Map<String,JsonNode>` (a role may
carry a flat scalar OR a sub-record) in `Gardening.sow` + `SowAndGraftStage.sowing` (one host call-site).
incus-bdd now deps incus-core (+ build-parent dependencyManagement entry for incus-core); seed-master too
(compile scope — Main derives the live root). `IncusAmendReflectorTest` rewritten onto WORKTREE (4/4 green).

**THE RUNBOOK GOES TO host.live.d — a LIVE mutation, NOT promoted (user insight, 2026-07-15).** Butée:
the runbook needs the COMPLETE played model (post-run), but promotion is a mid-scenario beat — so it
CANNOT travel through the promotion. Resolution (user): the host writes it into `host.live.d` DIRECTLY
post-run, the twin of the instance mutating its mounted content at runtime; at the next rotation the
`drift` delta sees it as drift vs the static staging — expected. This DISSOLVED the "how does the scion
return its chosen slot to the host" knot: `host.live.d` is a FIXED path (`BootstrapPaths.liveRoot()` =
`clusterNodeRoot/host.live.d`) the host derives from the scalars alone — no slot returned. `Main` renders
there; `ClusterSeedScenario.LAST_STAGING_ROOT`/`lastStagingRoot()` DELETED. Graved in host-cellar §
"the runbook json has a runtime role" (the CORRECTED paragraph) + the Still-open bullet (now RESOLVED).

**cloud-config replay — OPEN nuance for §4/#7 (user doubt, 2026-07-15).** staging = the STATIC view of
resources needed to bootstrap master; nocloud came OUT of it "because it needs a reboot" — BUT the user
doubts this: cloud-init has per-boot/`always` modules that CAN be replayed live (`cloud-init clean+init`
or `cloud-init single`), no reboot; only strictly per-instance-already-done modules need a fresh boot (or
`clean`). So delta #7's "cloud-config → always recreate the instance" is too coarse — it's "depends on the
module touched": the replayable-live part joins the `json` runtime-refresh mechanism (like systemd), only
the strictly per-instance part is a real replace-trigger. To settle when §4/the grow restores the nocloud
wire. §3 old "split by role" draft below is SUPERSEDED by §3a's whole-topology-OSGi-side — kept for history.

**§3 design (settled, whiteboard § "§3 — SCINDER BootstrapPaths").** BootstrapPaths mixes TWO natures;
descending it whole would re-create an OSGi→host coupling (it imports controlplane.config.BootstrapConfig,
Pulumi-side) — the leak we just closed. So SPLIT BY ROLE (user): the LAYOUT half (HostPathCatalog + the
roots + `asStagingView` — the mount points of the host folders inside the incus instance, incus's domain)
descends into incus-core; the HOST-VIEW half (`asHostView`/`pathOn`/`fromLocalWorktree`/secretsFile/gitRoot
— the Pulumi/worktree bindings) stays in seed-master and COMPOSES the layout. No new type invented — cut
the historical type at its two natures. The host passes incus the `nodeRoot` (+ cluster/node); incus does
`HostSlotSelector(nodeRoot)` + catalog + `asStagingView` to pick the slot AND compute the SOIL. CRITICAL
(user): the SOIL is handed by COORDINATE, not a typed field — `IncusProvisionScenario.the_manifests_are_cultivated`
already does `broker.sow(AmendCoordinate("manifests"), …)` (reflector maps role SOIL onto
ManifestsRunbookInput.materializationRoot) then `broker.sow(RunbookCoordinate("manifests"), …)`. incus tends
the computed `rke2-manifests.d` through that SAME amend-by-coordinate — JSON neutral, ZERO added typed
cross-domain dep. §4 = the `prepare` scenario (graft→publish→promote) + delete seed-master host-flat
remnants + the incus in-container test. D6 (EntryGatePolicyEnforcer → ManifestUpdateGate, same
anti-pattern, blocks the preview's preflight WHEN) still open after.

## The GROW of the incus instance is ~ENTIRELY ABSENT (inventory 2026-07-15) — not "half-migrated"

Digging (user: "on n'a même pas le scénario pour faire pousser l'instance") proved the incus instance
GROW is not half-done — it is quasi-entirely ABSENT from the feature branch. `342b7c327` deleted
`IncusResourceBootstrap` (3491 l) + 13 support classes ENTIRE, keeping only the NARRATION (the scion
that RECOUNTS prepare) and throwing the BODY (what makes the instance grow). Systematic inventory (grep,
false positives cleared) — each block of main's grow, its fate in THREE sorts:

**① DISSOLVED (already done — the I6 inversion replaced them, do NOT restore):** synth+explode manifests
→ `DefaultManifestSynthesisService`; build image → `DistrobuilderImageBuilder` (incus-edge); flox assets
→ `DefaultFloxRuntimeAssetService.writeInstallerAssetTree` (so `ClasspathTreeCopier` is dissolved too);
ImageStateConfigMap → migrated manifests-side; gh/flox token READING → `CliAuthTokenContact` (auth-edge).

**② ABSENT — to RESTORE (the real missing work, ~2000 l host + promotion):** the com.pulumi provision
(Project/Network/Profile/**Instance** — the `new Instance(running=true)` that grows) → seed-master
host-flat; `IncusProviderContext` + `IncusFunctions` import-lookup → seed-master; `seedInstanceDevices` +
`DeviceMountPipeline` (13 mounts host→container) → seed-master (reads HostPathCatalog + asHostView);
`HostAssetRootLifecycle` (promotion/rotation/rsync) → incus-core (= the NEW `HostTreePromoter`); nocloud
transcoder (`CloudConfigSecretRenderer` + `NodeConfigRegenerator`) → a STEP of the incus `prepare`
scenario AFTER the manifests graft (user-tranché — source `runtime/cloud-config` is IN the SOIL manifests
just filled, target `cloud.d/` is incus territory); `HostMountSourceVerifier` → incus scion;
gh/flox token WRITING (`LaunchSecretsUpdater.ensureTokensPresent` — upsert `.secrets` YAML preserving
comments, env-var precedence) → host-only (H1, the AuthTokenContact javadoc explicitly leaves the WRITE
to "the host launch-secrets updater").

**③ RE-EXAMINE — RESOLVED 2026-07-15 (mostly DISSOLVED, one narrow residue).** The whole ③ column turned
out smaller than it looked. main had EXACTLY ONE STATIC target (`cloud-init`); the other 4 (k8s, rke2-config,
systemd, rke2lab-env) are DYNAMIC. Resolution: (a) *DYNAMIC half DISSOLVED by I6* — R1 says a live instance
is never restarted on content change, it REFRESHES services via the runbook `json` runtime-role (the
`change` delta → services-to-reload map) = main's hot-reload in I6 vocab; so `TargetChecksumPipeline`/
`TargetReloadPolicy`/`provisioning.slice.*`/the 5-target registry are NOT restored (promotion #9 + json
refresh replace them). (b) *STATIC/cloud-init = the ONE real replace-trigger* — cloud-init reads its seed
once at first boot, so a `user-data` change MUST recreate the instance; the sole surviving glue is
"nocloud drift (a `HostDriftEntry` on cloud.d/) → flip `replaceOnChanges` on the host Instance", host-side,
built when the grow is restored; the per-file checksums to detect it already live in `HostStagingEntry`.
(c) *the 4 `*Metadata` + `HostSlotManifest` + `GitMetadataExtractor`* — their PROVENANCE role folds into the
`Host*Entry` provenance field, NOT restored as standalone types. Graved in provisioning-slice § "Delta #7
resolved". So ③ shrinks to: nothing to restore except the one nocloud→replace wire (already inside delta #8).

## `provisioning-slice-architecture.adoc` IS the reconciliation spec, but PRE-DATES the I6 correction

The reconciliation of "our scenario vision vs all that's missing" is NOT to invent from zero:
`provisioning-slice-architecture.adoc` already carries it — the founding rule (invisible at both
frontiers, VERIFIED byte-for-byte against `main`), the SETTLED arbitration (shape (A): host provisions,
declares the map's "image+instance=scion" line FALSE, splits the WHEN into 2 beats — pure-host
"provisioned" + sow-and-graft "verified"), and the 7 DELTAS (= our inventory, pre-written, with home+nature).

Its 7 deltas map to our 18 blocks: #1 Pulumi graph (host restore), #2-6 the 5 materializations (→
`manifests-bdd` scion, Family 2), #7 checksum→replaceOnChanges glue (straddling). BUT 4 of our ② blocks
have NO delta: the 13 MOUNTS, the PROMOTION (`HostTreePromoter`, lives in host-cellar), the SECRETS
(`.secrets` — TOTALLY absent from all 12 specs), `HostMountSourceVerifier`. → the reconciliation =
MERGE this spec with the I6 CORRECTION, adding deltas #8 mounts / #9 promotion / #10 secrets / #11
verify-sources.

**The ORDER contradiction to settle (the real remaining decision, NOT cosmetic):** shape(A) literal says
host provisions the VM FIRST, THEN sows the scion to verify+materialize (Family 1 & 2 independent, order
free). I6/host-cellar says the scion prepares+PROMOTES FIRST (jsync staging→host.live.d), THEN the host
grows the VM that mounts an already-current `host.live.d` — HARD order, else the VM mounts stale/empty
content. These are OPPOSITE. **I6 is right on the order** (the shape(A) doc pre-dates I6 and doesn't know
the promotion exists). The merge must flip the doc's "host-first" to "scion-promotes-first, host-grows-last".

**3 spec maladies (localised):** (1) MENT — `bdd.adoc:446/478` + `incus-edge:121` say the scion creates
the instance (false, 4 recent specs say host/Shape C, arbitration already rendered in provisioning-slice).
(2) SELF-OBSOLETE — host-cellar + atlas have 2026-07-14 CORRECTIONs invalidating their own earlier text;
`incus-edge` contradicts itself (l.113 host-side vs l.121 scion-creates). (3) REAL HOLES (no home) — the
instance-grow beat is prose nowhere a step; `.secrets` absent everywhere; nocloud transcoder undescribed.
`bdd-diagnostic-pattern.adoc` is the most MISALIGNED (ComponentResource/dry-run model) — legacy, not a target.

**BootstrapPaths — DESIGN SETTLED 2026-07-15 (superseded the "split by role" draft).** The realm boundary
is a CLASSLOADER boundary, NOT a resource one — Felix is embedded in the host JVM (on the Mac), so the
scion sees the SAME filesystem (§ "Filesystem access is not an obstacle"). Two user insights drove it:
(1) "même JVM → le scion peut tout calculer" ; (2) "on peut tout calculer côté OSGi si ça rend plus
lisible". So the WHOLE topology is computed OSGi-side, uniformly (not a host/scion split): incus-core holds
`HostPathCatalog` + `fromLocalWorktree` (DARWIN-local view) + `HostSlotSelector`/`asStagingView` + `pathOn`
EXTRACTED AS A PURE FUNCTION (the NFS `/net/`·`/private/`·netPrefix translation, formerly a BootstrapConfig
method). The scion produces the 3 PREPARE outputs in-container: (a) the staging slot, (b) the SOIL
`rke2-manifests.d` (by coordinate), (c) the mount plan `{name, source-NIXOS-translated, target=catalog.path()}`
= the CROSSING SOIL (flat, via `@Scion`/`@Rootstock` + a `IncusSplitReflector`, twin of DoctorSplitReflector).
The host keeps ONLY: the Pulumi engine (`com.pulumi.incus`, sole host-irreducible, shape B) + extract a
HANDFUL of FLAT SCALARS from BootstrapConfig (worktreeRoot, clusterName, nodeName, nfsAutomount) handed by
`@Amendment`; it reads the resolved mount plan flat to build InstanceDeviceArgs. BootstrapConfig NEVER
crosses. WHY the NFS translation survives: provisioning is BI-MACHINE (Felix on the Mac WRITES assets,
remote NIXOS host MOUNTS them over NFS to grow the instance) — user confirmed Felix runs on the Mac, so
pathOn is dormant (its caller seedInstanceDevices was deleted with the grow) but NOT dead. GRAVED in
host-cellar-realisation-spec § "BootstrapPaths — the WHOLE topology is computed OSGi-side" + seed-broker-spec
§ "The two natures of a SOIL" (the crossing-SOIL pattern + the HostPathCatalog corollary).

**The crossing-SOIL pattern GRAVED (reusable, seed-broker-spec § "The two natures of a SOIL").** Two natures:
*intra-world SOIL* = `@Amendment(SOIL)` (amont, host fills, scion consumes, nothing returns) ; *crossing SOIL*
= `@Scion(role)`+`@Rootstock`+SplitReflector (aval, scion RESOLVES a soil the host reads back FLAT). The broker
KNOWS the two apart by the annotations (no new machinery) ; the existing gates VERIFY it (RECORD_PURITY/
CONTRACT_PURITY keep the records pure, REALM_BOUNDARY forbids a flat class naming a bundle-only type) — no
CROSSING_SOIL gate needed at N=1. This resolves the recurring "host has no record to read the soil" problem:
the host NEVER reads the scion's record, it reads flat split sub-trees. [[gateway-crossing-three-natures]]

**Whiteboard state:** `.claude/claude-preview.adoc` is FULL of these (now-graved) findings + the ONE open
question (the merge: 7 deltas + 4 missing + the order A-vs-I6). NEXT = graduate the graved parts OUT of the
whiteboard, rewrite it clean on the merge, THEN merge provisioning-slice + I6 into one reconciled scenario map.

See [[gson-flat-vs-staged-regression]] [[master-execution-stage-missing-state]]
[[controlplane-to-osgi-migration-frame]] [[cellar-extended-resource-producer]].
