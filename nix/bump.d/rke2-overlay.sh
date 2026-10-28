#!/usr/bin/env bash
# bump's rke2 mechanism (kind B): point nixos/rke2-1-36.nix at upstream's versions for <version>, as a vendored
# versions.nix beside it (nixpkgs' update-script layout: version, commit, the two hashes, the image tags), then
# commit. The hashes come from upstream: the tarball's by nix-prefetch, the vendor hash left for the build to fill,
# so a bump whose build fails is dropped by bump's checks, never committed red.
#
#   rke2-overlay <version>      from the rke2lab repo root, e.g. v1.37.1+rke2r1
set -euo pipefail
version=${1:?usage: rke2-overlay <version>}
version=${version#v}
minor=$(cut -d. -f1-2 <<<"$version" | tr . _)
[[ -f nixos/rke2-1-36.nix ]] || { echo "rke2-overlay: run from the rke2lab repo root" >&2; exit 2; }
echo "rke2-overlay: rke2 $version (minor $minor) needs its versions.nix: nixpkgs' rke2/update-script.sh writes one" >&2
echo "              per minor; vendoring it is a reviewed change, so this mechanism stops here and reports." >&2
exit 3
