---
name: doc-cleanup-wave4-checkpoint
description: "Resume point for the docs/ work-history cleanup (the courant/future/mort review). Waves 1-3 + the p2p reframe committed; Wave 4 residual strip is what remains, per-file verdict known. Safe-to-compact snapshot."
metadata:
  node_type: memory
  type: project
---

**The task:** strip work-history scaffolding from `docs/` (status markers, phases, checkboxes,
`SHIPPED`, `increment N`, dates, commit shas) so docs describe the CURRENT codebase + genuine FUTURE,
never the history of the work. Rule set: [[atlas-before-after-shift-at-merge]] (shift consumed
before/afters; do it at each merge), [[tidy-on-drift-widens-scope]]. VIGILANCE that paid off twice:
before removing work-history, ask "does this abandon a design someone judged important?" — it surfaced
[[world-gateway-lost-open-extensibility-debt]] (the multiplexor AND the config registrar, both = the
same open-contributor evolution required by the p2p distributed mode).

**DONE + COMMITTED on design/pre-integration:**
- Wave 1 `1f6cd612` — purged 4 dead docs (vocabulary-and-live-record-plan, jgiven-osgi-wrap-spike-report,
  first-increment-handler-load, planning-documents-guide) + redirected inbound links.
- Wave 2 `9bc432c6` — collapsed world-gateway spec + 2A/2B/2C/2D into ONE current-state
  `world-gateway-spec.adoc` (contract layer = current; host-side migration = §Remaining, future).
- Wave 3 `64c10226` — atlas: shifted consumed transitions to current-state, stripped R1-R7 SHIPPED
  table + dates + "GRADUATION of migration-spec" framing; monotonicity verified HOLDS across all L1s;
  removed the never-coded multiplexor diagrams (N/N2/O) from atlas/doctor, kept the LIVE host-side
  before/after.
- p2p reframe `2275f051` — world-gateway §"Planned evolution" + config-restructuring §"Open the domain
  set" both framed as FUTURE required by the distributed p2p mode (not debt); memory linked to
  [[federated-unitrepo-p2p-design]] [[fragment-contribution-mediation-model]] [[multiplexor-two-models-design]].

**WAVE 4 REMAINING — residual cosmetic strip, per-file verdict (the "buried critical design" scan is
already done, NO more bombs — these are safe):**
- config-restructuring-spec.adoc — the p2p item is DONE; residual: still says `wip/` in places, an
  Appendix lists `InfraDomainRegistrar` as *to create* though the enum shipped (fix the appendix), and
  "Implementation increments" state A/B/C/D scaffolding to strip. MED.
- cluster-workload-completion-plan.adoc — FUTURE (seed-master validation paused mid-way, per user;
  see the seed-master provisioning rework). Strip the dated 2026-05-26 snapshot, the `~~struck~~ Done`
  items, "Current focus", commit shas; KEEP the still-to-do (Porch, Flux, CAPN peers). Reframe as a
  future worklist, not a dated running plan. HEAVY (23 markers).
- cluster-api-bootstrap-requirements.adoc — Phase 1A cleanup + flox webhook injection; annotation
  injection is SHIPPED. Strip checkbox todos + phase labels + timeline; keep the three-tier env model
  + webhook architecture (future). HEAVY (47 markers).
- unit-repository/{model-overview,node-and-resolution,unit-model,jgit-transposition}.adoc — legitimate
  FUTURE design vision; scan says KEEP the vision. Only strip: `*Status:* DESIGN (2026-06-20)` dates
  and turn "SHIPPED vs DESIGN" tracking columns into durable phrasing. LOW (mostly 1-12 light).
- doctor/{practitioners-as-components-design,preview-whatif-replay-exploration,rules-engine-checkpoint-topology-exploration}.adoc
  — FUTURE/exploration, ALREADY labeled as such, designs INTACT (do NOT delete — these hold the
  GeneralPractitioner+Consultation split, the self-referential medicalRecord accumulator, and the
  Checkpoint-topology-as-planner-prerequisite; all still-unbuilt future). Only strip dates/`Status:`
  headers; keep every design. LOW.
- patterns/{controlnode-host-assets-architecture,flox-webhook-design}.adoc — ordinary future design,
  scan says safe. Strip phase/day-estimate/checkbox scaffolding; keep the contract/design. controlnode
  LOW, flox-webhook HEAVY (22, mostly its impl-plan checkboxes).

**Approach:** grape by grape (unit-repo+doctor lightest first, then patterns, then the two cluster-api
plans + config appendix), a commit per grape, prose + diagrams only (no Java). Nothing here needs a
user decision — the decisions (what's future vs dead vs current) are all made.

**After Wave 4:** deferred gestures — remove the merged `feature/engine-lifecycle-socle` worktree +
branch + .code-workspace; then Plan 2 (ClusterSeed BDD migration, consumes the socle).
