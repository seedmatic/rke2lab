#!/usr/bin/env bats
# Regressions for claude-sessions.sh. Every case here is a defect that actually occurred on
# 2026-10-02 — found by the integration session, by Copilot, or by the author's own test run,
# which relocated a live transcript directory before anyone noticed.

load helper

setup() { setup_fake_seat; }

# Copilot: filtering line by line only drops a harness block's FIRST line, so the second line
# was reported as the user's question.
@test "list skips a multi-line harness block and shows what the user said" {
  write_transcript_with_reminder "$HERE/aaaaaaaa-1111-2222-3333-444444444444.jsonl" "voici ma vraie question"
  run bash "$BIN/claude-sessions.sh"
  [ "$status" -eq 0 ]
  [[ "$output" == *"voici ma vraie question"* ]]
  [[ "$output" != *"NOT THE USER SPEAKING"* ]]
}

@test "list reads the bare-string shape of content as well as the block list" {
  printf '{"type":"user","message":{"content":"une question en chaine nue"}}\n' \
    >"$HERE/bbbbbbbb-1111-2222-3333-444444444444.jsonl"
  run bash "$BIN/claude-sessions.sh"
  [[ "$output" == *"une question en chaine nue"* ]]
}

# Copilot, round 2: list scanned only the root slug, so a session started in a subdirectory of
# this worktree — which shares this config home and is a session of this worktree by any
# reading — was invisible at the exact moment the command is wanted.
@test "list shows a session started from a subdirectory, and says where" {
  mkdir -p "$HERE-manifests"
  write_transcript_with_reminder "$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl" "question depuis manifests"
  write_transcript_with_reminder "$HERE/aaaaaaaa-1111-2222-3333-444444444444.jsonl" "question depuis la racine"
  run bash "$BIN/claude-sessions.sh"
  [ "$status" -eq 0 ]
  [[ "$output" == *"question depuis manifests"* ]]
  [[ "$output" == *"question depuis la racine"* ]]
  [[ "$output" == *"[manifests]"* ]]
}

@test "list sorts across directories, not within each one" {
  mkdir -p "$HERE-manifests"
  write_transcript_with_reminder "$HERE/aaaaaaaa-1111-2222-3333-444444444444.jsonl" "la plus ancienne"
  sleep 1
  write_transcript_with_reminder "$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl" "la plus recente"
  run bash "$BIN/claude-sessions.sh"
  [[ "${lines[0]}" == *"la plus recente"* ]]
}

@test "a root-slug session carries no origin marker" {
  write_transcript_with_reminder "$HERE/aaaaaaaa-1111-2222-3333-444444444444.jsonl" "depuis la racine"
  run bash "$BIN/claude-sessions.sh"
  [[ "$output" != *"["* ]]
}

@test "list says so rather than lying when a transcript holds nothing said" {
  printf '{"type":"assistant","message":{"content":[{"type":"text","text":"moi, pas lui"}]}}\n' \
    >"$HERE/cccccccc-1111-2222-3333-444444444444.jsonl"
  run bash "$BIN/claude-sessions.sh"
  [[ "$output" == *"nothing said"* ]]
}

@test "relink reports no candidate when everything is already under this name" {
  run bash "$BIN/claude-sessions.sh" relink
  [ "$status" -eq 0 ]
  [[ "$output" == *"no candidate"* ]]
}

# A directory under a DIFFERENT slug may be a live session started from a subdirectory of this
# worktree — the transcript dir is keyed on the cwd while the config home comes from the git
# root. So it is a candidate, never certainly an orphan.
@test "a differently-named directory is a candidate, not an orphan" {
  mkdir -p "$HERE-manifests"
  : >"$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl"
  run bash "$BIN/claude-sessions.sh" relink
  [[ "$output" == *"candidate:"* ]]
  [[ "$output" != *"orphan"* ]]
}

@test "--apply without a named candidate refuses, and moves nothing" {
  mkdir -p "$HERE-manifests"
  : >"$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl"
  run bash "$BIN/claude-sessions.sh" relink --apply
  [ "$status" -eq 2 ]
  [[ "$output" == *"refusing"* ]]
  [ -f "$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl" ]
}

