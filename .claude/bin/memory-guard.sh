#!/usr/bin/env bash
# SessionStart hook: verify memory is wired to the memory worktree, and that it is clean.
#
# Checked at START, not later, because a running session CANNOT be redirected: the path is
# resolved once into the system prompt. Measured 2026-09-29 — settings were corrected at
# 09-27 22:44 and writes were still landing in the old directory 14 h later. So a
# misconfiguration found mid-session costs the whole session's memory.
#
# Two failure modes, both silent until they hurt:
#   1. `autoMemoryDirectory` pointing somewhere other than the memory worktree — writes
#      fork into a tree nobody merges (that cost 7 facts recorded as dead).
#   2. The memory worktree left dirty — the SessionEnd hook should have committed it, so
#      dirt here means a previous session ended without running it.
set -uo pipefail

cat >/dev/null 2>&1 || true # drain the hook payload; nothing here needs it

note() { printf '{"systemMessage": "%s"}\n' "$1"; }

wt="$(git worktree list --porcelain 2>/dev/null |
  awk '/^worktree /{w=$2} /^branch refs\/heads\/memory$/{print w; exit}')"

if [[ -z "$wt" || ! -d "$wt" ]]; then
  note "MEMORY: no worktree on the 'memory' branch. Memory writes will not be committed. Fix: git worktree add <repo>.d/memory memory"
  exit 0
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
settings="$repo_root/.claude/settings.local.json"
configured=""
[[ -f "$settings" ]] &&
  configured="$(yq -p json '.autoMemoryDirectory // ""' "$settings" 2>/dev/null || true)"

problems=()

# Compare resolved paths: a symlinked or /private-prefixed spelling of the same directory
# is correct, and a string compare would call it a mismatch.
if [[ -z "$configured" ]]; then
  problems+=("autoMemoryDirectory is UNSET in .claude/settings.local.json — memory goes to the reload-lost default, not $wt")
elif [[ "$(cd "$configured" 2>/dev/null && pwd -P)" != "$(cd "$wt" && pwd -P)" ]]; then
  problems+=("autoMemoryDirectory is '$configured' but the memory worktree is '$wt' — writes would fork")
fi

if [[ -n "$(git -C "$wt" status --porcelain)" ]]; then
  n="$(git -C "$wt" status --porcelain | wc -l | tr -d ' ')"
  problems+=("the memory worktree has $n uncommitted file(s) — a previous session ended without the SessionEnd commit hook")
fi

if ((${#problems[@]} > 0)); then
  note "MEMORY WIRING: $(
    IFS='; '
    echo "${problems[*]}"
  )"
fi
