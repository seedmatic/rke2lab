#!/usr/bin/env bash
# bump's nix-flake-commons step (kind A): relock the chosen inputs of nfc in ONE relock (one commit), then play ONE
# pass downstream, so every head that follows nfc relocks it, in the graph's order, each through its own pre-push
# gate. With no input, only the pass: it carries the heads' edges (an input of a repo that is a head of the graph).
#
#   nfc-relock [<input>…]       NFC_WORKTREE names nfc's develop checkout (the pass must start on its own branch)
#
# relock refuses a pass from any other branch than the head's; nothing here works around it.
set -euo pipefail
nfc=${NFC_WORKTREE:?NFC_WORKTREE must name the develop checkout of nix-flake-commons}
[[ -d $nfc/.git || -f $nfc/.git ]] || { echo "nfc-relock: $nfc is no git checkout" >&2; exit 2; }
if (($# > 0)); then
  rev=$(git -C "$nfc" rev-parse HEAD)
  (cd "$nfc" && @nix@ run "git+file://$nfc?rev=$rev#relock" -- "$@")
fi
rev=$(git -C "$nfc" rev-parse HEAD)
(cd "$nfc" && @nix@ run "git+file://$nfc?rev=$rev#relock" -- --downstream)
