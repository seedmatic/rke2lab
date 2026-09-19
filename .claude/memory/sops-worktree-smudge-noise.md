---
name: sops-worktree-smudge-noise
description: "rke2lab gotcha: git worktree add leaves sops-governed files (.secrets, .ndh-ssh.d/keys.yaml, **/01-secret-*.yaml) ENCRYPTED on disk — the smudge filter runs before .sops.yaml lands in the new dir, so it aborts. NOT harmless: the worktree's secrets are unusable until re-smudged. FIX (proven): after creation, `rm <sops files> && git checkout -- <sops files>` re-decrypts them (ENC[ count → 0). .sops.yaml is tracked+committed so it IS already present — a symlink is NOT the fix; the ORDER is."
metadata: 
  node_type: memory
  type: project
  originSessionId: db60e59b-0ed2-439c-b0ac-8f559df91cbf
---

When you run `git worktree add` in **rke2lab**, the global sops git filters
(defined in `~/.config/git/config` → `sops.d/{yaml,binary,json}` includes) fail
during the initial checkout:

```
You do not have configured sops for that repository. You're missing .../.sops.yaml.
error: external filter '.../yaml-smudge %f' failed
sops clean filter: .ndh-ssh.d/keys.yaml already contains sops metadata — refusing to stage.
error: external filter '.../yaml-clean %f' failed
```

**ROOT CAUSE — an ordering bug, not a missing file.** `.sops.yaml` is *tracked
and committed*, so it lands in every worktree. But during `git worktree add`,
git applies the smudge filter to the secret files **before** `.sops.yaml` is
written into the new directory. The filter can't find its config → it aborts →
the secret files stay **ENCRYPTED** on disk.

**THIS IS NOT HARMLESS.** Verified on 2026-06-14: in a fresh worktree,
`.secrets` had 13 `ENC[` markers and `.ndh-ssh.d/keys.yaml` had 9, while the
main checkout had 0 (decrypted). Any build/run that reads sops secrets in that
worktree gets ciphertext.

sops-governed paths (from `.gitattributes`, `filter=sops-yaml`):
`.secrets`, `.ndh-ssh.d/keys.yaml`, `**/01-secret-*.yaml`.

**THE FIX (proven to work once `.sops.yaml` is present):** re-smudge after
creation —

```bash
cd <worktree>
rm .secrets .ndh-ssh.d/keys.yaml
git checkout -- .secrets .ndh-ssh.d/keys.yaml   # filter now finds .sops.yaml → decrypts
```

After this, `ENC[` count → 0 and `git status --porcelain` is empty (clean).
(Extend the file list if `**/01-secret-*.yaml` instances exist in the tree.)

**A SYMLINK of `.sops.yaml` is NOT the fix** — the file is already there; the
problem is the smudge timing. The durable fix is a post-creation re-smudge step;
captured for design in the worktree backlog → see [[pulumi-stack-per-worktree-backlog]].

The filters also leave macOS **AppleDouble** files (`._*`, e.g. `._.local.d`)
as untracked noise → `git worktree remove` refuses with "contains modified or
untracked files"; safe to `--force` once the commit is reachable on origin.

Relates to the [[hub:worktree-per-conversation]] rule — every per-conversation
worktree in rke2lab surfaces this until the re-smudge is automated.
