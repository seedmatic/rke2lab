# Memory index — hub (claude-hub)

The cross-cutting hub, auto-loaded as the session root. One line per entry (<~180 chars); detail lives in the file. Project-specific memory lives in each repo's own `.claude/memory` (loaded on demand). Links: `[[name]]` hub-local, `[[scope:name]]` cross-repo (scope = repo name or `hub`). See [[MEMORY-STRUCTURE-SPEC]] for the design.

## Registry-map — active chantiers per repo

- **rke2lab** — doctor/health system (intervention-provenance EXECUTING, runbook), manifests, config restructuring. See [[rke2lab:intervention-provenance-state]], [[rke2lab:runbook-doctor-state]].
- **maven fleet** (cross-repo: pom-manipulation-ext + maven-devenv-extension + build-cache + rke2lab consumer) — [[maven-github-token-resolution]], [[maven-extension-ecosystem]], [[pme-5.3-manipulated-pom-port]], [[maven4-status-backlog]].
- **docrepo-dag-wip** — [[docrepo-dag-state]] (V1 spec written, lives in its own repo).
- **nix-darwin-home** — [[global-wip-guard-hooks-state]] (wip-guard shipped, 2 steps pending).

## Cross-cutting: who the user is + how to work

- [[user-profile-senior-dev]] — ~40 yrs; orthogonal axes, domain-model load-bearing, deepest pain = errors-as-logs; collaborate by going deep + justifying + conceding honestly; continuity = memory files only.
- [[working-style-narrate-progress]] — narrate intent before each tool batch; long silent gaps read as stuck; cap investigation.
- [[works-best-from-concrete-code]] — user decides best seeing real impl, not abstract design; get to runnable code fast.
- [[error-handling-layered-contract]] — Optional=nothing-here, exception=went-wrong; leaf throws, aggregator carries partial + addSuppressed, caller decides. Deep why = errors-as-logs deport the decision onto humans.
- [[docs-diagrams-not-java]] — repo docs/specs/PLANS use prose + C4/UML Mermaid, NEVER Java snippets; user reviews faster from diagrams.
- [[sequential-no-compat-workflow]] — single dev, one topic at a time; NEVER backwards-compat; delete old paths same change.
- [[shared-artifacts-in-english]] — docs/comments/commits en-US, never French.
- [[feedback-verify-working-directory]] — verify CWD before shell commands (sandbox can be in any directory).
- [[standing-approval-subagent-execution]] — user pre-approves subagent-driven plan execution; default to it, don't re-ask approach (still review between tasks).
- [[defer-memory-writes-to-session-end]] — don't write auto-memory mid-session (it dirties the main checkout via the home symlink); accumulate + flush ALL at session end in one commit+push. Composes with the start-gate in [[external-worktree-operating-model-state]].

## Cross-cutting: git / workspace / build

- [[develop-into-main-is-a-no-ff-merge]] — `develop` → `main` by **`--no-ff` merge**, `main` → `develop` by **rebase**; never squash: a squash makes main stop being an ancestor, and a later `rebase main` on develop DROPS its commits.

- [[external-worktree-operating-model-state]] — ★ ACTIVE chantier: worktree-rooted Claude config. 3 layers (specific=repo .claude/ → general=claude-hub subtree at .claude/hub → ephemeral). CONFIG_DIR=<worktree>/.claude/hub, native settings cascade. Spec+plan written, step-1 PROVEN. GOTCHA: extension needs ABSOLUTE CONFIG_DIR (no ${workspaceFolder} subst). NEXT = wire real workspace + integrate to main.
- [[claude-auto-memory-mechanics]] — hard facts: auto-memory is REPO-WIDE by default (keyed off the git repo, shared across worktrees); `autoMemoryDirectory` (absolute/~ path in settings.local.json) VERIFIED on darwin 2026-08-14 — redirects read+write, REPLACED the deleted `link-memory.sh` symlink; only MEMORY.md auto-loads (200 lines/25KB); CLAUDE_CONFIG_DIR not documented to affect memory.
- [[worktree-per-conversation]] — PROMOTED to global CLAUDE.md rule; each conversation its OWN worktree, main READ-ONLY, base=fresh from origin/main.
- [[branch-namespaces]] — kind-prefixed branches (feature/refactor/deprecated/spike/); wip-guard keys off the wip/ DIR not the branch.
- [[superpowers-assets-in-wip]] — writing-plans/brainstorming plans+specs in wip/superpowers/, NOT docs/; kept off main by wip-guard.
- [[wip-guard-hooks]] — .githooks pre-commit+pre-push block wip/ reaching protected branches; via core.hooksPath in flox on-activate.
- [[bedrock-compaction-issue]] — auto-compaction fails; proactively warn at 80k/120k tokens before "input too long".
- [[flake-mutual-dependency-follows-root]] — mutual/cyclic flake dep resolved by reciprocal `inputs.<other>.inputs.<self>.follows = ""` (empty follows = ROOT flake, a fixpoint); both sides see real cross-data, lock terminates, no snapshot/subtree/getFlake; fails only on a true VALUE cycle. Validated 2026-08-17. Used by the seedmatic catalog federation (ndh↔nnh↔rke2lab).

## Cross-cutting: design + doc conventions

