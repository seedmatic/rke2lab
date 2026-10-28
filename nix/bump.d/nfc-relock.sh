#!/usr/bin/env bash
# bump's nix-flake-commons mechanism (kind A): relock one of nfc's inputs, then play the pass downstream, so every
# head that follows nfc relocks it, in the graph's order, each through its own pre-push gate.
#
#   nfc-relock <input>          NFC_WORKTREE names nfc's develop checkout (the pass must start on its own branch)
#
# relock refuses a pass from any other branch than the head's; nothing here works around it.
set -euo pipefail
input=${1:?usage: nfc-relock <input>}
nfc=${NFC_WORKTREE:?NFC_WORKTREE must name the develop checkout of nix-flake-commons}
[[ -d $nfc/.git || -f $nfc/.git ]] || { echo "nfc-relock: $nfc is no git checkout" >&2; exit 2; }
rev=$(git -C "$nfc" rev-parse HEAD)
# nfc's own input first, by revision of its checkout, then the pass from it.
(cd "$nfc" && @nix@ run "git+file://$nfc?rev=$rev#relock" -- "$input")
rev=$(git -C "$nfc" rev-parse HEAD)
(cd "$nfc" && @nix@ run "git+file://$nfc?rev=$rev#relock" -- --downstream)
