---
name: worktree
description: >-
  Set up or tear down an isolated external git worktree + VSCode workspace for a
  topic. Use when the user wants to start work on a new topic, spin up a
  workspace or worktree, create a branch to work in, isolate a change into its
  own checkout, or clean up a finished worktree ("monte un espace de travail
  pour…", "new workspace", "spin up a worktree", "work on X in isolation",
  "remove this worktree"). Encodes the external-worktree operating model: the
  <repo>.d/<namespace>/<branch> layout, the source-branch choice, the sops
  re-smudge step, the home-dir memory bridge, the Claude backend (Bedrock vs
  enterprise Anthropic) selection, and .code-workspace generation.
---

# External-worktree workspace lifecycle

Every checkout is an **external** git worktree at `<repo>.d/<namespace>/<slug>` —
a **sibling of `main`**, NOT under `.claude/worktrees/`. One VSCode window = one
worktree = one conversation, so parallel sessions never share a checkout.

> **Do NOT use the `EnterWorktree` harness tool.** It hard-codes
> `.claude/worktrees/<branch>`, the location this model rejects (flox `[include]`
> manifest-relative paths don't resolve from there). Use plain `git worktree add`
> at the external path.

## Layout (host-specific — do not hard-code)

Store roots are **per-host**, defined declaratively in the `ndh` repo's host
modules. Determine the real container at runtime from `git worktree list` and
place the new worktree as a sibling under the same `<repo>.d/` container as
`main`; never assume an absolute root.

- Pattern: `<worktree-store>/<org>/<repo>.d/<namespace>/<slug>` for worktrees,
  `<bare-store>/<org>/<repo>.git` for the bare repo.
- **nikopol** (darwin VM on a tart/vz engine, host `vzhost.nikopol`) — the migrated
  layout: `<worktree-store>` = `/Volumes/git-worktree-store`,
  `<bare-store>` = `/Volumes/git-bare-store`.
- **bioskop** — not yet migrated to this volume layout; its roots differ, so
  check the ndh host module rather than assuming nikopol's paths.

`<org>` is part of the path (`nxmatic`, `seedmatic`, …) — orgs were changed
recently, so read the actual remote, don't assume an old org name.

## Namespaces (by kind of work)

`feature/` · `chore/` · `design/` · `refactor/` · `spike/`. Pick the one that
matches the task; the slug is a short kebab-case topic name.

## Create a worktree

Run from any existing worktree of the repo (or `<repo>.d/main`). First **ask the
user** for the choices that aren't obvious — namespace, slug, **source branch**,
and **Claude backend** — then run the steps.

1. **Choose the source branch.** Default to the branch you're currently on (so
   new work stacks on top of it), **not** `main`:
   ```bash
   src="$(git rev-parse --abbrev-ref HEAD)"   # the invoking worktree's branch
   ```
   Override only when the user names another branch. Use `main`/the default
   branch as source **only** when the user explicitly wants a fresh base off
   trunk — then `git fetch origin <default-branch>` and use `origin/<default-branch>`
   as the start point below. If the intended base is ambiguous, ask.
