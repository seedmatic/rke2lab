---
name: worktree-provisioning-handoff
description: "Three worktree-provisioning gaps discovered running pulumi preview in the R4 feature worktree; the worktree generator (base workspace) should close them at handoff — incl. a branch-named Pulumi stack"
metadata:
  node_type: memory
  type: project
---

Running `pulumi preview` from the R4 **feature** worktree (the first real exercise of the embedded-OSGi
exec-jar outside `main`) surfaced THREE provisioning gaps. None are R4 code defects — they are holes in
**how we provision a working worktree**. We discover them here (experimental worktree); at **handoff** the
**base workspace / worktree generator** should close them so the next worktree starts clean. (Per
[[merge-from-target-worktree]]: the sub-branch session finishes + transmits; it does not mutate the
generator from inside its own worktree.)

**The three gaps (each blocked the preview in turn, fixed manually here, must be automated):**

1. **`rke2lab:worktree:dir` is hard-coded to the `main` checkout** (`/private/var/lib/git/nxmatic/rke2lab`)
   in `Pulumi.dev.yaml`. It is a *mandatory* config read by `BootstrapConfig` (the bbox secrets reader +
   others resolve files under it). In a feature worktree it must point at THIS worktree, else bbox reads
   `.secrets` from the wrong tree. Manual fix used: edit the nested key — but `Pulumi.dev.yaml` is COMMITTED,
   so the edit must NOT ship. Right place = a per-worktree stack (see gap 3), not an edit to `dev`.

2. **`.secrets` lands ENCRYPTED in a fresh worktree** — the [[sops-worktree-smudge-noise]] gotcha: `git
   worktree add` checks files out before `.sops.yaml` is visible to the smudge filter. The bbox reader fails
   with "Cannot read secrets file … is the sops-yaml smudge filter active?". Fix: `rm .secrets && git
   checkout -- .secrets` (re-smudge); verify 0 `ENC[` remain. The generator already owes this re-smudge
   step for ALL sops files (memory says so) — bind it into worktree creation, don't leave it manual.

3. **Pulumi backend is per-worktree and EMPTY** ([[pulumi-stack-per-worktree-backlog]]): flox sets
   `PULUMI_BACKEND_URL=file://<worktree>/.pulumi-state`, so a fresh worktree has zero stacks. Manual fix:
   `pulumi stack init dev` then point config at the worktree. **Better idea (user, 2026-06-20): name the
   stack after the BRANCH** — clearer than a shared-looking `dev`/`local`. Stack names can't contain `/`,
   so use the branch **leaf slug** (`osgi-runtime-r4-boot-seam`, not `feature/...`). Generator gesture:
   `pulumi stack init <branch-leaf>` + set `rke2lab:worktree:dir` to the worktree in `Pulumi.<branch-leaf>.yaml`
   (gitignored — add the pattern; `.gitignore` currently does NOT ignore `Pulumi.*.yaml`). To get a realistic
   diff (update/no-change instead of N creates), seed it from main: `cd .../main && pulumi stack export
   --stack dev > /tmp/s.json` then `pulumi stack import --stack <branch-leaf> --file /tmp/s.json` — state
   transfers, but NOT config/classpath resources, so it does not substitute for gaps 1-2.

**Handoff payload:** fold the above into the worktree-creation recipe in
[[hub:external-worktree-operating-model-state]] / the `.code-workspace` generator alongside the known
memory-slug + jdtls-heap defects ([[claude-memory-cascade-state]] [[jdtls-heap-workspace-generation]]).

See [[pulumi-stack-per-worktree-backlog]] [[sops-worktree-smudge-noise]] [[osgi-runtime-r4-resume-state]]
[[merge-from-target-worktree]].
