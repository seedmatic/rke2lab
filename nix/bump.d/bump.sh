#!/usr/bin/env bash
# bump: propose, or play, the version bumps of the unified report (#versions).
#
#   bump                         propose: what is behind, grouped as bump would play it, one command per step
#   bump --plan <name>…          the plan for the chosen names, and nothing changed
#   bump <name>…                 play them, from the rke2lab repo root, on a feature branch
#
# The first two are read-only and run from any checkout, develop included; only playing refuses develop, main and a
# dirty tree.
#
# A step is what one mechanism does. Each chosen name's `source` decides its step, and the order is fixed: (A) the
# nix lock edges FIRST, through relock's own pass, which pushes in the graph's order through each head's pre-push
# gate (the operator choosing the line is the go); then (B) what touches code, one commit per line on THIS branch,
# for 🧭's merge. A step that cannot be played (red checks, an apply that fails) is undone alone, reported, and the
# next one runs.
#
#   source                                      step                                                   kind
#   nix-flake-commons:flake.lock#<input>        nfc: relock every chosen input at once, then ONE pass   A
#   rke2lab:flake.lock#nixpkgs (a tool's line)  = nixpkgs-unstable, in that nfc step                     A
#   rke2lab:flake.lock#<head of fabric/heads>   moves with the pass, if there is one; else its own pass A
#   rke2lab:flake.lock#<other input>            relock <input> here                                     A
#   rke2lab:nixos/rke2-1-36.nix                 the overlay's versions → upstream's release             B
#   rke2lab:…/Component.java                    manifests versions apply=true component=<name>          B
set -euo pipefail

usage() { echo "usage: bump [--plan] [<name>…]   (no name: propose; names from \`nix run .#versions\`)" >&2; exit 2; }
plan_only=0
names=()
for arg in "$@"; do
  case $arg in
    --plan) plan_only=1 ;;
    -*) usage ;;
    *) names+=("$arg") ;;
  esac
done
[[ -f pom.xml && -f flake.nix ]] || { echo "bump: run from the rke2lab repo root" >&2; exit 2; }
branch=$(git rev-parse --abbrev-ref HEAD)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# The report: @versions@ is this flake's #versions program; BUMP_REPORT names a ready JSONL instead (the tests).
if [[ -n ${BUMP_REPORT:-} ]]; then cp "$BUMP_REPORT" "$work/report.jsonl"; else @versions@ --format=jsonl >"$work/report.jsonl"; fi
# The heads of the graph: an input that is one moves with relock's pass. Read at run time from nix-flake-commons'
# fabric/heads, as relock reads it (a branch, so never at evaluation); BUMP_HEADS names a ready list instead (the tests).
if [[ -n ${BUMP_HEADS:-} ]]; then
  cp "$BUMP_HEADS" "$work/heads"
else
  gh api -H 'Accept: application/vnd.github.raw' 'repos/seedmatic/nix-flake-commons/contents/heads.json?ref=fabric/heads' \
    | jq -r '.heads | keys[]' >"$work/heads" || { echo "bump: cannot read fabric/heads" >&2; exit 2; }
fi
is_head() { grep -qxF "$1" "$work/heads"; }

# A line's step: kind, step key, what it does. Lines that share a key are one step.
step_of() { # <line json>
  local name source upstream latest input
  name=$(jq -r .name <<<"$1"); source=$(jq -r .source <<<"$1")
  upstream=$(jq -r .upstream <<<"$1"); latest=$(jq -r '.latest // ""' <<<"$1")
  case $source in
    nix-flake-commons:flake.lock#*) printf 'A\tnfc\t%s\n' "${source#*#}" ;;
    rke2lab:flake.lock#nixpkgs)
      [[ $upstream == nixpkgs:nixpkgs-unstable#* ]] || { echo "bump: '$name' has an unknown upstream $upstream" >&2; return 2; }
      printf 'A\tnfc\tnixpkgs-unstable\n' ;;
    rke2lab:flake.lock#*)
      input=${source#*#}
      if is_head "$input"; then printf 'A\tpass\t%s\n' "$input"; else printf 'A\there\t%s\n' "$input"; fi ;;
    rke2lab:nixos/rke2-1-36.nix) printf 'B\toverlay\t%s\n' "$latest" ;;
    rke2lab:*Component.java) printf 'B\tcomponent\t%s\n' "$name" ;;
    *) echo "bump: '$name' has a source no mechanism plays: $source" >&2; return 2 ;;
  esac
}

