#!/usr/bin/env bash
# The config home: where this session's history is written.
#
#   (no args)  SessionStart hook — silent when healthy, emits a systemMessage when not.
#   explain    print which mechanism is ACTUALLY in force, measured now.
#
# `explain` exists because this wiring has already drifted once: project memory recorded a
# "clean-split" model (claudeProcessWrapper disabled, config home set by
# claudeCode.environmentVariables) that was NOT what ran — the wrapper had come back, and
# nothing re-checked the claim for months. Prose can be wrong for a long time. So the authority
# is this command, which reads the live system whenever asked, plus the hook, which runs at
# every session start.
#
# Checked at START because the transcript's location is fixed when the process launches. A
# session that landed in the wrong config home cannot be redirected, and its history simply
# does not appear in /resume from this workspace — the "I lost my conversation" symptom.
#
# What decides it: claude-hub's claude-config-home-wrapper.sh, delivered by home-manager
# (ndh/modules/home-manager/claude-code.nix) and wired through
# `claudeCode.claudeProcessWrapper` in <repo>.d/<branch>.code-workspace. It derives
# CLAUDE_CONFIG_DIR from the GIT ROOT OF THE CWD, and only when that root carries a
# .claude/hub — which is how one fixed wrapper path serves every worktree (the extension
# cannot substitute ${workspaceFolder}, so an env var would have to be absolute and rewritten
# per worktree).
#
# The catch, measured 2026-10-02: of the 7 folders in develop.code-workspace, exactly ONE
# satisfies that guard, so the arrangement rests on the extension launching with the FIRST
# folder as cwd — an invariant nothing stated. And the wrapper falls through SILENTLY by design
# (the extension also invokes it for `auth status --json` with cwd=/), so a misroute is
# invisible. Fixing that at the source costs a claude-hub commit, an outward push, an ndh
# flake-lock bump and a home-manager switch — hence the assertion lives here instead.
set -uo pipefail

# Compare resolved paths: /Volumes vs /private, or a symlinked spelling of the same directory,
# is correct, and a string compare would call it a mismatch.
resolve() { (cd "$1" 2>/dev/null && pwd -P); }

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
ws="$(dirname "$root")/$(basename "$root").code-workspace"

cfg_ok=no
[[ -n "${CLAUDE_CONFIG_DIR:-}" && "$(resolve "${CLAUDE_CONFIG_DIR:-/nonexistent}")" == "$(resolve "$root/.claude")" ]] && cfg_ok=yes
hub_ok=no
[[ -d "$root/.claude/hub" ]] && hub_ok=yes
first="$(yq --no-doc -p json '.folders[0].path // ""' "$ws" 2>/dev/null || true)"
first_ok=no
[[ -n "$first" && "$(resolve "$(dirname "$ws")/$first")" == "$(resolve "$root")" ]] && first_ok=yes

problems=()
if [[ -z "${CLAUDE_CONFIG_DIR:-}" ]]; then
  problems+=("CLAUDE_CONFIG_DIR is UNSET — this session's history goes to ~/.claude and will NOT be offered by /resume in this workspace")
elif [[ "$cfg_ok" == no ]]; then
  problems+=("CLAUDE_CONFIG_DIR is '$CLAUDE_CONFIG_DIR' but this worktree is '$root' — history lands outside the workspace")
fi
# The wrapper's own guard, restated: without this directory the NEXT session falls back silently.
[[ "$hub_ok" == yes ]] ||
  problems+=("$root/.claude/hub is MISSING — the claudeProcessWrapper guard needs it to set CLAUDE_CONFIG_DIR at all")
# The unwritten invariant: the extension launches with the workspace's FIRST folder as cwd, and
# the .code-workspace is generated, so a reorder is cheap to make and invisible to notice.
[[ -z "$first" || "$first_ok" == yes ]] ||
  problems+=("the first folder of $(basename "$ws") is '$first', not this worktree — a new window would resolve its config home elsewhere")

if [[ "${1:-}" == "explain" ]]; then
  echo "config home — measured now, not remembered"
  echo
  printf '  mechanism         %s\n' \
    "$(yq --no-doc -p json '.settings["claudeCode.claudeProcessWrapper"] // "NONE in the workspace file"' "$ws" 2>/dev/null)"
  # grep -c prints 0 AND exits 1 on no match, so take its output and default only if empty.
  envvars="$(grep -c 'claudeCode.environmentVariables' "$ws" 2>/dev/null)"
  printf '  clean-split       claudeCode.environmentVariables: %s occurrence(s)\n' "${envvars:-0}"
  echo
  printf '  [%s] CLAUDE_CONFIG_DIR  %s\n' "$cfg_ok" "${CLAUDE_CONFIG_DIR:-<unset — history goes to ~/.claude>}"
  printf '  [%s] .claude/hub        %s\n' "$hub_ok" "$root/.claude/hub"
  printf '  [%s] workspace folder 0 %s\n' "$first_ok" "${first:-<unknown>}"

  # The number that explains why this is delicate: one folder satisfies the guard, so the
  # launch cwd is doing all the work.
  total=0 own=0
  while IFS= read -r p; do
    [[ -n "$p" ]] || continue
    total=$((total + 1))
    r="$(cd "$(dirname "$ws")/$p" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null)"
    [[ -n "$r" && -d "$r/.claude/hub" ]] && own=$((own + 1))
  done < <(yq --no-doc -p json '.folders[].path' "$ws" 2>/dev/null)
  echo
  printf '  own config home: %s of %s folders — the other %s fall back to ~/.claude, silently.\n' \
    "$own" "$total" "$((total - own))"
  echo
  if ((${#problems[@]} == 0)); then
    echo "verdict: healthy — history is being written inside this worktree."
  else
    printf 'verdict: %s problem(s)\n' "${#problems[@]}"
    printf '  - %s\n' "${problems[@]}"
  fi
  exit 0
fi

cat >/dev/null 2>&1 || true # drain the hook payload; nothing here needs it

if ((${#problems[@]} > 0)); then
  msg="CONFIG HOME: $(
    IFS='; '
    echo "${problems[*]}"
  )"
  # Escape for JSON. The message carries PATHS, and a backslash or a double quote in one
  # would make the payload invalid — so the cry would be swallowed in exactly the situation
  # where it is the only thing standing between the user and a lost session.
  msg=${msg//\\/\\\\}
  printf '{"systemMessage": "%s"}\n' "${msg//\"/\\\"}"
fi