# The slug begins with "-", because the encoded path begins with "/". An arg parser that treats
# "-*" as a flag rejects every legitimate argument; basename reads it as an option and prints
# NOTHING, which made the move walk the whole projects directory.
@test "a named candidate whose slug starts with a dash is moved, and only it" {
  mkdir -p "$HERE-manifests" "$PROJECTS/-some-other-project"
  : >"$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl"
  : >"$PROJECTS/-some-other-project/eeeeeeee-1111-2222-3333-444444444444.jsonl"
  run bash "$BIN/claude-sessions.sh" relink "$SLUG-manifests" --apply
  [ "$status" -eq 0 ]
  [[ "$output" != *"unknown flag"* ]]
  [ -f "$HERE/dddddddd-1111-2222-3333-444444444444.jsonl" ]
  [ ! -d "$HERE-manifests" ]
  # The sibling must be exactly where it was. This is the assertion that would have caught the
  # relocation of a live transcript directory.
  [ -f "$PROJECTS/-some-other-project/eeeeeeee-1111-2222-3333-444444444444.jsonl" ]
}

# Copilot, round 3: `for f in $(ls -t …)` word-splits, so a worktree path with a space turned one
# transcript into several bogus rows — du and date erroring, exit status still 0.
@test "a worktree path containing a space yields one row per transcript" {
  setup_fake_seat "seat with space"
  write_transcript_with_reminder "$HERE/aaaaaaaa-1111-2222-3333-444444444444.jsonl" "une seule ligne attendue"
  run bash "$BIN/claude-sessions.sh"
  [ "$status" -eq 0 ]
  [ "${#lines[@]}" -eq 1 ]
  [[ "${lines[0]}" == *"une seule ligne attendue"* ]]
  [[ "$output" != *"No such file"* ]]
}

# Copilot, round 3, high severity: there is no `set -e`, and `mv … && moved=…` only suppressed
# the counter — so a partial repair exited 0 with a reassuring "moved N". For a recovery tool
# that is the worst failure mode: it says the history is safe while half of it is elsewhere.
@test "a move that cannot be performed fails loudly instead of claiming success" {
  mkdir -p "$HERE-manifests"
  : >"$HERE-manifests/dddddddd-1111-2222-3333-444444444444.jsonl"
  chmod 500 "$HERE"
  run bash "$BIN/claude-sessions.sh" relink "$SLUG-manifests" --apply
  chmod 700 "$HERE"
  [ "$status" -ne 0 ]
  [[ "$output" == *"FAILED to move"* ]]
  [[ "$output" == *"PARTIAL"* ]]
}

@test "a real unknown flag is still rejected" {
  run bash "$BIN/claude-sessions.sh" relink --bogus
  [ "$status" -eq 2 ]
  [[ "$output" == *"unknown flag"* ]]
}

# The integration session's finding: reading the projects directory from CLAUDE_CONFIG_DIR meant
# that a misrouted session would have moved every other project's transcripts under this
# worktree's name.
@test "relink is anchored to the worktree, never to CLAUDE_CONFIG_DIR" {
  FOREIGN="$BATS_TEST_TMPDIR/foreign/.claude"
  mkdir -p "$FOREIGN/projects/-a-totally-different-project"
  : >"$FOREIGN/projects/-a-totally-different-project/ffffffff-1111-2222-3333-444444444444.jsonl"
  run env CLAUDE_CONFIG_DIR="$FOREIGN" bash "$BIN/claude-sessions.sh" relink
  [[ "$output" != *"-a-totally-different-project"* ]]
  [ -f "$FOREIGN/projects/-a-totally-different-project/ffffffff-1111-2222-3333-444444444444.jsonl" ]
}

@test "a misrouted config home is announced, because it changes what list can show" {
  run env CLAUDE_CONFIG_DIR="$BATS_TEST_TMPDIR" bash "$BIN/claude-sessions.sh"
  [[ "$output" == *"not this worktree"* ]]
}
