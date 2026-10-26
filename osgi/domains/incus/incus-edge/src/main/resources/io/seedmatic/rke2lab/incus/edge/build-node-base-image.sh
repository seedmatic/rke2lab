#!/usr/bin/env sh
set -eu

if [ "$#" -ne 5 ]; then
    echo "usage: $0 <flake-ref> <artifact-dir> <nix-binary> <source-digest> <nixos-configuration>" >&2
    exit 2
fi

flake_ref="$1"
artifact_dir="$2"
nix_bin="$3"
source_digest="$4"
# The node image to build, one per call: rke2-node-server (the root and every control plane) or rke2-node-base
# (the agent, for the worker pools). Each has its own artifact dir.
configuration="$5"
case "$configuration" in
rke2-node-server | rke2-node-base) ;;
*)
    echo "not a node image: $configuration" >&2
    exit 2
    ;;
esac

export PATH="/run/wrappers/bin:/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

case "$artifact_dir" in
/*) ;;
*)
    echo "the artifact dir must be absolute: $artifact_dir" >&2
    exit 2
    ;;
esac
mkdir -p "$artifact_dir"

metadata_name="incus.tar.xz"
rootfs_name="rootfs.squashfs"
# The RKE2 version baked into the image, emitted from nix beside the two artifacts (see the else
# branch): a true property of the built image, so no version is ever hand-pinned downstream.
rke2_version_file="$artifact_dir/rke2.version"

# The flake ref names a REVISION (git+file://<repository>?rev=<sha>): nix reads that commit
# through the repository, so what is built is what was committed and no working file reaches the
# store. The source digest is the scion's content digest of that revision's build inputs
# (flake.lock, flake.nix, nixos/), the same one folded into the plan's buildChecksum. Unchanged
# digest + both artifacts still on disk => artifacts current, skip nix.
checksum_file="$artifact_dir/.image.checksum.sha256"

if [ -f "$checksum_file" ] && [ "$(cat "$checksum_file")" = "$source_digest" ] &&
    [ -f "$artifact_dir/$metadata_name" ] && [ -f "$artifact_dir/$rootfs_name" ] &&
    [ -f "$rke2_version_file" ]; then
    echo "$configuration sources unchanged ($source_digest) — reusing on-disk artifacts, skipping nix build"
else
    # The homogeneous NixOS substrate every RKE2 node boots from. Build its two Incus artifacts: nix
    # realises them into /nix/store (local, content-addressed) — no tmpfs scratch and no root, unlike
    # distrobuilder. Only the two finished files are published below.
    attr="nixosConfigurations.$configuration.config.system.build"

    # Cross-build the aarch64-linux node-base artifacts from this (possibly darwin)
    # host: `--system aarch64-linux` + `--max-jobs 0` force every derivation onto the
    # configured aarch64-linux builder (bioskop-nixos) rather than a doomed local build
    # (a linux ELF won't run on darwin, e.g. "executing bash: Undefined error: 0"). No
    # --accept-flake-config: no flake of ours declares a nixConfig; the builder's own nix.conf
    # carries the caches (cache.flox.dev included). The evaluation is PURE: a revision in, nothing read from the host (measured 2026-10-12: the same
    # drvPaths with and without --impure).
    nix_flags="--no-link --print-out-paths --system aarch64-linux --max-jobs 0"

    # build the two node-base artifacts.
    metadata_out="$($nix_bin build $nix_flags "$flake_ref#$attr.metadata")"
    squashfs_out="$($nix_bin build $nix_flags "$flake_ref#$attr.squashfs")"

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

    # Resolve the RKE2 version baked into THIS node-base image from the SAME revision the artifacts
    # were built from (nix is the source of truth — never a hand-pinned literal), and emit it beside
    # them. The scion folds it into the image identity, from which the workload
    # RKE2ControlPlane.spec.version derives. `nix eval` takes only the eval flags — the build-only
    # --no-link / --print-out-paths / --max-jobs would be rejected.
    rke2_version="$("$nix_bin" eval --raw \
        "$flake_ref#nixosConfigurations.$configuration.config.services.rke2.package.version")"
    printf '%s\n' "$rke2_version" >"$rke2_version_file"
fi

# Build-only: the two artifacts ($metadata_name + $rootfs_name) and their freshness checksum are the
# whole output. The image is NOT imported here — that is the incus PROVIDER's job (the host GROW
# declares an `Image` resource sourcing these files, so the provider orders Project → Image → Instance
# and the import rides the provider's own channel). This keeps the build a pure artifact producer with
# no incus coupling and no out-of-graph side effect. See
# docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc's sibling — the substrate model.
