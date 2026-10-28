#!/usr/bin/env bash
# bump: play the version bumps the operator chose, by name, from the unified report (#versions).
#
#   bump [--plan] <name>…        from the rke2lab repo root, on a feature branch
#
# Each chosen name is looked up in the report; its `source` decides its mechanism. Two kinds, and the order between
# them is fixed: (A) a nix lock edge, played FIRST, through relock's own pass, which pushes in the graph's order
# through each head's pre-push gate (the operator choosing the line is the go); then (B) what touches code, one
# commit per line on THIS branch, for 🧭's merge. A line that cannot be played (red checks, an apply that fails) is
# undone alone, reported, and the next one runs. --plan prints the plan and changes nothing.
#
#   source                                      mechanism                                      kind
#   nix-flake-commons:flake.lock#<input>        relock <input> in nix-flake-commons, --downstream   A
#   rke2lab:flake.lock#nixpkgs (a tool's line)  = nixpkgs-unstable: the line moves with it          A
#   rke2lab:flake.lock#<input>                  relock <input> here                                  A
#   rke2lab:nixos/rke2-1-36.nix                 the overlay's versions → upstream's release          B
#   rke2lab:…/Component.java                    manifests versions apply=true component=<name>       B
set -euo pipefail

usage() { echo "usage: bump [--plan] <name>…   (names from \`nix run .#versions\`)" >&2; exit 2; }
plan_only=0
names=()
for arg in "$@"; do
  case $arg in
    --plan) plan_only=1 ;;
    -*) usage ;;
    *) names+=("$arg") ;;
  esac
