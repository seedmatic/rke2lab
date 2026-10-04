#!/usr/bin/env bash
# PreCompact hook: snapshot the session before context compaction.
#
# Merges agent-authored session context (if present) with git state.
# - Agent draft: .claude/checkpoint-draft-<session_id>.md (conversation context)
# - Git state: the WHOLE worktree DAG, not just the current checkout
# - Output: .claude/checkpoint-<session_id>-<timestamp>.md (complete recovery point)
#
# Non-destructive by design: runs *before* compaction (which may fail),
# so only writes — never deletes.
#
# ★ What this file is NOT: the session's memory. Continuity lives in the handoffs
# under .claude/ and in the memory branch; this is a git snapshot that tells the
# next session WHERE to look. Measured the hard way on 2026-10-04: a backlog that
# lived only in an interface's state vanished when that interface was tidied.
set -euo pipefail

input="$(cat)"
session_id="$(printf '%s' "$input" | yq -p json '.session_id // ""')"

# Without a session id we cannot key (or later GC) the checkpoint. Fall back to
# a timestamp so the snapshot is never silently lost.
if [[ -z "$session_id" ]]; then
  session_id="unkeyed-$(date '+%Y%m%d-%H%M%S')"
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
timestamp="$(date '+%Y%m%d-%H%M%S')"
checkpoint="$repo_root/.claude/checkpoint-$session_id-$timestamp.md"
draft="$repo_root/.claude/checkpoint-draft-$session_id.md"
now_display="$(date '+%Y-%m-%d, %Hh%M')"

# A truncation that does not say so is indistinguishable from completeness — the
# recurring fault this workspace measured seven times in two days. So every cut
# below announces what it dropped.
emit_capped() {         # $1 = cap, stdin = lines
  local cap="$1" n=0 total=0 line
  local -a buf=()
  while IFS= read -r line; do
    total=$((total + 1))
    [[ $n -lt $cap ]] && { buf+=("$line"); n=$((n + 1)); }
  done
  ((total == 0)) && { echo "(none)"; return; }
  printf '%s\n' "${buf[@]}"
  ((total > cap)) && echo "… and $((total - cap)) more (capped at $cap)"
  return 0
}

# One repository's worktrees: branch, head, and how dirty — the three facts that
# decide whether work is safe. ★ A green barrier covers a TREE, not a sha, so the
# head alone is not enough; the dirty count is what says the tree is attributable.
emit_repo() {           # $1 = any checkout of the repo, $2 = display name
  local dir="$1" name="$2" wt br head dirty ahead
  echo "#### $name"
  echo ""
  while IFS= read -r wt; do
    [[ -d "$wt" ]] || continue
    br="$(git -C "$wt" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
    head="$(git -C "$wt" rev-parse --short HEAD 2>/dev/null || echo '?')"
    dirty="$(git -C "$wt" status --porcelain 2>/dev/null | grep -cv '^?? ' || true)"
    ahead="$(git -C "$wt" rev-list --count '@{upstream}..HEAD' 2>/dev/null || echo '-')"
    printf -- '- `%s` — %s @ %s · %s tracked change(s) · %s unpushed\n' \
      "${wt##*/}" "$br" "$head" "${dirty:-0}" "$ahead"
  done < <(git -C "$dir" worktree list --porcelain 2>/dev/null |
             awk '/^worktree /{print $2}' | head -12)
  echo ""
}

emit_state() {
  echo "## Git state"
  echo ""
  echo "### This checkout — recent commits"
  echo ""
  git -C "$repo_root" log --oneline -10 2>/dev/null | emit_capped 10 || echo "(no git history)"
  echo ""
  echo "### This checkout — working tree"
  echo ""
  git -C "$repo_root" status --short 2>/dev/null | emit_capped 40 || echo "(no changes)"
  echo ""
  echo "### The worktree DAG"
  echo ""
  echo "Every checkout of every sibling repository in the layout, so a resumed"
  echo "session knows what is in flight elsewhere — not only where it was sitting."
  echo ""
  # The layout is {storeRoot}/{org}/{repo}.d/{slot}: the parent of the parent of
  # this checkout is the org directory, and its `*.d` children are the repos.
  local org_dir
  org_dir="$(cd "$repo_root/../.." 2>/dev/null && pwd || true)"
  if [[ -n "$org_dir" && -d "$org_dir" ]]; then
    local d
    for d in "$org_dir"/*.d; do
      [[ -d "$d" ]] || continue
      local repo_name slot found=""
      repo_name="$(basename "$d")"; repo_name="${repo_name%.d}"
      # ⚠️ NOT `head -1`: the first entry may be a hidden archive directory that is
      # not a repository (measured: rke2lab.d/.etage0-archive), and a silent skip
      # would drop the WHOLE repo from the snapshot. Take the first that is a repo.
      for slot in "$d"/*; do
        [[ -d "$slot" ]] || continue
        git -C "$slot" rev-parse --git-dir >/dev/null 2>&1 && { found="$slot"; break; }
      done
      if [[ -n "$found" ]]; then
        emit_repo "$found/" "$repo_name"
      else
        echo "#### $repo_name"
        echo ""
        echo "- (no git checkout found under \`$(basename "$d")\`)"
        echo ""
      fi
    done
  else
    echo "(layout root not resolvable from $repo_root)"
    echo ""
  fi
  echo "### Where continuity actually lives"
  echo ""
  echo "- handoffs: \`.claude/*handoff*.md\` — the master one is \`devpod-integration-handoff.md\`"
  echo "- memory: the \`memory\` branch, checked out at \`<repo>.d/memory\`"
  echo ""
  echo "⚠️ This checkpoint is a git snapshot. It records WHERE things were, never"
  echo "what was decided — read the handoff for that."
}

{
  echo "# Checkpoint — $now_display"
  echo ""
  echo "> Auto-generated before compaction. Session \`$session_id\`. Resume with this file."
  echo ""
  if [[ -f "$draft" ]]; then
    echo "---"
    echo ""
    # Include agent-authored context (strip any leading "# Session Context" header)
    sed '1{/^# Session Context$/d;}' "$draft"
    echo ""
  fi
  echo "---"
  echo ""
  emit_state
} > "$checkpoint"

if [[ -f "$draft" ]]; then
  rm -f "$draft"
  printf '{"systemMessage": "Checkpoint created with agent context + worktree DAG: %s"}\n' \
    ".claude/checkpoint-$session_id-$timestamp.md"
else
  printf '{"systemMessage": "Checkpoint created (git state + worktree DAG, no agent draft): %s"}\n' \
    ".claude/checkpoint-$session_id-$timestamp.md"
fi
