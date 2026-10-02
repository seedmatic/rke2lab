#!/usr/bin/env bats
# Regressions for config-home-guard.sh. Every case here is something that actually went wrong
# on 2026-10-02, not a hypothetical.

load helper

setup() { setup_fake_seat; }

@test "silent when the config home is this worktree" {
  run env -u CLAUDE_CONFIG_DIR_UNSET bash "$BIN/config-home-guard.sh" </dev/null
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}

@test "cries when CLAUDE_CONFIG_DIR is unset — the history would go to ~/.claude" {
  run env -u CLAUDE_CONFIG_DIR bash "$BIN/config-home-guard.sh" </dev/null
  [ "$status" -eq 0 ]
  [[ "$output" == *"CLAUDE_CONFIG_DIR is UNSET"* ]]
}

@test "cries when CLAUDE_CONFIG_DIR points outside this worktree" {
  run env CLAUDE_CONFIG_DIR="$BATS_TEST_TMPDIR" bash "$BIN/config-home-guard.sh" </dev/null
  [[ "$output" == *"history lands outside the workspace"* ]]
}

@test "cries when .claude/hub is missing — the wrapper could not set the variable at all" {
  rmdir "$SEAT/.claude/hub"
  run bash "$BIN/config-home-guard.sh" </dev/null
  [[ "$output" == *".claude/hub is MISSING"* ]]
}

# Copilot, 2026-10-02: an unreadable folder 0 was treated as success, so the guard printed
# "[no] workspace folder 0 <unknown>" and still returned healthy — answering something smaller
# instead of saying it could not answer, which is the exact failure it exists to catch.
@test "a MISSING .code-workspace is a problem, not a pass" {
  rm -f "$WS"
  run bash "$BIN/config-home-guard.sh" </dev/null
  [[ "$output" == *"CANNOT be checked"* ]]
}

@test "a MALFORMED .code-workspace is a problem, not a pass" {
  printf 'this is not json {{{\n' >"$WS"
  run bash "$BIN/config-home-guard.sh" </dev/null
  [[ "$output" == *"CANNOT be checked"* ]]
}

@test "explain never says healthy while it reports a failed check" {
  printf 'this is not json {{{\n' >"$WS"
  run bash "$BIN/config-home-guard.sh" explain
  [ "$status" -eq 0 ]
  [[ "$output" != *"verdict: healthy"* ]]
  [[ "$output" == *"problem(s)"* ]]
}

@test "explain reports the mechanism actually in force" {
  run bash "$BIN/config-home-guard.sh" explain
  [[ "$output" == *"measured now, not remembered"* ]]
  [[ "$output" == *"verdict: healthy"* ]]
}

@test "folder 0 pointing elsewhere is a problem" {
  mkdir -p "$BATS_TEST_TMPDIR/other"
  printf '{ "folders": [ { "path": "other" }, { "path": "seat" } ] }\n' >"$WS"
  run bash "$BIN/config-home-guard.sh" </dev/null
  [[ "$output" == *"not this worktree"* ]]
}

# The payload carries PATHS. An unescaped quote produced invalid JSON, so the cry was swallowed
# in precisely the case where it is the only warning the user gets.
@test "the cry stays valid JSON when the path contains a quote and a backslash" {
  run env CLAUDE_CONFIG_DIR='/tmp/we"ir\d' bash "$BIN/config-home-guard.sh" </dev/null
  [ -n "$output" ]
  printf '%s' "$output" | yq -p json '.systemMessage' >/dev/null
}
