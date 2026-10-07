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
#   claude-sessions.sh [list]                      # every session of this worktree, newest first
#   claude-sessions.sh relink                      # list candidate directories (always a dry run)
#   claude-sessions.sh relink <candidate> --apply  # move THAT ONE under this worktree's name
#
# `relink --apply` refuses without a named candidate, by design: a directory under another slug
# may be a live session started from a subdirectory of this worktree, not a moved one. Hence
# "candidate" and never "orphan" — the tool cannot tell them apart, so it does not pretend to.
# `list`, for the same reason, shows them all and marks where each was started.
set -uo pipefail

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

# Reproduces the harness's own cwd encoding, e.g.
#   /Volumes/git-worktree-store/seedmatic/rke2lab.d/develop
#   -> -Volumes-git-worktree-store-seedmatic-rke2lab-d-develop
encode() { printf '%s' "$1" | sed 's/[^a-zA-Z0-9]/-/g'; }

# ★ Anchored to the WORKTREE, never to CLAUDE_CONFIG_DIR. That variable falls back to ~/.claude
# whenever the config home is misrouted, and `relink --apply` reads every directory it finds here.
# Pointed at ~/.claude it would move EVERY other project's transcripts under this worktree's name.
# The default dry run would have softened that, not prevented it. Anchoring removes the
# possibility instead of guarding it: every directory under a worktree's own config home belongs
# to that worktree.
projects="$root/.claude/projects"
rootslug="$(encode "$root")"
here="$projects/$rootslug"

# Said once, to stderr, because it changes what `list` can possibly show.
if [[ -n "${CLAUDE_CONFIG_DIR:-}" ]] &&
  [[ "$(cd "$CLAUDE_CONFIG_DIR" 2>/dev/null && pwd -P)" != "$(cd "$root/.claude" 2>/dev/null && pwd -P)" ]]; then
  echo "warning: CLAUDE_CONFIG_DIR is '$CLAUDE_CONFIG_DIR', not this worktree — the running" >&2
  echo "         session writes elsewhere and is absent below." >&2
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
#
# ★ The tag filter runs inside yq, on whole BLOCKS, and that placement is the point. Filtering
# line by line downstream only drops a reminder's FIRST line: a multi-line block leaks its
# second line through, and the listing then reports injected harness text as the user's
# question. A block either starts with a tag and is discarded whole, or it does not and its
# first line is genuinely theirs.
first_prompt() {
  local p
  p="$(head -n 600 "$1" 2>/dev/null |
    yq --no-doc -p json 'select(.type == "user")
          | with(.message.content | select(tag == "!!str"); . = [{"type": "text", "text": .}])
          | .message.content[] | select(.type == "text") | .text
          | select(test("^\s*<(system-reminder|ide_opened_file|ide_selection|command-name|local-command|user-prompt)") | not)' 2>/dev/null |
    sed 's/^[[:space:]]*//' |
    grep -vE '^(Caveat:|\[Request interrupted)' |
    grep -vE '^[-=_*#[:space:]]*$' |
    head -1 | cut -c1-72)"
  printf '%s' "${p:-<nothing said in the first 600 lines>}"
}

