#!/usr/bin/env bash
# Manual command, NOT a hook: list this worktree's Claude sessions, and repair the one thing
# that silently orphans them.
#
# Why it exists. Transcripts live under the config home in a directory named after the ENCODED
# CWD (every non-alphanumeric character becomes "-"). Two consequences, both measured on
# 2026-10-02:
#   1. Finding "the session that carried a role" meant grepping eight .jsonl files of 0.4 MB
#      to 117 MB by hand. The only thing that identifies a session is the first thing the user
#      actually asked, so that is what `list` prints.
#   2. Moving or renaming the worktree re-encodes that directory name, so /resume stops
#      offering the history until it is renamed. That was carried as a step to remember in a
#      handoff; `relink` does it instead.
#
# Usage:
#   claude-sessions.sh [list]            # one line per session, most recently written first
#   claude-sessions.sh relink            # report orphaned transcript directories (dry run)
#   claude-sessions.sh relink --apply    # move them under this worktree's current name
set -uo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

# Reproduces the harness's own cwd encoding, e.g.
#   /Volumes/git-worktree-store/seedmatic/rke2lab.d/develop
#   -> -Volumes-git-worktree-store-seedmatic-rke2lab-d-develop
encode() { printf '%s' "$1" | sed 's/[^a-zA-Z0-9]/-/g'; }

# ★ Anchored to the WORKTREE, never to CLAUDE_CONFIG_DIR. That variable falls back to
# ~/.claude whenever the config home is misrouted — the very case config-home-guard.sh
# exists to catch — and `relink --apply` reads every directory it finds here. Pointed at
# ~/.claude it would move EVERY other project's transcripts under this worktree's name. The
# default dry run would have softened that, not prevented it. Anchoring removes the
# possibility instead of guarding it: every directory under a worktree's own config home
# belongs to that worktree.
projects="$root/.claude/projects"
here="$projects/$(encode "$root")"

# Said once, to stderr, because it changes what `list` can possibly show.
if [[ -n "${CLAUDE_CONFIG_DIR:-}" ]] &&
  [[ "$(cd "$CLAUDE_CONFIG_DIR" 2>/dev/null && pwd -P)" != "$(cd "$root/.claude" 2>/dev/null && pwd -P)" ]]; then
  echo "warning: CLAUDE_CONFIG_DIR is '$CLAUDE_CONFIG_DIR', not this worktree — the running" >&2
  echo "         session writes elsewhere and is absent below. See: config-home-guard.sh explain" >&2
fi

# A "user" entry is not the same thing as something the user said: the harness replays IDE
# events, interruption notices and reminders through the same channel. So drop those and take
# the first line that is actually a question.
#
# yq-go reads the .jsonl as one document per line (--no-doc suppresses the "---" it would
# otherwise interleave). `content` comes in two shapes — a bare string, or a block list — so a
# `with` statement NORMALISES the string into the list shape and a single path then reads both.
# No branching, one extraction. Taking only "text" blocks also skips tool results, instead of
# joining them into blanks.
first_prompt() {
  local p
  p="$(head -n 600 "$1" 2>/dev/null |
    yq --no-doc -p json 'select(.type == "user")
          | with(.message.content | select(tag == "!!str"); . = [{"type": "text", "text": .}])
          | .message.content[] | select(.type == "text") | .text' 2>/dev/null |
    sed 's/^[[:space:]]*//' |
    grep -vE '^<(system-reminder|ide_|command-name|local-command|user-prompt)' |
    grep -vE '^(Caveat:|\[Request interrupted)' |
    grep -vE '^[-=_*#[:space:]]*$' |
    head -1 | cut -c1-72)"
  printf '%s' "${p:-<nothing said in the first 600 lines>}"
}

cmd_list() {
  if [[ ! -d "$here" ]]; then
    echo "no transcript directory for this worktree at $here" >&2
    echo "(if the worktree was moved, try: $(basename "$0") relink)" >&2
    return 1
  fi
  local f
  # ls -t: most recently written first — the session you just lost is at the top.
  # shellcheck disable=SC2045 # transcript names are UUIDs: no spaces, no glob characters
  for f in $(ls -t "$here"/*.jsonl 2>/dev/null); do
    printf '%-36s %6s  %s  %s\n' \
      "$(basename "$f" .jsonl)" \
      "$(du -h "$f" | cut -f1)" \
      "$(date -r "$f" '+%m-%d %H:%M')" \
      "$(first_prompt "$f")"
  done
}

cmd_relink() {
  local apply="${1:-}" d n moved=0
  [[ -d "$projects" ]] || {
    echo "no $projects — nothing to relink" >&2
    return 1
  }
  for d in "$projects"/*/; do
    d="${d%/}"
    [[ -d "$d" ]] || continue
    [[ "$d" != "$here" ]] || continue
    n="$(find "$d" -maxdepth 1 -name '*.jsonl' | wc -l | tr -d ' ')"
    [[ "$n" != 0 ]] || continue
    echo "orphaned: $(basename "$d")  ($n transcript(s))"
    if [[ "$apply" == "--apply" ]]; then
      mkdir -p "$here"
      # Move entry by entry so an existing target directory is merged, not clobbered.
      local e t
      for e in "$d"/* "$d"/.[!.]*; do
        [[ -e "$e" ]] || continue
        t="$here/$(basename "$e")"
        if [[ -e "$t" ]]; then
          echo "  SKIP $(basename "$e") — already present at the current name"
        else
          mv "$e" "$t" && moved=$((moved + 1))
        fi
      done
      rmdir "$d" 2>/dev/null && echo "  removed the empty $(basename "$d")"
    fi
  done
  if [[ "$apply" == "--apply" ]]; then
    echo "moved $moved entr(ies) into $(basename "$here")"
  else
    echo "dry run — re-run with --apply to move them into $(basename "$here")"
  fi
}

case "${1:-list}" in
list) cmd_list ;;
relink) cmd_relink "${2:-}" ;;
*)
  echo "usage: $(basename "$0") [list|relink [--apply]]" >&2
  exit 2
  ;;
esac