# Propose: every line behind or unknown, grouped by step, one command each. Read-only.
if ((${#names[@]} == 0)); then
  : >"$work/proposals.tsv"
  while IFS= read -r line; do
    status=$(jq -r .status <<<"$line")
    [[ $status == behind || $status == unknown ]] || continue
    step=$(step_of "$line") || continue
    IFS=$'\t' read -r kind where arg <<<"$step"
    name=$(jq -r .name <<<"$line"); level=$(jq -r '.level // "-"' <<<"$line")
    printf '%s\t%s\t%s\t%s\t%s\n' "$kind" "$where" "$arg" "$name" "$level" >>"$work/proposals.tsv"
  done <"$work/report.jsonl"
  if [[ ! -s $work/proposals.tsv ]]; then echo "bump: nothing to propose: every line is current"; exit 0; fi
  echo "bump proposals (⚠ = a major), one command per step:"
  # The nix edges are ONE step (one relock in nfc, one pass); each code line is its own.
  awk -F'\t' '
    $1 == "A" { a = a " " $4; if ($5 == "major") am = 1 }
    $1 == "B" { printf "  %s nix run .#bump -- %s\n", ($5 == "major" ? "⚠" : " "), $4 }
    END { if (a != "") printf "  %s nix run .#bump --%s     (nix: one relock, one pass)\n", (am ? "⚠" : " "), a }
  ' "$work/proposals.tsv" | sort -r
  exit 0
fi

# The chosen names, each to its step.
: >"$work/plan.tsv"
for name in "${names[@]}"; do
  line=$(jq -c --arg n "$name" 'select(.name == $n)' "$work/report.jsonl" | head -1)
  [[ -n $line ]] || { echo "bump: no line named '$name' in the report" >&2; exit 2; }
  step=$(step_of "$line") || exit 2
  IFS=$'\t' read -r kind where arg <<<"$step"
  if [[ $(jq -r .source <<<"$line") == rke2lab:flake.lock#nixpkgs ]]; then
    with=$(jq -r 'select(.source == "rke2lab:flake.lock#nixpkgs") | .name' "$work/report.jsonl" | grep -vx "$name" | tr '\n' ' ')
    echo "bump: '$name' is not pinned by us: it moves with nixpkgs-unstable, and with it: ${with:-nothing else}" >&2
  fi
  [[ $(jq -r .status <<<"$line") == current ]] && echo "bump: '$name' is already current; nothing to play" >&2
  printf '%s\t%s\t%s\t%s\n' "$kind" "$where" "$arg" "$name" >>"$work/plan.tsv"
done

# Fold the steps: every nfc input in ONE relock and one pass; a head rides that pass (or makes one of its own); each
# other line is its own step. A before B, in that order: nfc, pass, here, then the code lines.
awk -F'\t' '
  $2 == "nfc"  { if (!(($3) in nfcIn)) { nfcIn[$3] = 1; nfcArgs = nfcArgs " " $3 }; nfcNames = nfcNames " " $4; next }
  $2 == "pass" { passNames = passNames " " $4; next }
                 { rest[++n] = $0 }
  END {
    if (nfcArgs != "")
      printf "A\tnfc\t%s\t%s\trelock%s in nix-flake-commons, then one pass\n", substr(nfcArgs, 2), substr(nfcNames, 2), nfcArgs
    if (passNames != "")
      printf "A\tpass\t-\t%s\t%s\n", substr(passNames, 2), (nfcArgs != "" ? "moves with that pass" : "one pass from nix-flake-commons")
    for (i = 1; i <= n; i++) {
      split(rest[i], f, "\t")
      if (f[2] == "here") printf "A\there\t%s\t%s\trelock %s\n", f[3], f[4], f[3]
    }
    for (i = 1; i <= n; i++) {
      split(rest[i], f, "\t")
      if (f[2] == "overlay")   printf "B\toverlay\t%s\t%s\tthe overlay to %s\n", f[3], f[4], f[3]
      if (f[2] == "component") printf "B\tcomponent\t%s\t%s\tmanifests versions apply=true component=%s\n", f[3], f[4], f[3]
    }
  }
' "$work/plan.tsv" | awk -F'\t' '!seen[$1 FS $2 FS $3 FS $4]++' >"$work/ordered.tsv"

echo "bump plan, from $branch:"
awk -F'\t' '{ printf "  %d · %s  %-28s %s\n", NR, $1, $4, $5 }' "$work/ordered.tsv"
echo "  A = a nix lock edge, pushed by relock's pass in the graph's order · B = a commit on $branch, for the merge"
((plan_only)) && exit 0

case $branch in develop | main | HEAD) echo "bump: on $branch; play from a feature branch" >&2; exit 2 ;; esac
[[ -z $(git status --porcelain) ]] || { echo "bump: the worktree has changes; commit or set them aside first" >&2; exit 2; }

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
# Whether an nfc step runs: a head then rides its pass instead of making one. Read once, not inside the loop below.
has_nfc=0
grep -q $'^A\tnfc\t' "$work/ordered.tsv" && has_nfc=1
while IFS=$'\t' read -r kind where arg who what; do
  echo "== $kind $who: $what"
  case $where in
    nfc)
      # shellcheck disable=SC2086 # the inputs, one word each
      if "${BUMP_NFC_RELOCK:-@nfcRelock@}" $arg; then echo "A	$who	$what" >>"$work/done.tsv"
      else echo "A	$who	the nix-flake-commons relock or its pass failed" >>"$work/dropped.tsv"; fi
      ;;
    pass)
      if ((has_nfc)); then
        echo "A	$who	moved with the pass" >>"$work/done.tsv"
      elif "${BUMP_NFC_RELOCK:-@nfcRelock@}"; then echo "A	$who	$what" >>"$work/done.tsv"
      else echo "A	$who	the pass failed" >>"$work/dropped.tsv"; fi
      ;;
    here)
      if "${BUMP_RELOCK:-@relock@}" "$arg"; then echo "A	$who	$what" >>"$work/done.tsv"
      else echo "A	$who	relock $arg failed" >>"$work/dropped.tsv"; fi
      ;;
    component | overlay)
      before=$(git rev-parse HEAD)
      if [[ $where == component ]]; then
        "${BUMP_MANIFESTS:-@manifests@}" versions apply=true level=major component="$arg" || true
      else
        "${BUMP_OVERLAY:-@overlay@}" "$arg" || true
      fi
      if [[ $(git rev-parse HEAD) == "$before" ]]; then
        echo "B	$who	nothing was committed" >>"$work/dropped.tsv"
      elif [[ $(git rev-parse --abbrev-ref HEAD) != "$branch" ]]; then
        echo "bump: the commit for $who left $branch; stopping" >&2; exit 1
      elif why=$(checks "$(git rev-parse HEAD)"); then
        echo "B	$who	$(git log -1 --format=%h) $what" >>"$work/done.tsv"
      else
        git reset -q --hard "$before"
        echo "B	$who	red: $why; its commit dropped" >>"$work/dropped.tsv"
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
