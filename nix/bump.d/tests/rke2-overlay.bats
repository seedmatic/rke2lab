#!/usr/bin/env bats
# rke2-overlay against stubs: gh, curl, nix-prefetch-url and nix answer from fixtures (a release with two image
# archives, a source whose scripts/version.sh sets the six image tags), a throwaway git repo as the rke2lab root.
# Covers the files written and committed once with the build's vendor hash, a tag missing from the release's image
# list refused with nothing written, and a build that reports no vendor hash undoing the commit.

setup() {
	bin="$BATS_TEST_TMPDIR/bin"
	fx="$BATS_TEST_TMPDIR/fx"
	mkdir -p "$bin" "$fx/src/scripts"
	sh=$(command -v bash)
	commit=0123456789abcdef0123456789abcdef01234567
	cat >"$fx/src/scripts/version.sh" <<'EOF'
KUBERNETES_IMAGE_TAG=v1.2.3-rke2r1-build1
ETCD_IMAGE_TAG=${ETCD_IMAGE_TAG:-v3.7.1-k3s3-build2}
PAUSE_VERSION=3.10.2
CCM_VERSION=v1.2.3-ccm-build3
DOCKERIZED_VERSION=v1.2.3-rke2r1
KLIPPERHELM_VERSION=v0.13.3-build4
EOF
	cat >"$fx/rke2-images-all.linux-arm64.txt" <<'EOF'
docker.io/rancher/hardened-etcd:v3.7.1-k3s3-build2
docker.io/rancher/hardened-kubernetes:v1.2.3-rke2r1-build1
docker.io/rancher/klipper-helm:v0.13.3-build4
docker.io/rancher/mirrored-pause:3.10.2
docker.io/rancher/rke2-cloud-provider:v1.2.3-ccm-build3
docker.io/rancher/rke2-runtime:v1.2.3-rke2r1
EOF
	echo "aaaa  rke2-images-core.linux-amd64.tar.zst" >"$fx/sha256sum-amd64.txt"
	echo "bbbb  rke2-images-core.linux-arm64.tar.zst" >"$fx/sha256sum-arm64.txt"
	cat >"$fx/release.json" <<'EOF'
{"assets": [
  {"name": "rke2-images-core.linux-arm64.tar.zst", "browser_download_url": "https://x/arm64.tar.zst"},
  {"name": "rke2-images-core.linux-amd64.tar.zst", "browser_download_url": "https://x/amd64.tar.zst"},
  {"name": "sha256sum-arm64.txt", "browser_download_url": "https://x/sums"}
]}
EOF
	printf '#!%s\ncase "$2" in */commits/*) echo "{\\"sha\\": \\"%s\\"}" ;; */releases/tags/*) cat %q ;; *) exit 1 ;; esac\n' \
		"$sh" "$commit" "$fx/release.json" >"$bin/gh"
	printf '#!%s\nu=${@: -1}; cat %q/"${u##*/}"\n' "$sh" "$fx" >"$bin/curl"
	printf '#!%s\necho 0tarballnix32\necho %q\n' "$sh" "$fx/src" >"$bin/nix-prefetch-url"
	# nix: `hash convert` gives the tarball's SRI; `build` fails as a fake vendor hash does, naming NIX_GOT if set.
	printf '#!%s\ncase "$1" in hash) echo sha256-TARBALL= ;; build) [ -n "${NIX_GOT:-}" ] && printf "error: hash mismatch\\n         specified: sha256-AAAA\\n            got:    %%s\\n" "$NIX_GOT" >&2; exit 1 ;; esac\n' \
		"$sh" >"$bin/nix"
	chmod +x "$bin"/*
	export PATH="$bin:$PATH"
	export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
	repo="$BATS_TEST_TMPDIR/repo"
	git init -q -b feature/x "$repo"
	cd "$repo"
	mkdir -p nixos/rke2-version.d
	echo '{ }' >nixos/rke2-version.nix
	echo '{ old = true; }' >nixos/rke2-version.d/versions.nix
	echo '{}' >nixos/rke2-version.d/images-versions.json
	git add . && git commit -qm init
	base=$(git rev-parse HEAD)
}

overlay() { bash "$BATS_TEST_DIRNAME/../rke2-overlay.sh" "$@"; }

@test "it vendors the release and commits it once, with the vendor hash the build reports" {
	NIX_GOT=sha256-VENDOR+/= run overlay v1.2.3+rke2r1
	[ "$status" -eq 0 ]
	[ "$(git rev-list --count "$base"..HEAD)" = 1 ]
	[ -z "$(git status --porcelain)" ]
	v=nixos/rke2-version.d/versions.nix
	grep -qx '  rke2Version = "1.2.3+rke2r1";' $v
	grep -qx "  rke2Commit = \"$commit\";" $v
	grep -qx '  rke2TarballHash = "sha256-TARBALL=";' $v
	grep -qx '  rke2VendorHash = "sha256-VENDOR+/=";' $v
	grep -qx '  etcdVersion = "v3.7.1-k3s3-build2";' $v
	grep -qx '  helmJobVersion = "v0.13.3-build4";' $v
	j=nixos/rke2-version.d/images-versions.json
	[ "$(jq -r 'keys | join(" ")' $j)" = "images-core-linux-amd64-tar-zst images-core-linux-arm64-tar-zst" ]
	[ "$(jq -r '."images-core-linux-arm64-tar-zst".sha256' $j)" = bbbb ]
	[ "$(jq -r '."images-core-linux-amd64-tar-zst".url' $j)" = https://x/amd64.tar.zst ]
	# canonical: sorted keys, jq's own layout
	diff <(jq -S . $j) $j
	[[ $(git log -1 --format=%s) == "chore(nodes): rke2 1.2.3+rke2r1, vendored" ]]
}

@test "an image tag missing from the release's arm64 list is refused, and nothing is written" {
	sed -i '/klipper-helm/d' "$fx/rke2-images-all.linux-arm64.txt"
	NIX_GOT=sha256-VENDOR= run overlay v1.2.3+rke2r1
	[ "$status" -ne 0 ]
	[[ $output == *"rancher/klipper-helm:v0.13.3-build4 is not in v1.2.3+rke2r1's arm64 image list: nothing written"* ]]
	[ "$(git rev-parse HEAD)" = "$base" ]
	[ -z "$(git status --porcelain)" ]
}

@test "a go-modules build that reports no vendor hash undoes the commit" {
	run overlay v1.2.3+rke2r1
	[ "$status" -ne 0 ]
	[[ $output == *"reported no vendor hash: its commit undone"* ]]
	[ "$(git rev-parse HEAD)" = "$base" ]
	[ -z "$(git status --porcelain)" ]
}

@test "an image archive with no sha256 in the release's sum files is refused" {
	: >"$fx/sha256sum-arm64.txt"
	NIX_GOT=sha256-VENDOR= run overlay v1.2.3+rke2r1
	[ "$status" -ne 0 ]
	[[ $output == *"rke2-images-core.linux-arm64.tar.zst has no sha256"* ]]
	[ "$(git rev-parse HEAD)" = "$base" ]
}