done
((${#names[@]} > 0)) || usage
[[ -f pom.xml && -f flake.nix ]] || { echo "bump: run from the rke2lab repo root" >&2; exit 2; }
branch=$(git rev-parse --abbrev-ref HEAD)
case $branch in develop | main | HEAD) echo "bump: on $branch; bump from a feature branch" >&2; exit 2 ;; esac
[[ -z $(git status --porcelain) ]] || { echo "bump: the worktree has changes; commit or set them aside first" >&2; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# The report: @versions@ is this flake's #versions program; BUMP_REPORT names a ready JSONL instead (the tests).
if [[ -n ${BUMP_REPORT:-} ]]; then cp "$BUMP_REPORT" "$work/report.jsonl"; else @versions@ --format=jsonl >"$work/report.jsonl"; fi

# Resolve each name to its line and its mechanism; a nixpkgs tool's line resolves to nixpkgs-unstable.
: >"$work/plan.tsv"
for name in "${names[@]}"; do
  line=$(jq -c --arg n "$name" 'select(.name == $n)' "$work/report.jsonl" | head -1)
  [[ -n $line ]] || { echo "bump: no line named '$name' in the report" >&2; exit 2; }
  source=$(jq -r .source <<<"$line")
  upstream=$(jq -r .upstream <<<"$line")
  status=$(jq -r .status <<<"$line")
  latest=$(jq -r '.latest // ""' <<<"$line")
  case $source in
    nix-flake-commons:flake.lock#*) printf 'A\t%s\tnfc\trelock %s (in nix-flake-commons, then --downstream)\t%s\n' "$name" "${source#*#}" "${source#*#}" ;;
    rke2lab:flake.lock#nixpkgs)
      if [[ $upstream == nixpkgs:nixpkgs-unstable#* ]]; then
        with=$(jq -r 'select(.source == "rke2lab:flake.lock#nixpkgs") | .name' "$work/report.jsonl" | grep -vx "$name" | tr '\n' ' ')
        echo "bump: '$name' is not pinned by us: it moves with nixpkgs-unstable, and with it: ${with:-nothing else}" >&2
        printf 'A\t%s\tnfc\trelock nixpkgs-unstable (in nix-flake-commons, then --downstream)\tnixpkgs-unstable\n' "$name"
      else
        echo "bump: '$name' has an unknown upstream $upstream" >&2; exit 2
      fi
      ;;
    rke2lab:flake.lock#*) printf 'A\t%s\there\trelock %s\t%s\n' "$name" "${source#*#}" "${source#*#}" ;;
    rke2lab:nixos/rke2-1-36.nix) printf 'B\t%s\toverlay\tthe overlay to %s\t%s\n' "$name" "$latest" "$latest" ;;
    rke2lab:*Component.java) printf 'B\t%s\tcomponent\tmanifests versions apply=true component=%s\t%s\n' "$name" "$name" "$name" ;;
    *) echo "bump: '$name' has a source no mechanism plays: $source" >&2; exit 2 ;;
  esac >>"$work/plan.tsv"
  [[ $status == current ]] && echo "bump: '$name' is already current; nothing to play" >&2
done
# The nfc relocks of one input, deduplicated (kubectl and helm are both « nixpkgs-unstable »), A before B.
sort -t $'\t' -k1,1 -s "$work/plan.tsv" | awk -F'\t' '!seen[$1 FS $3 FS $5]++' >"$work/ordered.tsv"

echo "bump plan, from $branch:"
awk -F'\t' '{ printf "  %d · %s  %-18s %s\n", NR, $1, $2, $4 }' "$work/ordered.tsv"
echo "  A = a nix lock edge, pushed by relock's pass in the graph's order · B = a commit on $branch, for the merge"
((plan_only)) && exit 0

: >"$work/done.tsv"; : >"$work/dropped.tsv"
checks() { # the named checks of a B line, on both systems; an argument names the commit to check
  local rev=$1 c sys
  if [[ -n ${BUMP_CHECKS:-} ]]; then "$BUMP_CHECKS" "$rev"; return; fi
  for sys in aarch64-darwin aarch64-linux; do
    for c in @checks@; do
      @nix@ build --no-link "git+file://$PWD?rev=$rev#checks.$sys.$c" >/dev/null 2>&1 || { echo "$c on $sys"; return 1; }
    done
  done
}
while IFS=$'\t' read -r kind name where what arg; do
  echo "== $kind $name: $what"
  case $where in
    nfc)
      if "${BUMP_NFC_RELOCK:-@nfcRelock@}" "$arg"; then echo "A	$name	$what" >>"$work/done.tsv"
      else echo "A	$name	relock $arg failed in nix-flake-commons" >>"$work/dropped.tsv"; fi
      ;;
    here)
      if "${BUMP_RELOCK:-@relock@}" "$arg"; then echo "A	$name	$what" >>"$work/done.tsv"
      else echo "A	$name	relock $arg failed" >>"$work/dropped.tsv"; fi
      ;;
    component | overlay)
      before=$(git rev-parse HEAD)
      if [[ $where == component ]]; then
        "${BUMP_MANIFESTS:-@manifests@}" versions apply=true level=major component="$arg" || true
      else
        "${BUMP_OVERLAY:-@overlay@}" "$arg" || true
      fi
      if [[ $(git rev-parse HEAD) == "$before" ]]; then
        echo "B	$name	nothing was committed" >>"$work/dropped.tsv"
      elif [[ $(git rev-parse --abbrev-ref HEAD) != "$branch" ]]; then
        echo "bump: the commit for $name left $branch; stopping" >&2; exit 1
      elif why=$(checks "$(git rev-parse HEAD)"); then
        echo "B	$name	$(git log -1 --format=%h) $what" >>"$work/done.tsv"
      else
        git reset -q --hard "$before"
        echo "B	$name	red: $why; its commit dropped" >>"$work/dropped.tsv"
      fi
      ;;
  esac
done <"$work/ordered.tsv"

echo
echo "played:"; awk -F'\t' '{ printf "  ✓ %s %s: %s\n", $1, $2, $3 }' "$work/done.tsv"
if [[ -s $work/dropped.tsv ]]; then
  echo "dropped, back to you:"; awk -F'\t' '{ printf "  ✗ %s %s: %s\n", $1, $2, $3 }' "$work/dropped.tsv"
  exit 1
fi
