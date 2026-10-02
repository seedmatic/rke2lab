# Claude Code Hooks

This directory contains hook scripts that run during Claude Code session lifecycle events.

## Checkpoint System

The checkpoint system automatically creates snapshots of your work before context compaction, allowing you to recover state if needed. It merges **agent-authored session context** with **git state** for complete recovery points.

### How it works

1. **SessionStart** (`session-start-reminder.sh`): Reminds Claude to maintain checkpoint draft
   - Checks if draft exists and is current (< 30 minutes old)
   - Reminds Claude to create/update draft using `precompact-session-state` skill
   - Ensures draft stays fresh during long sessions

2. **Agent Draft** (`.claude/checkpoint-draft-<session_id>.md`): Session context
   - Written by Claude using the `precompact-session-state` skill
   - Contains conversation-specific state git cannot capture:
     - Current task and motivation
     - Approach taken and decisions made
     - Discoveries and learnings
     - Blockers and open questions
     - Concrete next steps with file paths
   - Updated periodically (~20-30 turns) or at milestones
   - Ephemeral: deleted after merge into final checkpoint

3. **PreCompact** (`checkpoint-write.sh`): Creates checkpoint before compaction
   - Filename format: `checkpoint-<session_id>-<timestamp>.md`
   - Timestamp format: `YYYYMMDD-HHMMSS` (e.g., `20260605-191826`)
   - **If agent draft exists**: merges agent context + git state → complete recovery point
   - **If no draft**: falls back to git state only (commits, status, generic next steps)
   - Non-destructive: only writes, never deletes

4. **PostCompact** (`checkpoint-gc.sh`): Garbage-collects old checkpoints
   - Phase 1: Removes ALL checkpoints for deleted sessions (orphans)
   - Phase 2: For active sessions, keeps only the N most recent checkpoints
   - Default: keep last 3 checkpoints per session
   - Session ID extraction: strips `-YYYYMMDD-HHMMSS` suffix from filename

### Configuration

Set the `CLAUDE_CHECKPOINT_KEEP_LAST` environment variable to control retention:

```json
{
  "env": {
    "CLAUDE_CHECKPOINT_KEEP_LAST": "5"
  }
}
```

Default is 3 if not set.

### Files

- `session-start-reminder.sh` - SessionStart hook (reminds Claude to maintain draft)
- `checkpoint-write.sh` - PreCompact hook (merges draft + git state)
- `checkpoint-gc.sh` - PostCompact hook (garbage collection)
- Generated checkpoints: `.claude/checkpoint-*.md` (gitignored)
- Agent drafts: `.claude/checkpoint-draft-*.md` (ephemeral, cleaned after merge)

### Manual operations

```bash
# List checkpoints for a session
ls -lt .claude/checkpoint-<session_id>-*.md

# View a specific checkpoint
cat .claude/checkpoint-<session_id>-<timestamp>.md

# Clean all checkpoints (USE WITH CAUTION)
rm .claude/checkpoint-*.md

# Clean checkpoints for a specific session
rm .claude/checkpoint-<session_id>-*.md
```

### Checkpoint Structure

**With agent context** (complete recovery):

```markdown
# Checkpoint — 2026-06-06, 14h30

> Session `abc-123`. Resume with this file.

---

## Current Task
Refactoring 27 ManifestsUnit classes to lazy instantiation pattern.

## Context & Motivation
CDK8s Constructs require Chart scope, but units registered statically...

## Discoveries & Learnings
- Records can't have private canonical constructors
- Uniformity critical for pattern audits

## Next Steps
1. `./mvnw -pl :manifests clean install`
2. `grep -r "ManifestsUnit()" manifests/src/`
3. If clean: commit "refactor: lazy instantiation"

---

## Git State

### Recent commits (last 5)
abc1234 refactor: migrate units to lazy pattern
def5678 feat: add checkpoint system

### Working tree status
M manifests/src/.../FluxResourcesManifestUnit.java
```

**Without agent context** (fallback):

```markdown
# Checkpoint — 2026-06-06, 14h30

> Session `abc-123`. Resume with this file.

## Recent commits (last 5)
...

## Current state
M file1.java
M file2.java

## Next steps
- Review blockers/errors above
- Continue from last commit's goal
```

### Troubleshooting

**No checkpoints created?**

