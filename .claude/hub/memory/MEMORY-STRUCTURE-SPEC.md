# Memory Structure — Design Spec (DAG: meta-hub + per-repo)

**Status:** Draft for review · **Date:** 2026-06-14
**Scope:** Restructure Claude's file-memory from one mixed pile into a traversable
DAG: a meta-repo hub (cross-cutting) + per-repo memories (project-specific).

## Problem

All memory currently lives in ONE place — rke2lab's `.claude/memory/` (the
session path `~/.claude/projects/-private-var-lib-git-nxmatic-rke2lab/memory`
is a symlink to it). It mixes:
- truly cross-cutting facts (user profile, conventions, principles),
- rke2lab-specific chantiers (doctor, manifests, config),
- and other-repo / cross-repo topics (maven fleet: token/PME/devenv/ecosystem,
  docrepo-dag, global-wip-guard, nix-darwin-home).

Two limits bite:
1. **Index cap (~24.4KB):** `MEMORY.md` is auto-injected at session start and
   capped. It hit 27.4KB → silently truncated (entries below the cut became
   invisible). Compressed to 12.1KB this session, but it will re-grow as
   chantiers accumulate.
2. **Relevance (not capacity):** on a 1M model all memory ≈ 74K tokens = 7.4% of
   the window, so capacity is NOT the constraint. But loading doctor's 11K
   tokens while working on PME is noise that dilutes attention.

The index cap is the hard limit; relevance is the quality argument. Both point
to splitting memory per-repo and keeping each index small.

## Model: memory as a traversable DAG

- **Nodes** = memory files. **Edges** = `[[links]]`.
- **Meta-repo hub** (auto-loaded, the session root) holds:
  - the **cross-cutting** memory (profile, conventions, principles, universal
    gotchas) — true for every repo;
  - a **registry-map**: one line per active chantier across all repos (the
    lightweight overview), NOT a link resolver.
- **Per-repo memory** (`<repo>/.claude/memory/`, already the pattern for
  rke2lab) holds that repo's chantiers. Loaded **on demand**.

### Session bootstrap

1. Claude is launched from the **meta-repo** (the root workspace of all
   sessions) → its `.claude/memory` auto-loads (cross-cutting + registry-map).
2. Claude reads the workspace's **working directories** → filters the
   registry-map to the repos present.
3. Claude asks: **"which entry point do you want to attack?"**
4. Loading then proceeds **as the conversation goes** — diving into a repo Reads
   its memory; `[[links]]` pull neighbours of-proche-en-proche.

### Link resolution (the DAG edges)

Chosen for low context cost + robustness (no central resolver to keep loaded):

- **Intra-repo:** `[[name]]` (bare; scope = current repo, implicit — zero
  writing overhead, unchanged from today).
- **Cross-repo:** `[[scope:name]]` where scope = a repo name or `hub`. Examples:
  `[[hub:maven-extension-ecosystem]]`, `[[pme:manipulated-pom-port]]`.

The edge IS the coordinate — self-sufficient, resolves even if the registry
drifts (we saw dead refs happen: `maven-external-version-extension` pointed
nowhere). The hub's registry-map is a **human/overview map**, deliberately NOT
on the link-resolution critical path.

## Repo layout

```
claude-memory/  (NEW meta-repo; the root CWD of every Claude session)
  .claude/memory/
    MEMORY.md            <- hub index: cross-cutting entries + registry-map
    <cross-cutting>.md   <- profile, conventions, principles, universal gotchas
    <cross-repo>.md      <- topics spanning repos (maven fleet, distributed-assets)

<project repos>/.claude/memory/   (per-repo, loaded on demand)
  MEMORY.md              <- that repo's own small index
  <repo-specific>.md
```

Naming: vote = `claude-memory`. Note the existing convention is
`<context>-claude` (e.g. `hyland-experience-claude`, the Hyland-scoped one — a
DISTINCT private repo, not this personal hub). Confirm `claude-memory` vs
`personal-claude` at implementation; spec uses "meta-repo" / `claude-memory`.

## Migration of the current 56 files

Classify each current file in rke2lab's memory:

**→ HUB (cross-cutting): true for every repo**
- user-profile-senior-dev, working-style-narrate-progress,
  works-best-from-concrete-code
- error-handling-layered-contract, docs-diagrams-not-java,
  sequential-no-compat-workflow, shared-artifacts-in-english
- worktree-per-conversation, branch-namespaces, superpowers-assets-in-wip,
  wip-guard-hooks, diagram-preview-file, brainstorm-vocabulary-view-first
- bedrock-compaction-issue, shared-test-fixtures-module, local-classes-pattern,
  review-scope-backlog
- model-substrate-alignment, specialist-as-ledger-northstar (design principles)

**→ HUB (cross-repo topics spanning multiple repos)**
- maven-github-token-resolution, maven-extension-ecosystem, maven4-status-backlog,
  pme-5.3-manipulated-pom-port (the maven fleet — touches devenv/PME/rke2lab)