2. **Add the worktree** off the chosen source, at the external path (derive the
   container from `git worktree list`, don't hard-code a root):
   ```bash
   git worktree add -b <namespace>/<slug> <worktree-store>/<org>/<repo>.d/<namespace>/<slug> "$src"
   ```
3. **Re-smudge sops** (any repo whose `.gitattributes` wires a sops smudge/clean
   filter). `git worktree add` checks files out **before** `.sops.yaml` is visible
   to the smudge filter, so sops-governed files land ENCRYPTED and are unusable
   until re-smudged. Don't hardcode the file list — derive it from git's OWN
   attribute resolution, so it auto-covers new paths, honours globs
   (`**/01-secret-*.yaml`), and is a correct **no-op** for repos that DISABLE the
   filter (e.g. `ndh`, whose `.gitattributes` intentionally carries no
   `filter=sops-*` — its worktree MUST stay encrypted for eval-time `readFile`):
   ```bash
   git ls-files -z | git check-attr --stdin -z filter \
     | while IFS= read -r -d '' path; do
         IFS= read -r -d '' _attr; IFS= read -r -d '' value
         case "$value" in sops*) rm -f "$path" && git checkout -- "$path" ;; esac
       done
   ```
   Then verify: a clean `git status --porcelain` confirms every governed file
   re-smudged. (Don't grep for `ENC[` blindly — schemas/docs like
   `.ndh-ssh.d/keys.schema.yaml` carry it as literal text and are NOT governed;
   the loop keys on the `filter` attribute itself, which is exactly right.)
4. **Bridge the session history to your home config.** The Dock-launched VSCode
   extension host lists sessions from `$HOME/.claude/projects/<slug>/` — its own
   `$HOME/.claude`, not the workspace-scoped `CLAUDE_CONFIG_DIR`. The canonical
   wiring keeps this worktree's transcripts **in the worktree** and points the
   home slug at them: `~/.claude/projects/<slug>` → `<worktree>/.claude/projects/<slug>`.
   The symlink target is absolute, so it does **not** survive `git worktree add` —
   recreate it:
   ```bash
   .claude/hub/bin/link-sessions.sh        # ~/.claude/projects/<slug> -> <worktree>/.claude/projects/<slug>
   ```
   This is the **session** bridge only. **Memory is separate** and handled in the
   next step via `autoMemoryDirectory` — do NOT symlink `~/.claude/projects/<slug>/memory`
   (the old hack; slug-fragile, broke on non-main worktrees).
5. **Write `.claude/settings.local.json`** (gitignored, per-checkout). It carries
   two independent things:

   **(a) Memory redirect — ALWAYS, regardless of backend.** Point
   `autoMemoryDirectory` at the repo's **`memory` worktree** — `<repo>.d/memory` — an
   ORPHAN branch holding nothing but the knowledge base. NOT this worktree, and not the
   `main` checkout. The path **must be absolute** (`~/` also allowed; no relative /
   `${workspaceFolder}`), which is what makes it slug- and reader-independent (the old
   `link-memory.sh` symlink was neither, and broke on non-main worktrees).

   ⚠️ **It used to point at `<worktree>/.claude/memory`, on the reasoning that being
   git-tracked it would "ride into the branch at merge". Measured 2026-09-29: it does
   not ride, it FORKS.** Two trees had diverged from one commit — 248 files in a branch
   worktree against 325 in the `main` checkout, 175 identical — and a dead-`[[link]]`
   audit run over one of them reported **7 false deaths**, recording live facts as lost.
   Reconciling them took a 3-way merge, because neither side was uniformly ahead.
   Per-worktree memory does not prevent forking; it *guarantees* it, and the merge that
   was supposed to heal it is the step nobody performs.

   ★ So the knowledge base is **one orphan branch with one worktree**, the convention
   this repo already uses twice — `flox-catalogue` (109 commits) and `seed-incluster`
   (46), both orphans with no common ancestor. Forking stops being discouraged and
   becomes impossible: no code branch carries `.claude/memory/` at all. Preferred over
   the dedicated `claude-memory` repo that `MEMORY-STRUCTURE-SPEC.md` step 1 proposed —
   same isolation, no new repo, no subtree machinery, no cross-repo sync.

   Two consequences to respect:

   - **Something must COMMIT it**, because the session that writes there is not the one
     that commits from there. That is what left 32 memory files dirty in a `main`
     checkout for 11 days (oldest 2026-09-18). The session-end hook
     (`.claude/bin/memory-commit.sh`) does it; the `SessionStart` guard shouts if the
     worktree is dirty or the configured directory is not the memory worktree.
   - **A running session cannot be redirected.** The path is resolved once, into the
     system prompt; editing this file mid-session changes nothing until the next one
     (measured: corrected 09-27 22:44, writes still landing in the old directory
     09-28 12:48). So verify at session START, not after.

   **(b) The Claude backend.** Ask: **AWS Bedrock** (`ai-tools-shared`) or the
   **enterprise Anthropic** account (direct)?

   - **Bedrock**:
     ```json
     {
       "model": "opus[1m]",
       "autoMemoryDirectory": "<repo>.d/memory",
       "env": {
         "CLAUDE_CODE_USE_BEDROCK": "1",
         "AWS_PROFILE": "ai-tools-shared",
         "AWS_REGION": "us-east-1",
         "ANTHROPIC_DEFAULT_OPUS_MODEL": "us.anthropic.claude-opus-5",
         "ANTHROPIC_DEFAULT_SONNET_MODEL": "us.anthropic.claude-sonnet-4-6",
         "ANTHROPIC_DEFAULT_HAIKU_MODEL": "us.anthropic.claude-haiku-4-5-20251001-v1:0"
       }
     }
     ```
     Needs `aws sso login --profile ai-tools-shared`. The `env` block is read at
     session start, so it takes effect in the new window's first session. The
     model ids are cross-region **Bedrock inference profiles** (`us.anthropic.…`),
     account-specific — those above are for `ai-tools-shared`; `opus[1m]` overrides
     the committed direct model id (which Bedrock would reject). Confirm the real
     profile ids with `aws bedrock list-inference-profiles` if the account differs.
   - **Enterprise Anthropic (direct)** → still write the file, just the memory
     redirect (the committed `.claude/settings.json` already selects the direct
     account):
     ```json
     { "autoMemoryDirectory": "<repo>.d/memory" }
     ```
6. **Generate the `.code-workspace`** as a sibling of the worktree, inside the
   namespace dir: `<repo>.d/<namespace>/<slug>.code-workspace`. Name the file with
   the bare leaf slug (the namespace is already carried by the parent dir). Set the
   folder `path` **relative** (just `<slug>`) and the folder `name` to
   `<namespace>/<slug>` so the VSCode tab stays distinct. Mirror the `settings`
   block (flox `JavaSE-25` runtime + `CLAUDE_CONFIG_DIR` pointing at this
   worktree's `.claude`) from an existing sibling `.code-workspace`. `CLAUDE_CONFIG_DIR`
   is an **absolute** path (the extension does not expand `${workspaceFolder}`), so it
   is host-specific — copy it from a sibling on the **same host**. These files
   live in the non-git `<repo>.d/` container — they are **not** committed.
7. **Open** that `.code-workspace` in a **new** VSCode window (one window = one
   worktree). Then `cd` into the worktree for any terminal work.

## Merge / land the branch (squash by default)

Squash is the default — one clean commit on the base. From the **base branch's**
worktree (never squash-merge into a branch you're standing on inside the topic
worktree):

```bash
git merge --squash <namespace>/<slug> && git commit    # on the base worktree
```

**⚠️ Subtree guard — applies ONLY if the branch touched `.claude/hub/`.** A squash
flattens the subtree sync commits (`Squashed '.claude/hub/' …`, the sync-down
merges) into one, orphaning `subtree pull` on the base (it loses the recorded
base → messy re-merge, or `could not rev-parse split hash`). Two safe ways:

- **Cheapest — don't squash *that* branch.** Plain `git merge` (or a fast-forward
  when the base is an ancestor) carries the subtree markers along intact. Only
  hub-touching branches need this; everything else squashes freely.
- **Keep squash-by-default — re-anchor after.** First **sync the branch's hub edits
  up** via the `hub-subtree-sync` skill (so the split reflects them), *then* on the
  base:
  ```bash
  git rm -r .claude/hub && git commit -m "chore: re-anchor hub subtree"
  git subtree add --prefix=.claude/hub claude-hub split/<hub-org>/dot-claude --squash
  ```
  This re-establishes a clean sync base at the current (synced) hub content. The
  sync-up **must** precede the re-add, or the re-add overwrites your hub edits with
  the stale split.

Push the base, then tear down the worktree below.

## Finish / tear down

A merged `<repo>.d/<namespace>/<slug>` worktree is **user-managed** under this
model — removing it is the normal, expected finish, not a provenance violation
(do not refuse on provenance grounds). Run from `<repo>.d/main`, never
cwd-inside the target:

1. Confirm you are **not** inside it, the branch is merged
   (`git branch --merged HEAD`), and the tree is clean — ignore expected churn:
   `.flox/env/manifest.lock`, sops re-smudge noise, and macOS AppleDouble `._*`
   files (`--force` is fine for those).
2. `git worktree remove [--force] <repo>.d/<namespace>/<slug>`
3. `git worktree prune`
4. `git branch -d <namespace>/<slug>` — use `-d` (refuses if unmerged).
5. **Remove the session bridge.** `git worktree remove` deletes the worktree's
   transcripts but NOT the home symlink `link-sessions.sh` created pointing at
   them, which now dangles. Remove it (slug = the worktree path with `/` and `.`
   → `-`):
   ```bash
   rm "$HOME/.claude/projects/$(printf '%s' <repo>.d/<namespace>/<slug> | sed 's:[/.]:-:g')"
   ```
6. **Remove the `.code-workspace`** sibling
   (`<repo>.d/<namespace>/<slug>.code-workspace`) and close its VSCode window.
   Orphan `closed - …` sidebar labels live in VSCode's `state.vscdb`
   workspaceStorage and survive removal — purge there only if they bother you.

A `<repo>.d/<namespace>/` parent that keeps a `.flox.d` symlink after removal is
expected scaffolding for the next worktree placed there — leave it.

## Hub-subtree sync (only if this session edited `.claude/hub/…`)

The `.claude/hub/` tree is a shared subtree of `claude-hub`. If you changed hub
content, publish it **up** before landing the consumer branch — via the
**`hub-subtree-sync` skill**, which owns the hardened procedure. Two invariants
that bite if ignored:

- **NEVER delete the split branches** (`split/<org>/dot-claude*`). `subtree pull`
  walks their recorded `Squashed … from A..B` bases; deleting a split GC's those
  bases → `could not rev-parse split hash`. They are permanent anchors — keep them
  (and any `refs/recovered/*`) forever, **including when you delete the consumer
  branch or worktree** (teardown removes the worktree, never its split).
- **`--squash` both directions, `--ignore-joins` on split, NEVER `--rejoin`** (it
  fails "refusing to merge unrelated histories" after a squash pull).