# ★ Scans EVERY directory under this worktree's projects/, not just the root slug.
#
# A session started in a subdirectory — say `<worktree>/manifests` — is keyed on that cwd and so
# gets its own slug, while the config home still comes from the git root and is therefore shared.
# It is a session of this worktree by any reading, and the whole point of this command is to find
# the one you lost; listing only the root slug would hide it at the moment it is wanted. The
# sorting is global across directories, because "newest first" is only useful if it is true of
# the whole set.
cmd_list() {
  if [[ ! -d "$projects" ]]; then
    echo "no projects directory under this worktree at $projects" >&2
    echo "(if the worktree was moved, try: ${0##*/} relink)" >&2
    return 1
  fi
  local f n=0 dir slug from id
  # ls -t: most recently written first — the session you just lost is at the top.
  #
  # ★ Read whole LINES. A command substitution word-splits, so a worktree path containing a
  # space turned one transcript into several bogus rows, with `du` and `date` erroring and the
  # command still exiting 0. The earlier `# shellcheck disable=SC2045` justified itself with
  # "transcript names are UUIDs: no spaces" — true of the FILENAMES, false of the PATH they sit
  # in, which is the part a user chooses. A disable whose reason does not cover the actual risk
  # is worse than the warning. The residual limit is a newline inside the path, which `ls`
  # cannot express either way.
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    n=$((n + 1))
    dir=${f%/*}
    slug=${dir##*/}
    # Where it was started, when that is not the worktree root — the only way to tell two
    # otherwise identical-looking sessions apart.
    from=""
    [[ "$dir" == "$here" ]] || from="[${slug#"$rootslug"-}] "
    id=${f##*/}
    printf '%-36s %6s  %s  %s%s\n' \
      "${id%.jsonl}" \
      "$(du -h "$f" | cut -f1)" \
      "$(date -r "$f" '+%m-%d %H:%M')" \
      "$from" \
      "$(first_prompt "$f")"
    # Process substitution, not a pipe: a pipe would run the loop in a subshell and `n` would
    # come back to zero, so an empty listing would be indistinguishable from a full one.
  done < <(ls -t "$projects"/*/*.jsonl 2>/dev/null)
  ((n > 0)) || {
    echo "no transcript under $projects" >&2
    return 1
  }
}

# ★ A directory that is not this worktree's slug is a CANDIDATE, never certainly an orphan —
# and that distinction is why --apply has to be told which one to move.
#
# The transcript directory is keyed on the session's own cwd, while the config home comes from
# the GIT ROOT of that cwd. So a perfectly live session started in `<worktree>/manifests` lands
# in this very config home under its own slug. An earlier version called every such directory
# orphaned and moved them all: it would have merged two distinct sessions' histories into one
# name, silently. Reporting candidates is harmless; moving one is not, so the move names its
# source and moves nothing else.
cmd_relink() {
  local apply=no src="" a d n moved=0 found=0
  # ★ Only a DOUBLE dash marks a flag here. Every slug begins with a single "-", because the
  # encoded path begins with "/" — so treating "-*" as a flag rejects every legitimate argument
  # this command takes. Measured the hard way: `relink <slug> --apply` answered
  # "unknown flag: -Volumes-...".
  for a in "$@"; do
    case "$a" in
    --apply) apply=yes ;;
    --*)
      echo "unknown flag: $a" >&2
      return 2
      ;;
    *) src="$a" ;;
    esac
  done

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
    found=$((found + 1))
    echo "candidate: $(basename "$d")  ($n transcript(s))"
  done

  ((found > 0)) || {
    echo "no candidate — every transcript here is already under this worktree's name"
    return 0
  }

  if [[ "$apply" == no ]]; then
    echo
    echo "dry run. To move ONE of them under $(basename "$here"):"
    echo "  $(basename "$0") relink <candidate> --apply"
    echo "First make sure it is a moved worktree and not a live session started from a"
    echo "subdirectory of this one — both live here, under different names."
    return 0
  fi

  [[ -n "$src" ]] || {
    echo "refusing: --apply needs the candidate to move, named explicitly." >&2
    echo "A candidate above may be a LIVE session started from a subdirectory of this worktree;" >&2
    echo "moving it would merge two distinct sessions' histories under one name." >&2
    return 2
  }

  # ★ Parameter expansion, NOT basename. Every slug begins with "-", which basename reads as an
  # option: it printed nothing, `src` silently became the projects directory itself, and the
  # move below then walked every sibling in it — relocating a live transcript directory into
  # another one. Measured, not imagined. `${v##*/}` has no option parsing to be fooled by.
  local name=${src##*/}
  [[ -n "$name" && "$name" != "." && "$name" != ".." ]] || {
    echo "not a usable candidate name: '$src'" >&2
    return 2
  }
  src="$projects/$name"
  [[ -d "$src" ]] || {
    echo "no such candidate: $name" >&2
    return 1
  }
  [[ "$src" != "$here" ]] || {
    echo "that is already this worktree's own directory — nothing to do" >&2
    return 1
  }

  mkdir -p "$here" || {
    echo "cannot create $here" >&2
    return 1
  }
  # Move entry by entry so an existing target directory is merged, not clobbered.
  #
  # ★ A failed move must be FATAL to the exit status. There is no `set -e` here, and the earlier
  # `mv … && moved=$((moved+1))` only suppressed the counter: a permission error, an I/O error or
  # a full disk produced a PARTIAL repair, a reassuring "moved N" and exit 0. For a recovery tool
  # that is the worst possible failure mode — it tells you your history is safe while half of it
  # is somewhere else.
  local e t failed=0
  for e in "$src"/* "$src"/.[!.]*; do
    [[ -e "$e" ]] || continue
    t="$here/${e##*/}"
    if [[ -e "$t" ]]; then
      echo "  SKIP ${e##*/} — already present at the current name"
    elif mv "$e" "$t"; then
      moved=$((moved + 1))
    else
      echo "  FAILED to move ${e##*/}" >&2
      failed=$((failed + 1))
    fi
  done
  rmdir "$src" 2>/dev/null && echo "  removed the empty ${src##*/}"
  echo "moved $moved entr(ies) into ${here##*/}"
  ((failed == 0)) || {
    echo "$failed entr(ies) could NOT be moved: the repair is PARTIAL and $src still holds them." >&2
    return 1
  }
}

case "${1:-list}" in
list) cmd_list ;;
relink)
  shift
  cmd_relink "$@"
  ;;
*)
  echo "usage: $(basename "$0") [list | relink [<candidate>] [--apply]]" >&2
  exit 2
  ;;
esac
