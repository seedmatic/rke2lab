---
name: nix-repo-setup
description: >-
  Wire the standard formatting + commit-gating setup for a repo that ships a Nix
  flake (a "nix-closure repo") in this ecosystem. Use when adding treefmt/`nix
  fmt` to a repo, when a repo's commits are NOT format-gated, when asked to "wire
  the same hook", "set up formatting", "add a formatter", "câbler le hook", or
  when scaffolding/aligning a new nix repo. Encodes the worktree-safe `treefmt.nix`,
  the flake `formatter` output, the repo-local `.githooks/pre-commit` that the
  global `core.hooksPath` dispatcher chains to, the sops `.gitattributes` filters
  (composes with the worktree skill), the Java=spotless split, and the `path:`
  nix-build gotcha under relative worktrees.
---

# Nix-closure repo setup

The repeatable setup a repo needs to sit correctly in this ecosystem: consistent
formatting, commit-time gating, and worktree/sops compatibility. Apply it when a
repo ships a flake but its `nix`/`shell` files aren't format-gated (symptom: a
commit in repo A runs `Running nix fmt -- --fail-on-change …` but repo B commits
with no such check).

## How the gate actually fires (don't re-invent it)

`core.hooksPath` is set **globally** to `~/.config/git/hooks`. That global
`pre-commit` is a DISPATCHER: it runs the wip-guard (blocks `wip/.wip` paths on
protected branches, catching squash-merges too) and then **chains to the repo's
own `.githooks/pre-commit`** (`chain_repo_local`). So a repo only gets a nix/shell
format gate if it ships BOTH:

1. a **`formatter`** flake output (so `nix fmt` has something to run), and
2. a repo-local **`.githooks/pre-commit`** for the dispatcher to chain into.

A repo missing either is silently ungated — the dispatcher chains to nothing.

## The pieces

### 1. `treefmt.nix` (worktree-safe)

```nix
{
  pkgs,
  # Worktree-safe root marker: in split bare/worktree layouts `.git/config`
  # does not exist in the worktree because `.git` is a file. Use flake.nix.
  projectRootFile ? "flake.nix",
}:
{
  inherit projectRootFile;
  programs.nixfmt.enable = pkgs.lib.meta.availableOn pkgs.stdenv.buildPlatform pkgs.nixfmt-rfc-style.compiler;
  programs.nixfmt.package = pkgs.nixfmt-rfc-style;
  programs.shellcheck.enable = true;
  settings.walk = "git";                       # only walk git-tracked files
  settings.formatter.shellcheck.options = [ "-s" "bash" "-S" "error" ];
  # Optional: JSON-Schema validation of a data file (non-rewriting; blocks on invalid):
  #   settings.formatter.<name> = { command = "${pkgs.check-jsonschema}/bin/check-jsonschema";
  #     options = [ "--schemafile" "path/to.schema.yaml" ]; includes = [ "path/to.yaml" ]; };
}
```

`settings.walk = "git"` + `projectRootFile = "flake.nix"` are the two lines that
make it behave in an external worktree (where `.git` is a file, not a dir).

### 2. flake `formatter` output

Add the input and wire the wrapper:

```nix
# inputs:
treefmt-nix.url = "github:numtide/treefmt-nix";

# outputs (per-system):
formatter = forAllSystems (system:
  let pkgs = nixpkgs.legacyPackages.${system};
  in inputs.treefmt-nix.lib.mkWrapper pkgs (import ./treefmt.nix { inherit pkgs; }));
```

### 3. repo-local `.githooks/pre-commit`

Commit this executable script at `<repo>/.githooks/pre-commit` (`chmod +x`). The
global dispatcher chains to it; it is the piece that actually runs the gate:

```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! command -v nix >/dev/null 2>&1; then
  echo "nix not found; skipping nix fmt check" >&2
  exit 0
fi

echo "Running nix fmt -- --fail-on-change ..."
if ! nix fmt -- --fail-on-change; then
  cat <<'EOF'
nix fmt -- --fail-on-change failed. Please run `nix fmt` to apply formatting.
EOF
  exit 1
fi
```

Note: the global `core.hooksPath` means git does NOT read `.git/hooks` or
`.githooks` automatically — the dispatcher's `chain_repo_local` is what invokes
`.githooks/pre-commit`. Just committing the file is enough; no `git config` change.

### 4. sops `.gitattributes` (if the repo has secrets)

Governed paths get `filter=sops-yaml` in `.gitattributes`. This composes with the
**worktree** skill, whose create step re-smudges every `filter=sops-*` file after
`git worktree add` (it scans `.gitattributes` via `git check-attr`). A repo that
DISABLES the filters (worktree must stay encrypted for eval-time `readFile`, e.g.
`ndh`) documents that in `.gitattributes` and carries no `filter=sops-*` line — the
re-smudge is then a correct no-op.

### 5. Java repos — treefmt covers nix/shell only

A Java repo (e.g. `rke2lab`) gates Java at BUILD time via **spotless +
google-java-format** (`build-parent/pom.xml`), not pre-commit. treefmt/`nix fmt`
here formats the `nix`/`shell` surface; leave Java to spotless. Don't add a Java
formatter to treefmt — it would fight spotless.

## Apply (checklist)

1. Add `treefmt-nix` input + the `formatter` output to `flake.nix`.
2. Write `treefmt.nix` (above); enable only the languages the repo actually has.
3. Add executable `.githooks/pre-commit` (above).
4. `nix fmt` once to normalize the tree; commit the (possibly large) reformat
   SEPARATELY from feature work so the diff stays reviewable.
5. Verify: `nix fmt -- --fail-on-change` exits 0 on a clean tree; a deliberately
   mis-indented file makes it exit 1; a real commit shows `Running nix fmt …`.

## Gotchas

- **`path:` vs the git fetcher.** `nix build .#x` / `nix eval` open the repo via
  libgit2, which FAILS on the `extensions.relativeworktrees` git extension that
  relative worktrees set (`unsupported extension name extensions.relativeworktrees`).
  Work around with a `path:` flakeref — `nix build "path:$PWD#x"` — which copies the
  tree instead of reading git. (`nix fmt` itself is unaffected.)
- **treefmt formats in place even under `--fail-on-change`**, then reports the
  diff and exits non-zero. So after a failed pre-commit, the working tree is
  already formatted — just `git add` and re-commit.

## Related

- `worktree` skill — external-worktree lifecycle; its sops re-smudge scans the
  same `.gitattributes` filters.
- `hub-subtree-sync` — this skill lives in the shared hub; publish hub edits up
  at session end.
