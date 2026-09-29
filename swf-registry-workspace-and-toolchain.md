---
name: swf-registry-workspace-and-toolchain
description: "HylandSoftware/swf-registry (hy-reg) workspace layout, its flox toolchain, and how to activate it"
metadata:
  node_type: memory
  type: project
  originSessionId: ee54cec5-9d2e-4a28-a017-90430ec22251
  modified: 2026-09-27T13:47:17.133Z
---

`HylandSoftware/swf-registry` = **hy-reg**, a registry of *references* to coding-agent
configurations (skills/agents/rules/commands/hooks). Descriptor TOML + `registry.lock.json`
pins a commit; `rulesync 17` converts at install time into 7 harnesses' native layouts.
It sits **on top of** `HylandSoftware/hyland-ai-toolkit` — which is itself already a Claude
Code marketplace (`hyland-tools`, 6 plugins, incl. `comment-length` and `hyland`).

Set up 2026-09-27:

- bare repo `/private/var/lib/git/HylandSoftware/swf-registry.git` (config copied from
  `seedmatic/ndh.git`: `fetch = +refs/heads/*:refs/remotes/origin/*`, `tagOpt = --no-tags`)
- worktree `/private/var/lib/git/HylandSoftware/swf-registry.d/main`
- flox env at `swf-registry.d/.flox` — `python311` + `nodejs_22`, and `HY_REG_CACHE`
  exported from `on-activate` into `$FLOX_ENV_CACHE/hy-reg` so one content cache is shared
  by every worktree
- venv per worktree: `python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"`
  (`.venv/` is gitignored upstream; `.flox` and `.envrc` are **not** — never put either
  inside the worktree, it would leave untracked files in Hyland's repo)

**Invoke with `--dir`, never by cd'ing:**

```bash
flox activate --dir /private/var/lib/git/HylandSoftware/swf-registry.d -- \
  .venv/bin/hy-reg validate
```

**Why:** flox 1.14 does **not** search parent directories for `.flox` — activating from
`swf-registry.d/main` fails with *"Did not find an environment in the current directory"*.
The org-level envs (`HylandSoftware/.flox`, `HylandExperience/.flox`) were reached through
**direnv** (`use flox` + `source_up`), and direnv is **abandoned** — user's call, 2026-09-27.
So `--dir` is the mechanism, not a workaround.

**How to apply:** the env must exist explicitly at `swf-registry.d/`. Do not rely on the
org env one level up: `HylandSoftware/.flox` carries maven, Temurin 21 and `python3Full`
but **no Node**, so hy-reg would half-work then die on `error: 'npx' not found on PATH`.
Upstream pins neither runtime (no `.nvmrc`, no `.tool-versions`, no flake) — only
`requires-python = ">=3.11"` and the README's Node ≥ 22; our two pins are that record.

**Two hy-reg defects hit while populating the cache (2026-09-27), worth reporting when we
do contribute:**

1. Two descriptors name their source over **SSH** (`git@github.com:HylandSoftware/{hyland-ai-toolkit,swf-sdp-skills}.git`)
   — 29 of the 44 resources. We pull GitHub over https with a token and have no usable SSH
   key (`ssh -T git@github.com` → `Permission denied (publickey)`, no agent). Worked around
   with an env-scoped rewrite in the flox `on-activate`, so the user's global git config
   stays untouched: `GIT_CONFIG_COUNT=1`, `GIT_CONFIG_KEY_0=url.https://github.com/.insteadOf`,
   `GIT_CONFIG_VALUE_0=git@github.com:`.
2. `cache populate` **reports success on a failed fetch**: a stub source dir (28 KB) is
   enough for it to print `44 resource(s) cached (0 source(s) fetched)` while
   `hy-reg files <id>` answers `is not cached`. It never verifies the subpath, and
   `populate --id` will not re-fetch. Only `cache clear --yes` then `cache populate` breaks
   out. Discriminator: trust `hy-reg files <id> --offline`, never the populate counter.

**Read-only for now.** The clone tracks `HylandSoftware` directly because we only consume.
The day a change is needed, add a `nxmatic` fork as a second remote on the bare repo and
branch from there — we are **not** at the contribution stage (user, 2026-09-27), so do not
create the fork pre-emptively.

Toolchain fragments were **not** promoted into `seedmatic/fleet/flox`: that repo's `flox/`
is a git **subtree** (branch `flox-subtree`, `make flox-update`), is currently dirty
(`jdk`, `keyhole`, `nix` uncommitted) so `check-subtree-clean` would refuse, and the user
described it as "terrain mouvant". Revisit once it is stabilised.

See [[gh-token-env-shadows-sso-keyring]].
