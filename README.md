# `workspace` — the single source of truth for how the DAG resolves locally

An **orphan branch**, like `memory`, `flox-catalogue` and `seed-incluster`: it shares no history
with the code branches because it carries a different kind of content. Its worktree lives beside
them at `<store>/seedmatic/rke2lab.d/workspace` and is listed in the `.code-workspace`.

## What it carries

1. **The SSOT** — one YAML declaration of the DAG's worktrees, in **relative** form: which
   repository, which checkout, where it sits relative to the others. Every input of the seedmatic
   DAG (`rke2lab`, `ndh`, `nnh`, `nix-flake-commons`, `flox-nri-plugin`, `flox-controller`, `fleet`)
   appears once, and only here.
2. **The devpod specifications** — what a devpod is made of, derived from that same declaration
   rather than restated.

### Documentation

- [`docs/workspace-manifest-spec.adoc`](docs/workspace-manifest-spec.adoc) — the
  specification: what the manifest declares, what it deliberately does not, the reference
  invariant, the two outputs, and the assertions.
- [`docs/atlas.adoc`](docs/atlas.adoc) — the atlas: the measured state, the target, and the
  delta between them, with who closes each part.

`docs/` is intentionally flat. Everything on this branch is one subject, so a per-area tree
would separate nothing; a new subject — the devpod specifications below — is one more file.

### One source, two generated outputs

The same list answers two questions that were previously maintained by hand and could drift apart:

| Output | Consumer | Why it cannot be the source |
| --- | --- | --- |
| `registry.json` | **nix** — resolves `flake:<id>` to a local worktree | a registry rejects relative paths, so it must hold absolute ones |
| `develop.code-workspace` | **the editor** — which folders the window shows | its paths are relative *to its own location*, which is one level above the worktrees |

Both are therefore **artifacts**, generated from the relative YAML at activation and never
committed. The payoff is that a workspace stops being something each seat recreates by hand: the
same declaration reproduces it on either Darwin seat and inside a devpod.

⚠️ The editor file keeps its current location and name. It is **not** moved into this branch, for
two measured reasons: every relative path would gain a `../`, and `config-home-guard.sh` looks for
it at `$(dirname $root)/$(basename $root).code-workspace` — since a check it cannot perform now
counts as a failure, moving the file would make the guard shout at every session start.

★ Its **first folder must stay the seat's own worktree**: the editor extension launches with that
folder as cwd, and the config home is derived from it. A generator that reorders the list silently
breaks session history. That invariant belongs in the generator, not in a comment.

## Why it exists: locks pinned revisions nobody chose

Measured on 2026-10-02. The dev toolchain (`jdk25`, `maven`, `shfmt`, `shellcheck`) was locked to
an rke2lab revision from **August 27 — 612 commits behind**, and three other refs tracked a branch
that had already been retired. Nothing failed: the dev loop simply used binaries built from a dead
line. A pin that names a branch **rots silently**, and there is no command-line override for a flox
flake ref — the manifest is the only lever.

## How it resolves — and why the SSOT is YAML while the registry is JSON

Nix resolves *indirect* flake references (`flake:ndh`) through a **registry**, so the DAG's inputs
move from `github:owner/repo/branch` to `flake:<id>`. Three measured constraints shape the rest:

| Constraint | Consequence |
| --- | --- |
| a registry does **not** redirect an explicit `github:` URL | inputs **must** become indirect refs; this is not a style choice |
| a registry **rejects relative paths** (`cannot fetch input 'path:../a' because it uses a relative path`) | the registry must hold **absolute** paths |
| `NIX_CONFIG="flake-registry = <path>"` works | no flag has to be repeated on every command |

So the two formats have different jobs. The **YAML is the edited truth** and stays relative — no
machine, no absolute path, and it can carry comments, which is exactly what the silently rotting
pins lacked. The **`registry.json` is a generated artifact**: absolute, gitignored, produced at
activation from `${FLOX_ENV_PROJECT}`. Nothing absolute is ever committed, so the same declaration
works on either seat and inside a devpod, whatever the workspace root happens to be.

`yq -o=json` performs the conversion; `yq-go` already ships in the seat's flox environment.

## The trade-off, stated on purpose

Once inputs are indirect, **the flake.nix files stop being self-sufficient outside this
workspace** — an external consumer (CI, a third party) must supply a registry. That cost is
accepted here; it must be documented wherever an input becomes indirect, or the next reader will
take it for a bug.

## Related

- Relative *path inputs* (`inputs.x.url = "../x"`) are the supported form **within one git tree**,
  and their lock entry stays relative. They do **not** work across separate repositories: nix
  copies the parent flake into the store, so `../ndh` becomes `/nix/store/ndh`.
- `git+file:` with a relative path is **deprecated** upstream (NixOS/nix#12281) — do not build on it.
- `git+file:` to a *worktree* does work and nix reads the checked-out branch by itself, but the
  path it records is absolute.
