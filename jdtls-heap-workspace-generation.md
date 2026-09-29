---
name: jdtls-heap-workspace-generation
description: "Defect + fix for generated workspaces: the JDT.LS heap (-Xmx) is set in BOTH the .code-workspace and .vscode/settings.json, and the folder-scoped .vscode value WINS — so a generous workspace value is silently capped by a stale folder value, OOM-ing the Java language server on big reactors. Handoff to the main workspace generator."
metadata:
  node_type: memory
  type: project
---

**Defect (observed 2026-06-19, worktree refactor/extract-bridge-api).** The Eclipse JDT language
server OOM'd mid-session on this 30+-module reactor and had to be reloaded. Root cause was a
**settings-precedence conflict**, not just a low number: `java.jdt.ls.vmargs` was set in TWO places
with DIFFERENT `-Xmx` values —

- `<repo>.d/<ns>/<slug>.code-workspace` (workspace scope) → `-Xmx8G`
- `<slug>/.vscode/settings.json` (folder scope) → `-Xmx4G`

VSCode applies **folder-scoped settings OVER workspace-scoped settings**, so the effective heap was
the lower **4G** despite the workspace asking for 8G. The two configs silently disagreed and the
smaller one governed.

**Fix applied here:** both set to `-Xmx12G` (machine is 64 GB; ~30 GB free observed). Reload the
window for the JDT.LS to restart with the new `-Xmx`.

**Why:** a stale/low `.vscode/settings.json` heap masks whatever the `.code-workspace` carries, so
raising only the workspace value does nothing — the language server keeps OOM-ing. The two MUST agree
(or `.vscode` must omit the key so the workspace value applies).

**How to apply — handoff to the main workspace generator** (same generator that owns the
`.code-workspace` per [[claude-memory-cascade-state]]'s "handoff to the main workspace" defect): when
generating a worktree's `.code-workspace`, set `java.jdt.ls.vmargs` to a heap sized for this
reactor (≥8G, 12G comfortable on a 64 GB box) AND ensure `.vscode/settings.json` does NOT carry a
smaller `-Xmx` that overrides it — either keep the two in lock-step or drop the key from `.vscode`
so the single workspace-scoped value governs. Same root concern as the memory-slug defect: the
generator must produce per-worktree configs that are internally consistent, not a generous workspace
file shadowed by a stale folder file.

See [[claude-memory-cascade-state]] (sibling workspace-generation defect — memory slug),
[[hub:external-worktree-operating-model-state]] (the .code-workspace generation recipe),
[[extract-bridge-api-state]] (the slice where this surfaced).