- Check that `yq` (yq-go v4) is available: `yq --version`
- Check hook execution in Claude Code output
- Verify `.claude/settings.json` has SessionStart/PreCompact/PostCompact hooks configured

**Checkpoints missing agent context?**

- Claude must write `.claude/checkpoint-draft-<session_id>.md` before compaction
- Use the `precompact-session-state` skill to create/update the draft
- SessionStart hook will remind Claude if draft is missing/stale

**Too many/few checkpoints kept?**

- Set `CLAUDE_CHECKPOINT_KEEP_LAST` in `.claude/settings.json` env section
- Default is 3 per session

**Orphaned checkpoints not cleaned?**

- GC only runs after successful compaction
- Unkeyed checkpoints (`unkeyed-*`) are never auto-cleaned (manual cleanup needed)

## Usage Tips

**For Claude:**

1. Create checkpoint draft early in complex sessions using `precompact-session-state` skill
2. Update draft periodically (~20-30 turns) or after major milestones
3. Be specific in "Next Steps" - include file paths, commands, line numbers
4. Capture what git cannot: intent, decisions, failed attempts, blockers

**For Users:**

1. If context is lost, check `.claude/checkpoint-<session>-<timestamp>.md` files
2. Most recent checkpoint has latest timestamp
3. Copy checkpoint content to new session to resume work
4. Draft files are ephemeral - don't rely on them for recovery (they're merged into checkpoints)

## Config home and session recovery

Two pieces that keep this worktree's session history findable. They exist because of a
measurement taken on 2026-10-02: of the **7 folders** in `develop.code-workspace`, exactly
**one** satisfies the condition that gives this worktree its own config home. The other 6 are
worktrees of three other repos, and a session launched from one of them falls back to
`~/.claude` — where `/resume` in this workspace will never offer it.

### `config-home-guard.sh` — SessionStart hook, and the answer to "which model is in force?"

```bash
.claude/bin/config-home-guard.sh explain    # read the live system and say
```

Asserts that `CLAUDE_CONFIG_DIR` resolves to *this* worktree's `.claude`, that
`.claude/hub` exists (the wrapper's guard needs it), and that the worktree is still the
**first** folder of the `.code-workspace` — the unwritten invariant that makes the extension
launch with the right cwd. As a hook it is silent when healthy and emits a `systemMessage`
otherwise; `explain` prints the mechanism, the three checks and the 1-of-7 count.

**Why `explain` exists.** This wiring already drifted once: project memory described a
"clean-split" model — wrapper disabled, config home set by `claudeCode.environmentVariables` —
that was not what ran, and nothing re-checked the claim for months. Prose can stay wrong for a
long time, so the authority is a command that re-measures on demand, not a document. If the two
ever disagree again, the command wins and the document is the thing to fix.

Checked at start because the transcript's location is fixed when the process launches: a
session that landed in the wrong config home cannot be redirected.

The config home itself is set by claude-hub's `claude-config-home-wrapper.sh`, delivered by
home-manager and wired through `claudeCode.claudeProcessWrapper` in the `.code-workspace`. It
falls through **silently** by design (the extension also invokes it for `auth status --json`
with `cwd=/`), and fixing that at the source costs a claude-hub commit, an outward push, an
ndh flake-lock bump and a home-manager switch — so the assertion lives here instead.

### `claude-sessions.sh` — manual command

```bash
.claude/bin/claude-sessions.sh              # one line per session, most recently written first
.claude/bin/claude-sessions.sh relink       # report orphaned transcript dirs (dry run)
.claude/bin/claude-sessions.sh relink --apply
```

`list` prints session id, size, last write, and **the first thing the user actually asked** —
the only thing that identifies a session when you are hunting for the one that carried a role.
Without it, that hunt means grepping .jsonl files of 0.4 MB to 117 MB by hand.

`relink` repairs the other failure: transcripts live in a directory named after the *encoded
cwd*, so moving or renaming the worktree orphans them until that name is changed.

## Related Documentation

- [Claude Code hooks documentation](https://docs.anthropic.com/claude-code/hooks)
- `.claude/skills/precompact-session-state.md` - Agent skill for authoring drafts
- Session checkpoint commit: `c1f053d1` (2026-06-04)
- Agent-authored checkpoints commit: `<pending>` (2026-06-06)
