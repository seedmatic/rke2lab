---
name: pulumi-stack-per-worktree-backlog
description: "BACKLOG / brainstorm-later: how to manage Pulumi stacks per git worktree, since the flox backend URL is project-relative so every worktree gets an empty state."
metadata: 
  node_type: memory
  type: project
  originSessionId: 321da8b5-a82b-4af2-8f71-460186de7864
---

**★ BACKLOG, surfaced 2026-06-14 — brainstorm BEFORE building.** Decide how a worktree should get a usable Pulumi stack.

**Root mechanism:** the flox manifest sets `PULUMI_BACKEND_URL='file://${FLOX_ENV_PROJECT}/.pulumi-state'` (`.flox/env/manifest.toml:56`), and `FLOX_ENV_PROJECT` = the activated project root = the worktree dir. So:
- Main checkout → `/rke2lab/.pulumi-state/` → holds the REAL `dev` stack (+ `noexport-test`, `sandbox-selfread`, history, backups).
- Any `.claude/worktrees/<x>/` → its own `.pulumi-state/`, created empty by the `on-activate` `mkdir -p`. `pulumi stack ls` there = empty.

**The tension (this is the crux):** user asked "shouldn't a worktree import the dev stack at init?" — but a naive import RE-CREATES the cross-session sharing that the [[hub:worktree-per-conversation]] rule just eliminated. Two worktrees each holding a `dev` that points at the SAME real master = divergent states + colliding `pulumi up` on one infra. Must split two needs:
- **Mutate real dev infra** → stays at the MAIN checkout only (single source of truth for dev state). A worktree should NOT `up` dev.
- **Preview/test the worktree's code against realistic data** → either a throwaway stack (proven safe this session: isolated worktree-local backend, `pulumi preview` exercised the full runtime path) OR a READ-ONLY frozen snapshot of dev (export→import a copy, never written back to the real master).

Rule of thumb: **copy state to READ it, never to re-write it to the same infra.**

**Options on the table:** (a) **PROVEN 2026-06-22 — the working recipe:** from MAIN `pulumi stack export --stack dev --file /tmp/snap.json`; in the worktree `pulumi stack init <distinct-name>` (e.g. `dev-preview-staging`, NEVER reuse `dev`) + `pulumi stack import --file /tmp/snap.json`; then `pulumi preview` ONLY, never `up`. The distinct stack name is the safety: impossible to confuse and `up` the real dev. seed-master ran and `pulumi preview` was happy WITH the osgi-staging-extension active — an end-to-end proof the derived-bundle staging doesn't break the real runtime path, not just the tests. (b) point worktree `PULUMI_BACKEND_URL` at the main checkout's `.pulumi-state` (simple but reintroduces shared lock — risky); (c) status quo: empty state, throwaway stack for previews, real dev work at main checkout. Also in scope when brainstorming: stack lock, divergence, secrets/passphrase (`PULUMI_CONFIG_PASSPHRASE` empty in manifest; dev has `encryptionsalt`), and the nix deploy path (`nix build .#seed-master`).

## ★★★ CORRECTION 2026-10-02 — the anchor "real dev lives at MAIN" is STALE, and the rule was dropped

Measured across all rke2lab worktrees. Four hold a stack literally named `dev`, and **authority has
moved off `main`**:

| worktree | `dev` resources | last write |
|---|---|---|
| `main` | 65 | 2026-06-20 — **stale by 3.5 months** |
| `design/pre-integration` | 0 | 2026-06-22 — empty husk |
| `feature/cluster-seed-scenario` | 15 (+ a `dev-preview-cluster-seed`) | 2026-07-23 — stale |
| ★ `feature/viewpoint-separation` | 58 | **2026-10-01 21:09 — the LIVE one** |

So option (c) above ("real dev work at main checkout") no longer describes reality, and the (a)
safety rule — `pulumi stack init <distinct-name>`, **NEVER reuse `dev`** — was applied once in
`cluster-seed-scenario` and then abandoned: that worktree kept `dev-preview-cluster-seed` *and*
acquired a `dev`.

**The intent of the rule still holds — ONE `dev` per infra. Only its anchor was wrong.** Don't
re-derive "therefore work at main": `main`'s state is the stale one, so treating it as the source of
truth would roll the infra back 3.5 months.

**Also measured:** the state embeds the **absolute worktree path** in resource *inputs*
(`.local.d/rootfs.squashfs`, `.local.d/incus.tar.xz`, `.local.d/render/bioskop-mgmt-master`, and the
worktree root). Two consequences for relocating a worktree:

* a plain `cp -a .pulumi-state` is NOT enough — inputs keep the old path, so the next `preview` sees
  a diff and wants to **replace** the image resources;
* ⚠️ "recreate the stack, then `refresh`" does **not** reconcile anything. A fresh stack's state is
  **empty**, and `refresh` does not *discover* resources — it only refreshes what the state already
  holds. On an empty state it finds nothing, and the next `up` tries to **create** what already
  exists. `refresh` is the drift tool, not the relocation tool.

Relocation recipe that works: `pulumi stack export` → rewrite the path prefix with `sed` →
`pulumi stack import` into the new worktree's backend → `pulumi preview` must show **no changes**.
Backend selection needs nothing: `PULUMI_BACKEND_URL='file://${FLOX_ENV_PROJECT}/.pulumi-state'`
follows the worktree, and `PULUMI_CONFIG_PASSPHRASE=''` means no key travels. Context:
[[etage0-bare-worktree-migration]].

**A SECOND worktree-init concern joins this brainstorm (2026-06-14): sops re-smudge.** Same shape — "what does a fresh worktree need to be usable?" `git worktree add` leaves the sops-governed files (`.secrets`, `.ndh-ssh.d/keys.yaml`, `**/01-secret-*.yaml`) ENCRYPTED, because the smudge filter runs before `.sops.yaml` lands in the new dir (ordering bug, NOT a missing file — `.sops.yaml` is tracked so it IS there; a symlink is the wrong fix). Proven fix: post-creation re-smudge — `rm <sops files> && git checkout -- <sops files>` (ENC[ count → 0). Full diagnosis in [[sops-worktree-smudge-noise]]. So the worktree-init story has TWO automation candidates that want ONE hook: (1) provision a usable Pulumi stack, (2) re-smudge sops secrets. Likely landing spot = a worktree-creation hook (or the flox `on-activate`, which already runs the `mkdir -p .pulumi-state`). Brainstorm them together.

See [[hub:worktree-per-conversation]] (the isolation rule this must not violate), [[sops-worktree-smudge-noise]] (the second init concern), and [[preview-whatif-topic]] (self-referential state read patterns already explored).
