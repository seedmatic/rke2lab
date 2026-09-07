#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <workspace> <artifact-dir> <nix-binary>" >&2
    exit 2
fi

workspace="$1"
artifact_dir="$2"
nix_bin="${3:-nix}"

export PATH="/run/wrappers/bin:/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

cd "$workspace"

# The artifact dir arrives RELATIVE to the workspace root (the host passes only the subpath and lets
# the builder join it onto the automount root it cd'd into). An already-absolute value is honoured.
case "$artifact_dir" in
/*) ;;
*) artifact_dir="$workspace/$artifact_dir" ;;
esac
mkdir -p "$artifact_dir"

metadata_name="incus.tar.xz"
rootfs_name="rootfs.squashfs"
# The RKE2 version baked into the image, emitted from nix beside the two artifacts (see the else
# branch): a true property of the built image, so no version is ever hand-pinned downstream.
rke2_version_file="$artifact_dir/rke2.version"

# Build from the STAGED index, not HEAD: `git write-tree` writes the current index to a tree object
# and prints its SHA. When nothing is staged that tree is byte-identical to HEAD's (so an unamended
# run behaves exactly as before); when node-base edits are `git add`-ed, they are built WITHOUT a
# commit — the iteration loop is stage-and-grow, not commit-and-grow. Unstaged working-tree edits are
# NOT included: stage what you want built. Real git writes the tree (it understands this worktree's
# `relativeworktrees` extension); only nix's libgit2 chokes on the extension, which is why the tree
# is exported to a throwaway dir below rather than fetched as a git+file flake.
source_tree="$(git -C "$workspace" write-tree)"

# Freshness gate over the EXACT build inputs in that tree. `git ls-tree` lists the blob SHA of every
# file under flake.lock / flake.nix / nixos/ — a CONTENT digest (not mtimes, not the commit id) of
# what nix builds from. The flox runtime tree (runtime/flox) is deliberately NOT folded: since the
# FloxEnv-CR migration the image no longer bakes envs from it (workload closures are realised at
# runtime by the flox-controller), so it is the catalog SOURCE only and a catalog-only edit must not
# rebuild the node image. The baked NRI plugin + OCI hooks ride the flox-runtime flake INPUT, pinned
# in flake.lock (already folded). Kept in lock-step with GrowPlanAssembler.imageSourceDigest (the OSGi
# twin). Unchanged inputs + both artifacts still on disk ⇒ artifacts current, skip nix.
source_digest="$(git -C "$workspace" ls-tree -r "$source_tree" -- flake.lock flake.nix nixos | sha256sum | awk '{print $1}')"
checksum_file="$artifact_dir/.image.checksum.sha256"

if [ -f "$checksum_file" ] && [ "$(cat "$checksum_file")" = "$source_digest" ] &&
    [ -f "$artifact_dir/$metadata_name" ] && [ -f "$artifact_dir/$rootfs_name" ] &&
    [ -f "$rke2_version_file" ]; then
    echo "node-base sources unchanged ($source_digest) — reusing on-disk artifacts, skipping nix build"
else
    # Export the staged tree (computed above) with real git into a throwaway dir, and build THAT as a
    # path-flake: no git+file fetch (which libgit2 would reject over the `relativeworktrees`
    # extension), and exactly the tree the freshness digest was taken over. flake.lock rides in the
    # archive, so inputs stay pinned.
    src_dir="$(mktemp -d)"
    trap 'rm -rf "$src_dir"' EXIT
    git -C "$workspace" archive --format=tar "$source_tree" | tar -x -C "$src_dir"

    # The homogeneous NixOS substrate every RKE2 node boots from. Build its two Incus artifacts: nix
    # realises them into /nix/store (local, content-addressed) — no tmpfs scratch and no root, unlike
    # distrobuilder. Only the two finished files are published below.
    attr="nixosConfigurations.rke2-node-base.config.system.build"

    # Cross-build the aarch64-linux node-base artifacts from this (possibly darwin)
    # host: `--system aarch64-linux` + `--max-jobs 0` force every derivation onto the
    # configured aarch64-linux builder (bioskop-nixos) rather than a doomed local build
    # (a linux ELF won't run on darwin, e.g. "executing bash: Undefined error: 0");
    # `--accept-flake-config` honours the flake's substituters/trusted config. `--impure`
    # is retained defensively for the nixos eval (the flox ENV baking that once required
    # it — buildenv.nix's storePath/currentSystem — is gone; envs are now runtime FloxEnv
    # CRs, so there is no per-env pre-realise pass here anymore).
    nix_flags="--no-link --print-out-paths --impure --accept-flake-config --system aarch64-linux --max-jobs 0"

    # build the two node-base artifacts.
    metadata_out="$($nix_bin build $nix_flags "$src_dir#$attr.metadata")"
    squashfs_out="$($nix_bin build $nix_flags "$src_dir#$attr.squashfs")"

    # Locate the artifacts inside the nix outputs, layout-robust: the metadata is a *.tar.xz under the
    # metadata output, the rootfs a *.squashfs under the squashfs output (or the output path itself).
    metadata_src="$(find "$metadata_out" -name '*.tar.xz' 2>/dev/null | head -n1)"
    squashfs_src="$(find "$squashfs_out" -name '*.squashfs' 2>/dev/null | head -n1)"
    if [ -z "$squashfs_src" ] && [ -f "$squashfs_out" ]; then
        squashfs_src="$squashfs_out"
    fi

    if [ -z "$metadata_src" ] || [ -z "$squashfs_src" ]; then
        echo "nix build did not yield a metadata tarball + rootfs squashfs" >&2
        echo "  metadata output: $metadata_out" >&2
        echo "  squashfs output: $squashfs_out" >&2
        exit 3
    fi

    cp -f "$metadata_src" "$artifact_dir/$metadata_name"
    cp -f "$squashfs_src" "$artifact_dir/$rootfs_name"
    chmod a+r "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name"

    # Record the inputs digest beside the artifacts so the next run's freshness gate can trust them.
    printf '%s\n' "$source_digest" >"$checksum_file"

    # Resolve the RKE2 version baked into THIS node-base image from the SAME staged tree the artifacts
    # were built from (nix is the source of truth — never a hand-pinned literal), and emit it beside
    # them. The scion folds it into the image identity, from which the workload
    # RKE2ControlPlane.spec.version derives. `nix eval` takes only the eval flags — the build-only
    # --no-link / --print-out-paths / --max-jobs would be rejected.
    rke2_version="$("$nix_bin" eval --raw --impure --accept-flake-config \
        "$src_dir#nixosConfigurations.rke2-node-base.config.services.rke2.package.version")"
    printf '%s\n' "$rke2_version" >"$rke2_version_file"
fi

# Build-only: the two artifacts ($metadata_name + $rootfs_name) and their freshness checksum are the
# whole output. The image is NOT imported here — that is the incus PROVIDER's job (the host GROW
# declares an `Image` resource sourcing these files, so the provider orders Project → Image → Instance
# and the import rides the provider's own channel). This keeps the build a pure artifact producer with
# no incus coupling and no out-of-graph side effect. See
# docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc's sibling — the substrate model.