- [[shared-test-fixtures-module]] — cross-module fixtures in a dedicated *-testkit module (src/main/java, scope=test); never duplicate, never test-jar (m2e can't resolve).
- [[local-classes-pattern]] — use local classes in methods (not inner) when single-use & simple.
- [[review-scope-backlog]] — subagent quality reviewers use strict-diff scope; pre-existing code = non-blocking backlog at END, never refactored inline.
- [[diagram-preview-file]] — present mermaid via in-workspace .claude/claude-preview.adoc (NOT /tmp); kroki bioskop-nixos.local:8000 + .asciidoctorconfig; safe dialect = solid arrows, nude edge labels, flowchart not classDiagram.
- [[brainstorm-vocabulary-view-first]] — lead a new design with a code-faithful domain-vocabulary diagram (Diagram 0) so naming collisions surface at design time.
- [[check-osgi-standard-before-modeling]] — before modeling any OSGi-ish mechanism (config/lifecycle/services/extenders/fragments), verify the standard on the REAL jars (~/.m2/org/osgi) FIRST; don't reinvent Config Admin/Metatype/DS. Four planes: resolution / delivery / activation / registry.
- [[decision-options-in-preview]] — for non-trivial design choices, render the competing options as comparative diagrams in the preview BEFORE asking; user chooses against a visual, not prose.
- [[dsl-term-introduction-ritual]] — when a TERM/verb enters the DSL/glossary, document it the same way every time: a vocabulary view + before/after capability sentences anchored to a real artifact, by horizon. A term is valid only if it makes the system SAY something new. Positive twin of monotone-additivity; unifies [[brainstorm-vocabulary-view-first]] + [[decision-options-in-preview]] + the capability map.
- [[workspace-driven-by-need]] — VSCode workspaces composed by the NEED (active chantier), not a fixed domain taxonomy; a workspace = repos a task touches + claude-hub root; lean `.code-workspace`, strip unrelated folders.

## North-stars (design principles, active)

- [[model-substrate-alignment]] — if a round-trip WRITES through path X but READS bypassing X, the model is mis-fitted to its substrate (the bypass IS the tell). See [[rke2lab:intervention-provenance-state]].
- [[specialist-as-ledger-northstar]] — a knowledge-accumulating specialist IS a ledger (memory=a Pulumi stack, consulting=folding history); holds for drift/intervention subclass, NOT stateless reactive specialists. See [[rke2lab:intervention-provenance-state]].
- [[reentrance-northstar]] — the system submits ITSELF to the rules it edicts for its domain (same machinery, different subject); a self-exemption is a blind spot, the tell. Already at work in 4 instances (self-hosting units / specialist-as-ledger / designer runbook / the term-ritual on its own term). Generalizes [[model-substrate-alignment]] + [[specialist-as-ledger-northstar]]. First materialization = the designer runbook.

## Cross-repo chantiers (detail)

- [[maven-github-token-resolution]] — credential-command-resolver: Maven core ext resolves `<server>` password from `@command:gh auth token` fresh/build → kills the `${env.GH_TOKEN}` snapshot. Workaround: `GH_TOKEN= git -c credential.helper='!gh auth git-credential' push`.
- [[maven-extension-ecosystem]] — devenv = DISTRIBUTOR of forked/patched Maven exts; first payload = build-cache m2e patches. devenv full revival ABANDONED (pit of coupled forks).
- [[pme-5.3-manipulated-pom-port]] — **ACTIVE:** revive "build & attach manipulated POM without overwriting original" (Bridge auto-injects Mojo: setPomFile+setFile) onto jboss.pnc.mavenmanipulator 5.3. Spec on feature/manipulated-pom-passthrough. Bootstrap lever for the fleet. NEXT=port Mojo.
- [[maven4-status-backlog]] — Maven 4 RC-only (4.0.0-rc-5, not prod), ext API experimental; stay 3.9.x; dedicated deep-dive later.
- [[global-wip-guard-hooks-state]] — global any-depth wip-guard SHIPPED to nix-darwin-home develop; 2 steps pending (rke2lab migration, e2e verify on bioskop).
- [[claude-distributed-assets-topic]] — distributing Claude memory/skills across repos+hosts. SUPERSEDED by this memory-structure work → [[MEMORY-STRUCTURE-SPEC]].
- [[docrepo-dag-state]] — docrepo-dag-wip repo, V1 spec written; OSGi-resolution + P2P doc repository (git-DAG, Unit=bundle-equiv).

## Meta (this restructure)

- [[MEMORY-STRUCTURE-SPEC]] — the DAG memory design (meta-hub + per-repo + scoped links).
- [[memory-auto-garbage]] — keep the index BALANCED with the DAG by routine garbage collection (s/cleanup/garbage/): reclaim index slots whose value moved to git history or a consolidated note; GC every flush, not only at overflow; reclaim implies edge-repair (no orphans, no dead links). Routine GC vs [[memory-synthesis-prune-the-how]]'s big periodic garden. A [[reentrance-northstar]] instance.
- [[MEMORY-STRUCTURE-PLAN]] — the migration plan; Tasks 1-9 DONE. Task 9 (rke2lab pruning: 23 files git-rm'd + MEMORY.md rewritten to rke2lab-only) is STAGED-NOT-COMMITTED in rke2lab. DONE since: the hub IS pushed (origin github.com/nxmatic/claude-hub, pushed routinely this session). Workspaces: `rke2lab.code-workspace` trimmed 15→8 folders (rke2lab first = session anchor) + `nix-darwin-home.code-workspace` for provisioning. Convention recorded: [[workspace-driven-by-need]]. NEXT real work = resume [[pme-5.3-manipulated-pom-port]] (port the Mojo).
