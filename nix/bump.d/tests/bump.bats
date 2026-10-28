#!/usr/bin/env bats
# bump against stubs: a fixed report (BUMP_REPORT), stub mechanisms that record their call, a throwaway git repo as
# the rke2lab root. Covers the dispatch (source → mechanism), the order (A before B), the dedup of a nixpkgs tool's
# lines, the plan printed first, and a red line dropped alone while the next one runs.

setup() {
	bin="$BATS_TEST_TMPDIR/bin"
	calls="$BATS_TEST_TMPDIR/calls"
	mkdir -p "$bin"
	: >"$calls"
	sh=$(command -v bash)
	for m in nfc relock overlay; do
		printf '#!%s\necho "%s $*" >>%q\n' "$sh" "$m" "$calls" >"$bin/$m"
	done
	# The components mechanism commits, like `manifests versions apply=true`: one file per component.
	printf '#!%s\nfor a; do case $a in component=*) c=${a#component=} ;; esac; done\necho "manifests $c" >>%q\necho "$c" >"pin-$c" && git add "pin-$c" && git -c user.name=t -c user.email=t@t commit -qm "bump $c"\n' "$sh" "$calls" >"$bin/manifests"
	chmod +x "$bin"/*
	export BUMP_NFC_RELOCK="$bin/nfc" BUMP_RELOCK="$bin/relock" BUMP_OVERLAY="$bin/overlay" BUMP_MANIFESTS="$bin/manifests"
	export BUMP_REPORT="$BATS_TEST_TMPDIR/report.jsonl"
	export BUMP_HEADS="$BATS_TEST_TMPDIR/heads"
	printf '%s\n' flake-commons ndh flox-controller >"$BUMP_HEADS"
	cat >"$BUMP_REPORT" <<'EOF'
{"world":"nix","name":"kubectl","source":"rke2lab:flake.lock#nixpkgs-unstable","pinned":"1.36.3","latest":"1.37.1","level":"minor","status":"behind","upstream":"nixpkgs:nixpkgs-unstable#kubectl"}
{"world":"nix","name":"kubernetes-helm","source":"rke2lab:flake.lock#nixpkgs-unstable","pinned":"3.20.2","latest":"4.3.0","level":"major","status":"behind","upstream":"nixpkgs:nixpkgs-unstable#kubernetes-helm"}
{"world":"nix","name":"nixpkgs","source":"nix-flake-commons:flake.lock#nixpkgs","pinned":"26f4107e","latest":"718f670e","level":null,"status":"unknown","upstream":"github:NixOS/nixpkgs#nixos-26.05"}
{"world":"nix","name":"ndh","source":"rke2lab:flake.lock#ndh","pinned":"7a3f6593","latest":"8abc22ea","level":null,"status":"unknown","upstream":"github:seedmatic/ndh#develop"}
{"world":"nix","name":"seed-tool","source":"rke2lab:flake.lock#seed-tool","pinned":"aaaa1111","latest":"bbbb2222","level":null,"status":"unknown","upstream":"github:x/seed-tool#develop"}
{"world":"nix","name":"rke2","source":"rke2lab:nixos/rke2-version.d/versions.nix","pinned":"1.36.4+rke2r1","latest":"v1.37.1+rke2r1","level":"minor","status":"behind","upstream":"github:rancher/rke2"}
{"world":"clusters","name":"kubeVip","source":"rke2lab:osgi/x/Component.java","pinned":"v0.9.2","latest":"v1.3.1","level":"major","status":"behind","upstream":"github:kube-vip/kube-vip"}
{"world":"clusters","name":"certManager","source":"rke2lab:osgi/x/Component.java","pinned":"v1.21.1","latest":"v1.21.3","level":"micro","status":"behind","upstream":"github:cert-manager/cert-manager"}
EOF
	repo="$BATS_TEST_TMPDIR/repo"
	git init -q -b feature/x "$repo"
	cd "$repo"
	touch pom.xml flake.nix
	git add . && git -c user.name=t -c user.email=t@t commit -qm init
	# The checks stub: red when the commit adds the pin file BUMP_CHECKS_RED names, green otherwise.
	printf '#!%s
if [ -n "${BUMP_CHECKS_RED:-}" ] && git show --name-only --format= "$1" | grep -qx "pin-$BUMP_CHECKS_RED"; then echo red; exit 1; fi
' "$sh" >"$bin/checks"
	chmod +x "$bin/checks"
	export BUMP_CHECKS="$bin/checks"
	export PATH="$bin:$PATH"
}

bump() { "$BATS_TEST_DIRNAME/../bump.sh" "$@"; }

@test "the plan maps each source to its mechanism, A before B, and changes nothing" {
	run bump --plan kubeVip kubectl ndh nixpkgs rke2
	[ "$status" -eq 0 ]
	first=$(grep '1 · A' <<<"$output")
	[[ $first == *kubectl* && $first == *nixpkgs* && $first == *"in nix-flake-commons, then one pass"* ]]
	[[ $first == *"relock "*nixpkgs-unstable* ]]
	[[ $output == *"A  ndh"*"moves with that pass"* ]]
	[[ $output == *"B  rke2"*"the overlay to v1.37.1+rke2r1"* ]]
	[[ $output == *"B  kubeVip"*"manifests versions apply=true component=kubeVip"* ]]
	# A before B
	a=$(grep -n '· A' <<<"$output" | tail -1 | cut -d: -f1)
	b=$(grep -n '· B' <<<"$output" | head -1 | cut -d: -f1)
	[ "$a" -lt "$b" ]
	[ ! -s "$BATS_TEST_TMPDIR/calls" ]
}

@test "a nixpkgs tool's line says it means nixpkgs-unstable, and the lines that move with it" {
	run bump --plan kubectl
	[ "$status" -eq 0 ]
	[[ $output == *"kubectl is not pinned by us: it moves with nixpkgs-unstable, and with it: kubernetes-helm"* ]]
}

@test "the chosen tools of nixpkgs-unstable say so in ONE line" {
	run bump --plan kubectl kubernetes-helm
	[ "$status" -eq 0 ]
	[ "$(grep -c 'not pinned by us' <<<"$output")" = 1 ]
	[[ $output == *"kubectl kubernetes-helm are not pinned by us: they move with nixpkgs-unstable, and with them: nothing else"* ]]
}

@test "two tools of one nixpkgs are one relock" {
	run bump --plan kubectl kubernetes-helm
	[ "$status" -eq 0 ]
	[ "$(grep -c 'then one pass' <<<"$output")" -eq 1 ]
}

@test "the lines are played in the plan's order, one commit per component line" {
	run bump seed-tool kubeVip certManager
	[ "$status" -eq 0 ]
	[ "$(cat "$BATS_TEST_TMPDIR/calls")" = "$(printf 'relock seed-tool\nmanifests kubeVip\nmanifests certManager')" ]
	[ "$(git log --format=%s -2 | tr '\n' ' ')" = "bump certManager bump kubeVip " ]
}

@test "a red line's commit is dropped alone, the next one still played, and it comes back" {
	export BUMP_CHECKS_RED=kubeVip
	run bump kubeVip certManager
	[ "$status" -eq 1 ]
	[[ $output == *"✗ B kubeVip: red"* ]]
	[[ $output == *"✓ B certManager"* ]]
	[ "$(git log --format=%s -1)" = "bump certManager" ]
	[ ! -f pin-kubeVip ]
}

@test "an unknown name is refused; playing on a dirty tree is refused, planning there is not" {
	run bump --plan nosuch
	[ "$status" -eq 2 ]
	touch dirty
	run bump --plan kubeVip
	[ "$status" -eq 0 ]
	run bump kubeVip
	[ "$status" -eq 2 ]
	[ ! -s "$BATS_TEST_TMPDIR/calls" ]
}

@test "the nfc lines are ONE relock and ONE pass, and a head rides it" {
	run bump nixpkgs kubectl ndh
	[ "$status" -eq 0 ]
	[ "$(cat "$BATS_TEST_TMPDIR/calls")" = "nfc nixpkgs nixpkgs-unstable" ]
	[[ $output == *"✓ A ndh: moved with the pass"* ]]
}

@test "a head chosen alone makes one pass, with no relock in nfc" {
	run bump ndh
	[ "$status" -eq 0 ]
	[ "$(cat "$BATS_TEST_TMPDIR/calls")" = "nfc " ]
}

@test "propose, from develop: what is behind, one command per step, the nix edges as one, majors flagged" {
	git checkout -q -b develop
	run bump
	[ "$status" -eq 0 ]
	[[ $output == *"⚠ nix run .#bump -- kubeVip"* ]]
	[[ $output == *"nix run .#bump -- certManager"* ]]
	[[ $output == *"nix run .#bump -- rke2"* ]]
	[ "$(grep -c 'one relock, one pass' <<<"$output")" -eq 1 ]
	nix=$(grep 'one relock, one pass' <<<"$output")
	[[ $nix == "  ⚠"* ]]
	for n in kubectl kubernetes-helm nixpkgs ndh seed-tool; do [[ $nix == *" $n"* ]]; done
	[ ! -s "$BATS_TEST_TMPDIR/calls" ]
}

@test "the plan runs from develop and changes nothing; playing there is refused" {
	git checkout -q -b develop
	run bump --plan kubeVip
	[ "$status" -eq 0 ]
	run bump kubeVip
	[ "$status" -eq 2 ]
	[ ! -s "$BATS_TEST_TMPDIR/calls" ]
}
