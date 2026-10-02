#!/usr/bin/env bash
# SessionEnd hook: commit (and push) the memory worktree.
#
# The session that WRITES memory is never the one that commits from that checkout —
# `autoMemoryDirectory` points at `<repo>.d/memory`, while the session works in its own
# worktree. Nothing closed that gap, so memory accumulated UNCOMMITTED: measured
# 2026-09-29, 32 files had been dirty in a checkout for 11 days (oldest 2026-09-18), and
# the resulting divergence made a dead-link audit report 7 live facts as dead.
#
# So: at session end, whatever the memory worktree holds becomes a commit. Never fails
# the session — a memory that cannot be committed is reported, not fatal.
set -uo pipefail

input="$(cat 2>/dev/null || true)"
session_id="$(printf '%s' "$input" | yq -p json '.session_id // ""' 2>/dev/null || true)"

note() { printf '{"systemMessage": "%s"}\n' "$1"; }

# The memory worktree is the one checked out on the `memory` branch — derived, never
# assumed from a path, so a renamed or relocated worktree is found rather than missed.
wt="$(git worktree list --porcelain 2>/dev/null |
  awk '/^worktree /{w=$2} /^branch refs\/heads\/memory$/{print w; exit}')"

if [[ -z "$wt" || ! -d "$wt" ]]; then
  note "memory-commit: no worktree is on the 'memory' branch — memory writes are NOT being committed. Create it: git worktree add <repo>.d/memory memory"
  exit 0
fi

if [[ -z "$(git -C "$wt" status --porcelain)" ]]; then
  exit 0 # nothing written this session
fi

count="$(git -C "$wt" status --porcelain | wc -l | tr -d ' ')"
msg="memory: session writes"
[[ -n "$session_id" ]] && msg="memory: session ${session_id%%-*} writes"

if ! git -C "$wt" add -A || ! git -C "$wt" commit -q -m "$msg

$count file(s) written by the session. Committed by the SessionEnd hook, which exists
because the session writing here is not the one that commits from here.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"; then
  note "memory-commit: FAILED to commit $count changed file(s) in $wt — commit them by hand before they drift."
  exit 0
fi

if git -C "$wt" push -q 2>/dev/null; then
  note "memory-commit: committed + pushed $count memory file(s)."
else
  note "memory-commit: committed $count memory file(s) but the push FAILED — run 'git -C $wt push'."
fi
