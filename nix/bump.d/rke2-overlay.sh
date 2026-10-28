#!/usr/bin/env bash
# bump's rke2 mechanism (kind B): vendor upstream's rke2 <version> into nixos/rke2-version.d the way nixpkgs' rke2
# update script writes a release's versions (versions.nix + images-versions.json), then commit it, its vendor hash
# filled by a build. A micro and a minor are the same gesture; the stable nixpkgs' builder builds it
# (nixos/rke2-version.nix), and bump keeps the commit only if the named checks pass on both systems.
#
# The sources, as the update script reads them: the tag's commit (GitHub), the tarball's hash (nix-prefetch-url), the
# image tags from the source's scripts/version.sh, the images from the release's sha256sum files and assets. The image
# tags must all be in the release's arm64 image list, or nothing is written. The vendor hash is the one the go-modules
# build reports for a fake one, built by revision on linux; with none reported, the commit is undone.
#
#   rke2-overlay <version>      from the rke2lab repo root, e.g. v1.37.1+rke2r1
set -euo pipefail
version=${1:?usage: rke2-overlay <version>}
version=${version#v}
tag=v$version
dir=nixos/rke2-version.d
die() { echo "rke2-overlay: $*" >&2; exit 1; }
[[ -f nixos/rke2-version.nix && -d $dir ]] || { echo "rke2-overlay: run from the rke2lab repo root" >&2; exit 2; }
release="https://github.com/rancher/rke2/releases/download/${tag/+/%2B}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

commit=$(gh api "repos/rancher/rke2/commits/$tag" | jq -r .sha)
[[ $commit =~ ^[0-9a-f]{40}$ ]] || die "no commit for $tag"
meta=$(nix-prefetch-url --unpack --print-path "https://github.com/rancher/rke2/archive/refs/tags/$tag.tar.gz")
src=${meta##*$'\n'}
tarball=$(nix hash convert --hash-algo sha256 --to sri "${meta%%$'\n'*}")
# scripts/version.sh sets the image tags from the source, as the update script sources it; etcd's whole tag is
# ETCD_IMAGE_TAG (since 1.37), the form versions.nix keeps.
tags=$(
  cd "$src" && GITHUB_ACTION_TAG=$tag DRONE_COMMIT=$commit bash -c '
    set +u; source scripts/version.sh >/dev/null 2>&1
    printf "%s\n" "$KUBERNETES_IMAGE_TAG" "$ETCD_IMAGE_TAG" "$PAUSE_VERSION" "$CCM_VERSION" "$DOCKERIZED_VERSION" "$KLIPPERHELM_VERSION"'
)
mapfile -t t <<<"$tags"
((${#t[@]} == 6)) || die "scripts/version.sh of $tag gave ${#t[@]} image tags, not 6"
for v in "${t[@]}"; do [[ -n $v ]] || die "scripts/version.sh of $tag left an image tag empty"; done

curl -sSL --fail "$release/rke2-images-all.linux-arm64.txt" >"$work/images.txt"
i=0
for image in hardened-kubernetes hardened-etcd mirrored-pause rke2-cloud-provider rke2-runtime klipper-helm; do
  grep -qxF "docker.io/rancher/$image:${t[i]}" "$work/images.txt" || die "rancher/$image:${t[i]} is not in $tag's arm64 image list: nothing written"
  i=$((i + 1))
done

{ curl -sSL --fail "$release/sha256sum-amd64.txt"; curl -sSL --fail "$release/sha256sum-arm64.txt"; } >"$work/sums"
gh api "repos/rancher/rke2/releases/tags/$tag" |
  jq -r '.assets[] | select(.name | test("^rke2-images-.*\\.tar\\.")) | "\(.name) \(.browser_download_url)"' |
  while read -r name url; do
    sha=$(awk -v n="$name" '$2 == n { print $1; exit }' "$work/sums")
    [[ -n $sha ]] || die "$name has no sha256 in $tag's sha256sum files"
    jq -n --arg name "$(sed -e 's/^rke2-//' -e 's/\./-/g' <<<"$name")" --arg url "$url" --arg sha256 "$sha" \
      '{($name): {url: $url, sha256: $sha256}}'
  done | jq -S --slurp 'reduce .[] as $i ({}; . * $i)' >"$work/images-versions.json"
[[ $(jq length "$work/images-versions.json") -gt 0 ]] || die "$tag's release lists no image archive"

fake=sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
versionsNix() { # <vendor hash>
  cat <<EOF
{
  rke2Version = "$version";
  rke2Commit = "$commit";
  rke2TarballHash = "$tarball";
  rke2VendorHash = "$1";
  k8sImageTag = "${t[0]}";
  etcdVersion = "${t[1]}";
  pauseVersion = "${t[2]}";
  ccmVersion = "${t[3]}";
  dockerizedVersion = "${t[4]}";
  helmJobVersion = "${t[5]}";
  imagesVersions = with builtins; fromJSON (readFile ./images-versions.json);
}
EOF
}
cp "$work/images-versions.json" "$dir/images-versions.json"
versionsNix "$fake" >"$dir/versions.nix"
git add "$dir/versions.nix" "$dir/images-versions.json"
git commit -q -m "chore(nodes): rke2 $version, vendored" \
  -m "Written by #bump's rke2 mechanism from upstream's $tag: commit $commit, its image tags checked against the release's arm64 image list."
# The vendor hash: the go-modules build of this commit, by revision, reports the real one for the fake.
before=$(git rev-parse HEAD~1)
got=$(nix build --no-link "git+file://$PWD?rev=$(git rev-parse HEAD)#nixosConfigurations.rke2-node-base.config.services.rke2.package.goModules" 2>&1 |
  sed -n 's/^ *got: *\(sha256-[A-Za-z0-9+\/=]*\)$/\1/p' | head -1) || true
if [[ -z $got ]]; then
  git reset -q --hard "$before"
  die "the go-modules build of rke2 $version reported no vendor hash: its commit undone"
fi
versionsNix "$got" >"$dir/versions.nix"
git add "$dir/versions.nix"
git commit -q --amend --no-edit
echo "rke2-overlay: rke2 $version vendored, vendor hash $got"
