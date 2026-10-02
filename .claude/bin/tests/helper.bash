#!/usr/bin/env bash
# Shared fixture for the .claude/bin suites.
#
# Every test runs against a FAKE seat under the per-test temp dir, never against the real
# .claude/projects. That is not politeness: the defect that prompted this suite relocated a live
# transcript directory, so a suite that exercised `relink --apply` on the real one could destroy
# the very history it is meant to protect.

BIN="$BATS_TEST_DIRNAME/.."

# Reproduces the harness's cwd encoding, and must stay identical to the one in
# claude-sessions.sh — if they ever diverge, these tests pass while the tool looks in the
# wrong directory.
encode() { printf '%s' "$1" | sed 's/[^a-zA-Z0-9]/-/g'; }

# A seat is a worktree-shaped directory: a .claude/hub (what the wrapper's guard looks for), a
# .claude/projects/<slug>, and a .code-workspace sitting ONE LEVEL ABOVE it, as in the real
# layout.
#
# Takes an optional directory NAME, so a test can ask for a path containing a space — the shape
# that turned one transcript into several bogus rows.
setup_fake_seat() {
  local name="${1:-seat}"
  SEAT="$BATS_TEST_TMPDIR/$name"
  mkdir -p "$SEAT/.claude/hub"
  SLUG="$(encode "$SEAT")"
  PROJECTS="$SEAT/.claude/projects"
  HERE="$PROJECTS/$SLUG"
  mkdir -p "$HERE"
  WS="$BATS_TEST_TMPDIR/$name.code-workspace"
  printf '{ "folders": [ { "path": "%s" } ], "settings": {} }\n' "$name" >"$WS"
  export CLAUDE_PROJECT_DIR="$SEAT"
  export CLAUDE_CONFIG_DIR="$SEAT/.claude"
}

# One transcript whose first user turn is a multi-line harness block, followed by what the user
# actually said. The block is the point: filtering line by line only drops its first line.
write_transcript_with_reminder() {
  local f="$1" said="$2"
  {
    printf '{"type":"user","message":{"content":[{"type":"text","text":"<system-reminder>\\nCodebase and user instructions are shown below.\\nTHIS LINE IS NOT THE USER SPEAKING\\n</system-reminder>"}]}}\n'
    printf '{"type":"user","message":{"content":[{"type":"text","text":"%s"}]}}\n' "$said"
  } >"$f"
}