- global-wip-guard-hooks-state (nix-darwin-home + rke2lab)
- claude-distributed-assets-topic (this very topic)
- docrepo-dag-state (its own repo `docrepo-dag-wip` — really belongs there;
  park in hub registry until that repo gets its own memory)

**→ STAY in rke2lab (project-specific)**
- All doctor/* (runbook-doctor, healthsystem-keystone, referral-roundtrip,
  medical-record-impl-complete, doctor-live-record-roadmap, doctor-remediation,
  preview-whatif, efficacy-first-prescription, intervention-provenance,
  master-provisioning, cohort/healthsystem-access-control)
- manifests-doc-consolidation, terminology-refactor, refactor-pipeline-candidates,
  package-private-sweep, seed-vcluster, manifest-registrars-enum,
  domain-registry-abstraction, config-restructuring, dsl-unification,
  build-verification-gotchas, bdd-jgiven-test-strategy

**Cross-repo edges get re-scoped** during migration: e.g. a rke2lab note linking
the maven work becomes `[[hub:maven-extension-ecosystem]]`.

## Success criteria

- Launching Claude from `claude-memory` auto-loads only cross-cutting + the
  registry-map (small, always under the index cap).
- Working dirs filter the overview; Claude asks the entry point; per-repo memory
  loads on demand.
- A `[[scope:name]]` link resolves deterministically to one file without a
  central resolver in context.
- rke2lab's memory shrinks to rke2lab-only; the maven fleet + docrepo live in
  the hub (or their own repo); no more mixed pile.

## Out of scope

- Auto-detecting working dirs without being told (Claude reads them from the
  harness; no new tooling).
- A link-validation/dead-link checker (nice-to-have; the registry-map can host
  it later, not v1).
- Migrating other repos' existing session-memory dirs (nix-darwin-home,
  java-systemd) — do those when first working in them.

## Volume map (current distributed state, surveyed 2026-06-14)

Where memory actually lives across `/private/var/lib/git` + `~/.claude/projects`:

| Session anchor | Memory | State |
|---|---|---|
| `nxmatic/rke2lab` | 58 files, symlink `~/.claude/projects/…rke2lab/memory → repo/.claude/memory`, **git-tracked** | the big mixed pile (to split) |
| `thjomnx/java-systemd` | 2 files, **real dir** under `~/.claude/projects/…` (NOT symlinked, NOT in the repo) | orphan local; `feedback_verify_working_directory` is CROSS-CUTTING → goes to hub |
| `nxmatic/nix-darwin-home` | session dir exists, **no `memory/`** | never initialised |
| rke2lab worktree (provenance) | inherits rke2lab memory via worktree | — |

Other `.claude/` dirs: `rke2lab/.claude/skills/` holds `precompact-session-state.md`
(a local skill). `maven-devenv-extension` and `nix-darwin-home` have `.claude/`
but no memory.

Takeaways for the plan: rke2lab is the ONLY real memory store (hence the mix);
the java-systemd orphan's cross-cutting feedback should be folded into the hub;
nix-darwin-home memory is greenfield (init when first worked in).

## Migration steps (for the plan)

1. Create `claude-memory` repo + `.claude/memory/` + symlink its session path.
2. Move the HUB-classified files there; write the hub `MEMORY.md`
   (cross-cutting index + registry-map).
3. Prune rke2lab's `MEMORY.md` to rke2lab-only; delete the moved files from it.
4. Re-scope cross-repo `[[links]]` to `[[hub:...]]` / `[[repo:...]]`.
5. Update the launch convention: sessions root at `claude-memory`, project repos
   added as working dirs.
6. (rke2lab commit deferred — parallel session owns rke2lab; coordinate.)

### Status 2026-09-29 — steps 1-4 realised, and rke2lab's own store MOVED

Step 1's `claude-memory` repo exists as **`claude-hub`** (`claude-hub.d/main`, 41 memory
files), steps 2-4 landed with it: the hub index, the split, and the `[[hub:]]` re-scoping
(87 occurrences). The table above is stale — rke2lab is no longer "58 files, symlink,
git-tracked in the repo".

★ **rke2lab's project-local memory now lives on an ORPHAN `memory` branch** with its own
worktree at `<repo>.d/memory` (341 files), and NO code branch carries `.claude/memory/`
any more. Sessions point `autoMemoryDirectory` at that worktree.

Why a branch rather than a second repo: rke2lab already uses orphan branches as
independent artifacts — `flox-catalogue` (109 commits) and `seed-incluster` (46), neither
sharing an ancestor with `main`. A branch gives the same isolation as a repo (one line of
history, one working copy, forking impossible) with no new repo, no subtree machinery and
no cross-repo sync.

What forced it, measured the same day: memory authored inside the branching code repo had
split into two trees from one commit — 248 files against 325, 175 identical — and a
dead-`[[link]]` audit over one of them reported **7 false deaths**. Per-worktree memory
directories do not prevent that; they guarantee it. The contrast is in this very file's
neighbourhood: the hub's memory, one repo with one working copy arriving as a synced
subtree, was **41 files with 0 divergence** at the same moment.
