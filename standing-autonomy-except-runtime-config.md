---
name: standing-autonomy-except-runtime-config
description: "The user granted BROAD standing autonomy — do everything WITHOUT asking — EXCEPT modifying configuration that touches the runtime (live system or harness). They are tired of click-to-authorize; over-using AskUserQuestion and stripping their permission grants are BOTH the friction they want gone. Born 2026-06-18 from explicit frustration."
metadata:
  node_type: memory
  type: feedback
---

**Standing grant (user, 2026-06-18, said with frustration): "je t'avais dit que tu pouvais tout faire
sauf modifier de la configuration qui touche au runtime, j'en avais marre de clicker pour t'autoriser,
mais tu t'es remis à me demander plein d'autorisations."**

So the default posture is BROAD AUTONOMY — act, don't ask — with ONE hard boundary.

**Act without asking:** edit code / poms / docs / memory, run builds + tests, `git` local ops
(`git mv`, commit on the branch I'm working in, reset/amend of my own unpushed commits), inspect
anything. Decisions I can make from the code, the conventions, or sensible defaults: just make them
and state what I did — do NOT spawn an AskUserQuestion modal for them.

**STOP and hand off (the boundary): configuration that touches the runtime.** Two senses, both gated:
- the LIVE system — `pulumi up`/`apply`, `kubectl apply`, `incus`, `nix switch`/rebuild, anything that
  mutates the provisioned cluster (already in CLAUDE.md: "operations that change the live system are
  run by the user");
- the HARNESS runtime config — `settings.json` hooks / model / process-wrapper, i.e. how the agent
  itself runs. (Editing project settings' permission ALLOWLIST to GRANT more is fine/desired; changing
  runtime behaviour is the gated part.)
- plus the integration merge of a sub-branch — the user's gesture per [[merge-from-target-worktree]].

**Two specific anti-frictions the user named:**
1. **AskUserQuestion is itself a "click to authorize."** Over-using it = the friction they want gone.
   Reserve it for genuine forks the user alone owns (a real product/architecture decision, the runtime
   boundary). NOT for scope calls I can take with a stated default (e.g. "widen to all modules?" — just
   widen if it's a uniform low-risk improvement, per [[every-module-has-a-description]], and say so).
2. **Do NOT strip their permission grants as "session noise."** When the harness accrues
   `permissions.allow` / `additionalDirectories` entries, those are what spares them clicks. Prune only
   genuinely dead absolute paths (e.g. into a torn-down worktree) and KEEP the durable ones; when
   unsure, keep. Better still: avoid the cross-worktree reads that spawn per-path prompts by operating
   from within the relevant worktree.

**Why it kept happening this session:** working from the `design` worktree while inspecting the
`refactor/osgi-space-bundles` worktree made every cross-dir Read trigger a permission prompt → the
harness recorded absolute-path grants → I then "cleaned" them, undoing the click-saving. Operate inside
the worktree under work, and trust the standing grant.

See [[merge-from-target-worktree]] (the merge is the user's), [[rke2lab-solo-no-pr-merge-direct]],
[[hub:standing-approval-subagent-execution]].
