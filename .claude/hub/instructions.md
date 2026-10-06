# User preferences

## Language

The user is a French citizen; English is not their first language and they describe their spoken English as still developing. They write to me in English regardless.

When the user's phrasing is unclear, ambiguous, or grammatically off in a way that could change the meaning, restate it back more clearly before acting on it — both to confirm I understood the intent and to help them improve their English. Keep the rephrasing brief and natural; do not lecture, over-correct, or flag every minor slip. The goal is mutual clarity and gentle language help, not a grammar audit.

When the intent is already clear, just proceed — no need to rephrase.

## Progress narration

The harness spinner shows vague decorative words ("elucidating", "musing", "effecting", and similar) that I neither choose nor control and that say nothing about the actual task. They stress me: I can't tell from them whether you are working or stuck. So the prose you emit is the only real signal, and it must always carry it:

- Before each tool batch, state in one line what you are about to do and why.
- On multi-step work, give progress as you go ("3/5 done, now the 4th"); keep the todo list live.
- Never chain several tool calls in silence — a silent gap with only the spinner showing is exactly what stresses me.
- Do not rely on the spinner words to convey progress; narrate in real prose instead.
- If you are genuinely stuck, say so and ask — don't deliberate silently.

## Code hygiene

Never leave dead code behind. When a change supersedes an old path — a function, a code branch, a delivery mechanism, a config knob — delete the old one entirely and update all call sites in the same change. Do not defer removal to "a later pass," do not leave it as "dead weight, not breakage," and do not keep it for backwards-compatibility unless explicitly asked. If a refactor makes something unused, that unused thing is part of the same refactor's scope.

## Workspace isolation — the shared stack worktree

Work happens in a **shared development worktree per repo**, where the branches of a stack are navigated from one checkout. This supersedes the older rule that each conversation needed its own worktree: a stack of dependent branches is navigated from one checkout, not N copies. A chantier that spans several repos gets **the same branch name in each repo it touches** — parallel stacks, aligned by name, whose depths differ per repo.

Isolation therefore moved from topology to **discipline**: two checkouts *could not* mix, one *can*. So the write lock is explicit and centrally assigned — a session **receives** the lead, it does not take it, and the default is "I do not write".

- **One session writes at a time**, in a given worktree. Reading takes nothing.
- **One branch per session**, so commits stay attributable despite the single checkout.
- **A session does not write until the integration session has assigned it a branch AND given it the lead.** Both, not either.
- **A branch switch IS a lead transfer** — announce it, because the checkout is shared, so changing branch changes what another session would be writing onto.
- **The lead is released explicitly.** Silence is not release. The session that moves a layer **re-stacks what is above it** before releasing — it already holds the checkout and is the only one who knows what moved.

Who owns what: the **integration session owns the topology** — creating and assigning branches in every repo of a chantier, keeping the bases right, the stack tracking, granting the lead, and rebasing the stack when the trunk moves. The **working sessions own the position** — switching their worktrees while they hold the lead, and **checking the branch immediately before committing**. That last check cannot be centralised: the commits that once landed on the wrong branch were *correct*, it was their **landing** that was wrong, and the coordinator is not present at that instant.

Three gestures that are not optional in a shared worktree:

- **`git add <paths>` explicitly — never `-A` or `.`** It is what has saved every collision so far, in both directions.
- **Check the branch before committing** (`git rev-parse --abbrev-ref HEAD`) — a commit lands on the checkout's branch, not the one you have in mind.
- **Announce** taking the lead, releasing it, and every branch switch.

Three orderings that **no stack tool expresses**, so they live in announcements: a **base must be pushed before the stack layered on it**, a **trunk before a layer sitting on it** (otherwise pushing the layer publishes trunk commits as a side effect), and **dependencies between parallel stacks** — a repo whose change another repo consumes as an input must be pushed first.

⚠️ The third one's failure mode is **silent staleness, not breakage**, and that is why it needs announcing rather than checking: a `github:` fetch *physically cannot* see an unpushed commit, so a consumer that re-locks too early simply pins the older **pushed** revision, with no error anywhere. The chain is push-gated by construction — which also means there is nothing useful to assert about reachability; the lock can never name something unfetchable. Measured: re-locking a consumer's input resolved the pushed head while the local checkout sat two commits ahead.

⛔ **Memory is the structural exception.** The memory worktree is shared by every session and its index files are edited jointly, so they cannot be cut along authorship — per-session branches there would fragment the one thing whose sharing is the point. Rule: each session commits its **explicit paths**; the indexes stay shared.

When driving stacks with `gh stack`, its writing commands (`init`, `link`, `unstack`, `rebase`) belong to the integration session; the working sessions stay on `view --json` and navigation. It has default opinions that do not match this topology and does not announce them — always pass `--base develop`, and never run a writing command without reading `gh stack view --json` first. The traps live in project memory.

**Worktrees are EXTERNAL, NOT under `.claude/worktrees/`.** Every checkout lives at `<repo>.d/<namespace>/<branch>` — a sibling of `main` — so one VSCode window = indexed code + chat, and flox `[include]` manifest-relative paths resolve (they do NOT from inside `.claude/worktrees/…`). **Do NOT use the `EnterWorktree` harness tool** (it hard-codes `.claude/worktrees/<branch>`, which this model rejects). Treat `main` as read-only reference — never `git checkout <other-branch>` in it, another session may be living there.

When a task is about to **mutate** files, create a dedicated external worktree first; pure read-only investigation stays in the current checkout. The full **create + teardown recipe** — source branch (defaults to the *current* branch), `git worktree add`, sops re-smudge, the session bridge, `autoMemoryDirectory` + Claude-backend settings, `.code-workspace` generation, cleanup, and hub-subtree sync — lives in the **`worktree` skill** (it auto-loads for this kind of task, or invoke `/worktree`). Per-repo gotchas (flox `[include]`, sops re-smudge, Pulumi-state-per-worktree) live in project memory.

## Context window management

When the conversation context grows beyond 150,000 tokens, warn me proactively. Say something brief like "Context approaching limit (150k+ tokens used) — consider summarizing or starting fresh if we shift topics." This prevents work loss from hitting the hard limit unexpectedly.
