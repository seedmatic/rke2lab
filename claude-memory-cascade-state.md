---
name: claude-memory-cascade-state
description: "Reference — how Claude's file-memory is wired in this repo (three-tier cascade + clean-split config-home) and the hard-won facts about Claude's file model. Chantier SHIPPED to origin/main (4f24c65d + f744bf52)."
metadata:
  node_type: memory
  type: reference
---

**Shipped.** The three-tier Claude file-memory cascade landed on `origin/main`
(`4f24c65d feat(claude-memory): three-tier memory cascade`, `f744bf52 chore(claude): delete the
config-home wrapper`). Branch `chore/claude-memory-cascade` merged + deleted. Design spec:
`wip/specs/2026-06-18-claude-memory-three-tier-cascade-design.adoc`. This note is the durable
reference for how it works; it is no longer an active chantier.

**The model:**
- Three tiers, each a distinct scope + backing: **worktree** (`<wt>/.claude/memory/`, branch git) →
  **hub** (`<wt>/.claude/hub/memory/`, git subtree ↔ `claude-hub`) → **home** (`~/.claude/memory/`,
  minimal = profile only).
- Reading = root index `<wt>/.claude/memory/MEMORY.md` + scoped links `[[hub:name]]` / `[[home:name]]`.
  Location of a note IS its scope.

**Config-home wiring (clean-split — the CURRENT model):** config-home is set PER-WORKSPACE in the
`.code-workspace`, NOT via a global wrapper (the old `claude-config-home-wrapper.sh` was deleted).
`claudeCode.claudeProcessWrapper: ""` (empty=falsy=disabled) + `claudeCode.environmentVariables:
[{name:CLAUDE_CONFIG_DIR, value:<abs>/.claude}]`. So config-home = `<worktree>/.claude` (NOT
`.claude/hub`). Env vars are injected verbatim (`String(value)`, NO `${workspaceFolder}` substitution
→ path must be absolute; fine since host-specific + known at generation). Effects: runtime
(`projects/`, `sessions/`, …) lands under `.claude` (gitignored via `.claude/.gitignore`);
`.claude/hub` is SINGLE-role (subtree content only); memory is pinned to the tracked
`<wt>/.claude/memory` via `autoMemoryDirectory` in `settings.local.json` (no symlink).

**How tier-1 memory is wired — `autoMemoryDirectory` (since 2026-08-14, supersedes the symlink):**
the subtree gives `.claude/hub/memory/` (tier-2), but Claude's auto-memory defaults to a DIFFERENT
dir — `~/.claude/projects/<slug>/memory/`, **repo-wide** (keyed off the git repository, shared across
worktrees) + gitignored, NOT provided by the subtree. We set `autoMemoryDirectory` (in the
per-worktree `.claude/settings.local.json`) to the **absolute** `<wt>/.claude/memory`, so auto-memory
reads/writes straight into tier-1 (tracked → rides into main at merge). This REPLACED the old
`~/.claude/projects/<slug>/memory -> <wt>/.claude/memory` symlink (deleted along with `link-memory.sh`):
an absolute path is slug- and reader-independent, which is exactly why it fixes the defect below.

**The 5 hard-won facts about Claude's file model (why it was hard):**
1. *Slug encodes `/` AND `.` → `-`.* `rke2lab.d` → `rke2lab-d`. A `s:/:-:g` (only `/`) gives the wrong
   slug. Correct: `s:[/.]:-:g`. Now only the SESSIONS bridge uses it (`link-sessions.sh`); memory is
   slug-free (`autoMemoryDirectory`).
2. *Config-home redirects everything.* Whatever sets `CLAUDE_CONFIG_DIR` decides where Claude reads
   conversations + memory. Pre-clean-split that was the wrapper → `.claude/hub`; now it's the
   `.code-workspace` env var → `.claude`.
3. *Slug = working-tree root (`git rev-parse --show-toplevel`), governs SESSIONS only.* Per-worktree,
   so transcripts + the Dock sidebar isolate per worktree (bridged by `link-sessions.sh`). **Memory
   does NOT follow this slug** — auto-memory is repo-wide by default (docs: keyed off the git
   repository, shared across worktrees); we pin it per-worktree via `autoMemoryDirectory` (an explicit
   absolute path), which is precisely why it is slug-independent. The old belief that the slug isolated
   memory was the root of the defect below.
4. *Two concerns share `.claude`:* config-home runtime (`projects/`, `settings.json`, `plugins/` —
   gitignored) + subtree content under `.claude/hub` (`memory/`, `skills/`, `instructions.md` —
   versioned). The `.gitignore` files separate them.
5. *Conversations are EPHEMERAL + per-worktree.* They live under `<wt>/.claude/projects/<slug>/`;
   removing the worktree deletes them. Committed memory is the ONLY durable cross-boundary carrier.
   The `closed - …` sidebar noise = orphan labels in VSCode `state.vscdb` (workspaceStorage,
   `agentSessions.model.cache`) that SURVIVE worktree removal → purge is the corollary of removal.

**★ Bridging is human — one conversation ↔ one workspace ↔ one worktree.** Claude NEVER links/grafts
a conversation across worktrees (a one-file transcript symlink was tried 2026-06-18 and reverted — it
broke the invariant). Cross-conversation continuity flows through COMMITTED MEMORY; the HUMAN carries
the live thread between windows. A conversation is visible ONLY in its own workspace.

**★ DEFECT RESOLVED (2026-08-14) — was: handoff to the main workspace.** Observed 2026-06-19 from a
non-main worktree (`feature/osgi-runtime-r3-consume-references`): the system prompt announced
file-memory at the **main** worktree's slug (`…-rke2lab-d-MAIN/memory`), not this worktree's, so the
symlink bridge pointed at a non-existent path here. **Root cause:** the symlink model made the memory
path slug-dependent, and the slug wasn't recomputed per worktree. **Fix:** `autoMemoryDirectory` pins
memory to an explicit absolute `<wt>/.claude/memory` — no slug, no symlink, reader-independent — so
the announced/auto-loaded path is always THIS worktree's tracked dir. The `worktree` skill writes the
setting at worktree creation. Defect closed; `link-memory.sh` deleted.

**Residual / not done:** the nix `claudeCodeSeedMemory` activation (minimal home-tier seed-if-absent)
was never wired — revisit only if a fresh machine needs the home tier bootstrapped.

See [[hub:claude-auto-memory-mechanics]] [[hub:external-worktree-operating-model-state]].
