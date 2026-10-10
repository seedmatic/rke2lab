# SSH config & keys — shared git subtree

This folder is one of **two synchronized copies** of the same SSH configuration
and key bundle, shared between repositories as a `git subtree` (not a submodule,
not a flake input). Editing either copy and syncing through the integration
branch keeps both in step.

**`nix-darwin-home` owns the tree.** `modules/home-manager/ssh.d/` here is the
canonical source of the SSH config and keys; `rke2lab`'s `.ndh-ssh.d/` is a
synchronized consumer copy. Tree ownership is independent of the branch names
below.

| Role | Repository | Path |
| --- | --- | --- |
| Canonical source (owns the tree) | `nix-darwin-home` | `modules/home-manager/ssh.d/` |
| Consumer copy | `rke2lab` | `.ndh-ssh.d/` |
| Branch published by nix-darwin-home | `nix-darwin-home` (origin) | `split/nix-darwin-home/hm-ssh.d` |
| Branch published by rke2lab | `nix-darwin-home` (origin) | `split/rke2lab/hm-ssh.d` |

Each branch is a `subtree split` of the ssh.d tree — its root *is* the ssh.d
files, so it is a ref the two repositories sync through.

Integration branches follow the scheme **`split/{repo}/{tree}`**, where:

- **`{repo}` owns and publishes the branch** — it `subtree split`s the tree from
  its own copy and pushes the branch. The *other* repo **pulls** from it.
- This is symmetric: each repo publishes the branch named after itself, and
  consumes the branch named after its peer.

So there are two branches for `hm-ssh.d`:

- `split/nix-darwin-home/hm-ssh.d` — nix-darwin-home publishes it (sync **down**);
  rke2lab pulls from it.
- `split/rke2lab/hm-ssh.d` — rke2lab publishes it (sync **up**); nix-darwin-home
  pulls from it.

Both carry the same tree; they differ only in which repo refreshes each.
**`{repo}` is the branch's publisher, not the tree owner** — the tree is always
nix-darwin-home's.

## Why a subtree and not a flake input

rke2lab's `flake.nix` holds an INVARIANT: **nix-darwin-home must never be a flake
input of rke2lab** (the two repos depend on each other in opposite scopes; an
input edge would create a flake-eval cycle). The subtree shares this content over
plain git — no eval edge, no token, no cycle — which is exactly why it is the
right mechanism here. Treat the subtree as the *only* sanctioned channel for
ssh.d content to reach rke2lab.

## Sync down (nix-darwin-home → rke2lab)

After committing changes to `modules/home-manager/ssh.d` in nix-darwin-home:

```bash
# nix-darwin-home: refresh ITS OWN branch from the latest ssh.d
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/nix-darwin-home/hm-ssh.d --rejoin HEAD
git push origin split/nix-darwin-home/hm-ssh.d
```

```bash
# rke2lab: pull nix-darwin-home's branch into the subtree (squashed)
git fetch nix-darwin-home split/nix-darwin-home/hm-ssh.d
git subtree pull --prefix=.ndh-ssh.d \
  nix-darwin-home split/nix-darwin-home/hm-ssh.d --squash
```

## Sync up (rke2lab → nix-darwin-home)

After committing changes to `.ndh-ssh.d` in rke2lab, publish rke2lab's own
branch, then pull it into the source tree in nix-darwin-home:

```bash
# rke2lab: split .ndh-ssh.d onto ITS OWN branch and push it
git subtree split --prefix=.ndh-ssh.d \
  --branch=split/rke2lab/hm-ssh.d --rejoin HEAD
git push nix-darwin-home split/rke2lab/hm-ssh.d
```

```bash
# nix-darwin-home: pull rke2lab's branch into the source ssh.d tree (squashed),
# then refresh its own branch so the next "sync down" carries the change
git fetch origin split/rke2lab/hm-ssh.d
git subtree pull --prefix=modules/home-manager/ssh.d \
  origin split/rke2lab/hm-ssh.d --squash
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/nix-darwin-home/hm-ssh.d --rejoin HEAD
git push origin split/nix-darwin-home/hm-ssh.d
```

Always sync down before editing for an up-push, and keep `--squash` consistent on
both sides — mismatched squash settings make the synthetic histories diverge and
provoke avoidable merge conflicts.

## Initial setup (historical, already done in rke2lab)

```bash
git remote add nix-darwin-home https://github.com/nxmatic/nix-darwin-home.git
git fetch nix-darwin-home split/nix-darwin-home/hm-ssh.d
git subtree add --prefix=.ndh-ssh.d \
  nix-darwin-home split/nix-darwin-home/hm-ssh.d --squash
```

## Purpose

This subtree is the single source of truth for the SSH key definitions
(including `rke2-cluster`) used for:

- Flux SOPS age key derivation (`ssh-to-age` conversion)
- Consistent SSH key metadata across nix-darwin-home and rke2lab

`keys.yaml` carries metadata for every SSH key: key types and public keys, usage
annotations, certificate authorities, and profile associations.

## Rotating a TLS root authority (e.g. `mammoth-skate-tls`)

`keys.yaml` also holds X.509 root CAs under `authorities.<name>` (those with
`tls-authority` in their `usage`). Re-minting one — e.g. to change its
`basicConstraints` — is a `keys.yaml` content change that must propagate through
the subtree AND be re-consumed by anything that already derived material from it.

Re-mint reuses the **existing key** (the script reads `.private`, never
regenerates it), so intermediates and leaves signed under the root stay valid —
only the root cert itself changes:

```bash
# nix-darwin-home: re-sign the root (the app's template drops the
# pathLenConstraint — step's root-ca profile stamps pathlen:1, too tight for a
# 3-tier chain like RKE2's root -> intermediate-ca -> server-ca -> leaf).
# Run from the repo root (the app resolves keys.yaml via `git rev-parse`).
nix run .#authority-bootstrap-tls-root -- mammoth-skate-tls --force
sops -d modules/home-manager/ssh.d/keys.yaml \
  | yq -r '.authorities."mammoth-skate-tls".ca_crt' \
  | step certificate inspect - | grep -A1 'Basic Constraints'   # expect CA:TRUE, no pathlen
```

Commit `keys.yaml`, then **sync down** (see above) so every consumer copy (e.g.
`rke2lab`'s `.ndh-ssh.d/keys.yaml`) carries the new root.

**No SSH impact:** SSH host/user certs chain to the separate `mammoth-skate`
authority and are OpenSSH-format (no X.509 `pathLen`); rotating `mammoth-skate-tls`
touches only the TLS side.

> Consumers that *derive* material from a rotated root (e.g. rke2lab's cluster
> seal caches the derived cluster CA) must clear that cache and re-grow after the
> sync-down — a re-mint alone is not enough. That downstream procedure is
> consumer-specific and lives in the consumer's own runbook, not here.

## SOPS encryption

`keys.yaml` is SOPS-encrypted, with 3 age recipients — the `.sops.yaml` of the owning repository is
the source; recompute a host line with `ssh-to-age < <key>.pub` rather than trusting this list:

- `age1stxd...` — the **operator** key. Standalone: generated on its own, derived from nothing, present
  in no `keys.yaml`. A key that opens this file must not live inside it.
- `age1trxp...` — **bioskop's SSH host key** (`/etc/ssh/ssh_host_ed25519_key.pub` on that host), which
  is how sops-nix decrypts there at activation. Not an operator key.
- `age17q5k...` — **nikopol's SSH host key**, same role on that host.

CAUTION: `keys.yaml` holds the `mammoth-skate` and `mammoth-skate-tls` PRIVATE material, so this
recipient list is the list of identities that can read the SSH CA and the TLS root.

⛔ Until 2026-10-10 the list also held `age10ey0l...` (`github-signing-hyland@26-05-29`) and
`age1k0tc4...` (`rke2-cluster@26-05-29`), two keys of this very file whose privates were published.
Every version of `keys.yaml` committed before that date is readable from the public history.

The rke2-cluster age key (`age16ldt7qagykh3zt609689p69vcpugth6rhzpyyufztyws7llcp4asjpvp9j`, from
`rke2-cluster@26-10-10`) is:

- Derived from the SSH key: `ssh-to-age < ~/.local/share/ndh/ssh-keys/rke2-cluster.pub`
- A recipient of the cluster-side files in rke2lab's `.sops.yaml` only — what lets Flux in-cluster
  decrypt them. It is NOT a recipient of `keys.yaml`, which holds its private.

## Git filter

Each repository's `.gitattributes` configures the sops filter for `keys.yaml`
(the path differs per repo — `.ndh-ssh.d/keys.yaml` in rke2lab,
`modules/home-manager/ssh.d/keys.yaml` in nix-darwin-home):

```gitattributes
<path>/keys.yaml filter=sops-yaml
```

This means:

- **Working tree**: `keys.yaml` is auto-decrypted (plaintext)
- **Git index/commits**: `keys.yaml` is encrypted
- **Code reads**: plaintext (thanks to the git filter)
