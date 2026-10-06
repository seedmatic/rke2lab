# shellcheck shell=bash
# relock — ONE implementation, shared by every repo in the chain. See
# docs/architecture/patterns/flake-lock-propagation.adoc; built by `lib.mkRelockApp`.
#
# Build-time tokens (written WITHOUT at-sigils here so replaceVars does not substitute them in this
# comment): repoName, repoSlug, repoUrl, system, consumers, ownedArtifacts, pushFirstBranch,
# catalogBranch, selfPinName.
#
# ★ WHY the name is identical in every repo: propagation is a REQUEST, and the caller must know
# nothing about the callee beyond its name — that uniformity is what makes `nix run <any repo>#relock`
# possible. The same reasoning applies one level down, to the implementation: if each repo wrote its
# own, "the same rule everywhere" would be a claim nobody could check. So the rule lives once and each
# repo supplies only what is ITS OWN — its url, its artifacts, its branches, its consumers.

downstream=0
targets=()
for a in "$@"; do
  case $a in
    --downstream) downstream=1 ;;
    -h|--help)
      cat <<'USAGE'
relock [--downstream] [target...]

Reconcile this repo's derived, committed artifacts. No target = ALL of them.

  inputs            every flake input          -> flake.lock
  <input-name>      one input (ndh, flox-controller, flox-runtime, …)
  artifacts         every regen-* app this repo exposes (discovered)
  plans             regen-dataplan
  netplan           regen-blueprint
  regen-<name>      one regen app by name
  envs              every flox env             -> flox-catalog */manifest.lock
  envs:<id>         one env (cluster-api/seed-incluster)
  catalog         the flox-catalog branch's rke2lab pin

An input bump that moves no exported derivation is DROPPED, not carried.
--downstream then REQUESTS each declared consumer's own relock.
USAGE
      exit 0 ;;
    -*) echo "relock: unknown flag '$a' (try --help)" >&2; exit 2 ;;
    *) targets+=("$a") ;;
  esac
done

# WHOSE repo this reconciles — its OWN, always, and resolved rather than assumed.
#
# ⚠️ The contract is `nix run <repo>#relock` from ANY directory. When rke2lab's relock requests ours,
# the CWD is RKE2LAB's worktree, so a bare `git rev-parse --show-toplevel` would hand us the wrong
# repo and relock it with our rules — silently, and reporting success. So the CWD counts only if it is
# a checkout of THIS repo; otherwise we obtain one of our own.
#
# Matching is on the SLUG (`owner/name`), not the url: a local checkout may speak ssh where the input
# speaks https, and the same repo must not read as a different one because of the transport.
cur=""
RKE=""
if top=$(git rev-parse --show-toplevel 2>/dev/null); then
  origin=$(git -C "$top" remote get-url origin 2>/dev/null || true)
  origin=${origin%.git}
  case "$origin" in
    *"@repoSlug@") RKE=$top ;;
  esac
fi
if [ -n "$RKE" ]; then
  cur=$(git -C "$RKE" rev-parse --abbrev-ref HEAD)
  echo "relock(@repoName@): reconciling the checkout at $RKE ($cur)"
else
  # A REQUESTED run, from somewhere that is not our checkout. We clone, reconcile and PUSH: the chain
  # is push-gated anyway (a `github:` input only ever sees what is pushed), so the remote is the only
  # place a request can usefully land. The operator's own checkout stays untouched and simply pulls.
  RKE=$(mktemp -d)/@repoName@
  echo "relock(@repoName@): not inside this repo — cloning @repoUrl@ to reconcile and push"
  git clone --quiet "@repoUrl@" "$RKE" || { echo "relock: cannot clone @repoUrl@" >&2; exit 1; }
  cur=$(git -C "$RKE" rev-parse --abbrev-ref HEAD)
  echo "relock(@repoName@): cloned at $cur"
fi

# The registry that resolves a repo's INDIRECT inputs, pinned to its COMMITTED file — by a CLI
# flag rather than NIX_CONFIG. Measured 2026-10-06: `--flake-registry` beats a NIX_CONFIG aimed
# elsewhere, and leaving NIX_CONFIG alone matters because that is where access-tokens for the
# private inputs live.
#
# Why pin at all: an operator's shell aims this setting at their own gitignored
# flake-registry.local.json, so without the pin we would re-lock through THEIR local re-aim and
# commit the result — a lock that resolves on their machine and nowhere else. The clone path makes
# it worse, not better: a fresh clone has no local file, so the ambient one would be the only one.
#
# A repo that has not moved to indirect inputs carries no such file: nothing to pin, and the
# local-lock guard in relock_input covers it regardless. Uniform across the chain either way.
registry_flag=()
set_registry_flag() { # $1 checkout dir
  registry_flag=()
  if [ -f "$1/flake-registry.json" ]; then
    registry_flag=(--flake-registry "$1/flake-registry.json")
  fi
}
set_registry_flag "$RKE"

# The per-input comparison attributes a derivation change to the input just bumped, so
# any OTHER uncommitted edit would be credited to it. Refuse rather than mislead.
dirty=$(git -C "$RKE" status --porcelain -- . ':!flake.lock' @ownedArtifacts@)
if [ -n "$dirty" ]; then
  echo "REFUSING: the worktree carries changes beyond the artifacts relock owns, so a" >&2
  echo "derivation change could not be attributed. Commit or set them aside:" >&2
  printf '%s\n' "$dirty" >&2
  exit 1
fi

wt_for_branch() {
  local want=$1 path="" br=""
  while IFS= read -r line; do
    case $line in
      "worktree "*) path=${line#worktree } ;;
      "branch refs/heads/"*)
        br=${line#branch refs/heads/}
        [ "$br" = "$want" ] && { printf '%s\n' "$path"; return 0; } ;;
    esac
  done < <(git -C "$RKE" worktree list --porcelain)
  return 1
}

lockrev() { # $1 flake.lock  $2 root-input name -> resolved node rev
  # shellcheck disable=SC2016  # $i/$n/$nn are jq vars, not shell
  jq -r --arg i "$2" '
    .nodes.root.inputs[$i] as $n
    | (if ($n|type)=="array" then $n[-1] else $n end) as $nn
    | .nodes[$nn].locked.rev // empty' "$1"
}

# The MEANINGFUL projection of a flake edge: every exported package's derivation path.
# NOT a projection of the lock's fields — in a flake.lock `locked.rev` IS the content
# identity, so deleting it would make every bump compare equal and look impact-free.
evalmap() {
  nix eval --json "$RKE#packages.@system@" \
    --apply 'ps: builtins.mapAttrs (_: p: if p ? drvPath then p.drvPath else null) ps' \
    | jq -S .
}

all_inputs() { jq -r '.nodes.root.inputs | keys[]' "$RKE/flake.lock"; }

# Every `regen-*` app this flake exposes — DISCOVERED, not listed. A repo's generated
# artifacts are whatever its regen apps write, and it already declares those as apps; making
# a caller re-list them (app AND filename) is the same "enumerate what you could derive" the
# cluster set was cured of. It also means a new regen app is covered the day it lands.
regen_apps() {
  nix eval --json "$RKE#apps.@system@" --apply 'as: builtins.attrNames as' 2>/dev/null \
    | jq -r '.[] | select(startswith("regen-"))'
}

# Re-derive and commit only what MOVED. No eval needed and no filename needed: the regen
# writes whatever it writes, and git reports it.
regen_artifact() { # $1 app
  printf '  %-18s ' "$1"
  if ! ( cd "$RKE" && nix run ".#$1" ) >/dev/null 2>&1; then
    echo "FAILED (nix run .#$1)"
    return 1
  fi
  local -a moved=()
  mapfile -t moved < <(git -C "$RKE" diff --name-only)
  if [ "${#moved[@]}" -eq 0 ]; then
    echo "already current"
  else
    git -C "$RKE" commit -q -m "chore(relock): regen via $1" -- "${moved[@]}"
    committed=1
    echo "REGENERATED — ${moved[*]} was stale"
  fi
}

relock_input() { # $1 input name
  printf '  %-18s ' "$1"
  if ! ( cd "$RKE" && nix "${registry_flag[@]}" flake update "$1" --refresh ) >/dev/null 2>&1; then
    echo "FAILED to resolve"
    return 1
  fi
  if git -C "$RKE" diff --quiet -- flake.lock; then
    echo "already current"
    return 0
  fi
  # A lock has to be fetchable by everyone, not only by whoever ran this. The ONLY way a local
  # revision can enter one is a path- or file-typed ref: a `github:` fetch physically cannot see an
  # unpushed commit, which is what makes the chain push-gated to begin with. Measured 2026-10-06 —
  # re-locking ndh's `rke2lab` input resolved the PUSHED head while the local checkout sat two
  # commits ahead of it. So this single check IS the invariant; probing "is the rev on the remote"
  # as well would be vacuous.
  local locked_at
  locked_at=$(jq -r --arg i "$1" '
    (.nodes.root.inputs[$i]) as $n
    | if ($n | type) != "string" then ""
      else
        .nodes[$n].locked
        | if .type == "path" then "path:" + (.path // "")
          elif ((.url // "") | test("^(file|git\\+file)://")) then .url
          else "" end
      end' "$RKE/flake.lock")
  if [ -n "$locked_at" ]; then
    git -C "$RKE" checkout -q -- flake.lock
    echo "LOCAL lock REFUSED -> $locked_at"
    echo "relock: that revision resolves only on this machine, so the lock would be unfetchable" >&2
    echo "        for every other consumer. Aim the registry at a pushed ref — the committed" >&2
    echo "        flake-registry.json, not a flake-registry.local.json — and run again." >&2
    return 1
  fi
  local after
  after=$(evalmap)
  if [ "$after" = "$baseline" ]; then
    # A lock is a statement about outputs: carrying this adds nothing and would keep the
    # rke2lab <-> ndh cycle turning.
    git -C "$RKE" checkout -q -- flake.lock
    echo "moved, NO derivation impact -> dropped"
  else
    git -C "$RKE" commit -q -m "chore(flake): relock $1" -- flake.lock
    baseline=$after
    committed=1
    echo "BUMPED — derivations moved"
  fi
}

# Orphan-branch hops are OPTIONAL — a repo with none simply skips them. NOT a parameter: a
# repo either carries such a branch or it does not, and `git worktree list` already answers
# that. This is what lets the same implementation serve a repo like ndh, which has neither a
# seed-incluster nor a flox-catalog branch.
# Via variables, not the tokens inline: after substitution a token IS a literal, and shellcheck
# rejects `[ -n "literal" ]` (SC2157) — correctly, since the test would be constant.
pushFirstBranch="@pushFirstBranch@"
catalogBranch="@catalogBranch@"
SIC=""
if [ -n "$pushFirstBranch" ]; then SIC=$(wt_for_branch "$pushFirstBranch") || SIC=""; fi
CAT=""
if [ -n "$catalogBranch" ]; then CAT=$(wt_for_branch "$catalogBranch") || CAT=""; fi

if [ -n "$CAT" ]; then
  cat_ref=$(jq -r --arg p "@selfPinName@" '.nodes[$p].original.ref // empty' "$CAT/flake.lock")
  if [ -n "$cat_ref" ] && [ "$cat_ref" != "$cur" ]; then
    echo "MISMATCH: @catalogBranch@ tracks @selfPinName@@$cat_ref but this worktree is on '$cur' —" >&2
    echo "propagation would not reach the catalog. Stand on '$cat_ref' (or repoint the catalog)." >&2
    exit 1
  fi
fi

# No target = everything, in dependency order: artifacts first (they can move the
# derivations the input guard compares against), then inputs, then the catalog.
if [ "${#targets[@]}" -eq 0 ]; then
  targets=(artifacts inputs envs catalog)
fi

echo "worktrees:"
echo "  @repoName@ ($cur) : $RKE"
echo "  seed-incluster : $SIC"
echo "  flox-catalog : $CAT"
echo "targets: ${targets[*]}"
echo

# Our own branches first: an orphan-branch INPUT resolves github:, which sees only what is
# pushed.
if [ -n "$SIC" ]; then
  echo "== own branches: push before resolving =="
  git -C "$SIC" push origin seed-incluster
  echo "  seed-incluster @ $(git -C "$SIC" rev-parse --short=9 HEAD) pushed"
  echo
fi

baseline=$(evalmap)
do_catalog=0
env_targets=()
committed=0
for t in "${targets[@]}"; do
  case $t in
    # Every generated artifact this repo knows how to re-derive. `plans` and `netplan` stay as
    # the OPERATOR's words for two of them — naming two aliases is not the same as making a
    # caller enumerate an app AND a filename.
    artifacts)
      echo "== artifacts (every regen-* app) =="
      while read -r app; do regen_artifact "$app" || true; done < <(regen_apps)
      echo ;;
    plans)    echo "== plans =="   ; regen_artifact regen-dataplan  || true ; echo ;;
    netplan)  echo "== netplan ==" ; regen_artifact regen-blueprint || true ; echo ;;
    regen-*)  echo "== $t =="      ; regen_artifact "$t"            || true ; echo ;;
    inputs)
      echo "== inputs =="
      while read -r i; do relock_input "$i" || true; done < <(all_inputs)
      echo ;;
    # ⚠️ The env locks are NOT taken here. They resolve `path:../../..#<attr>` against the
    # CATALOG's flake, so locking before its rke2lab pin moves records the OLD derivation
    # and then reports "unchanged (churn dropped)" — a sincere answer to a question asked too
    # early. Measured 2026-09-30: cluster-api/seed-incluster reported unchanged, the pin then
    # advanced, and the env kept pinning the previous controller binary, so the node would
    # never have realised it. Re-locking the SAME env after the bump reported BUMPED and the
    # drv changed. So the targets only RECORD what to lock; the catalog hop does it, after.
    # Naming an env is for a SURGICAL act only. Normally you do not: any change committed to
    # rke2lab implies the catalog must be re-pinned and the envs re-locked, because the
    # envs resolve `path:../../..#<attr>` THROUGH the catalog's flake. Requiring the
    # operator to pair `relock seed-incluster envs:cluster-api/seed-incluster` made them
    # supply a dependency relation they should not have to know — and let them name the
    # wrong env, or forget it. The derivation guard drops the envs that did not move, so
    # re-locking all of them is both cheap and correct.
    envs) env_targets+=("") ; do_catalog=1 ;;
    envs:*) env_targets+=("${t#envs:}") ; do_catalog=1 ;;
    catalog) do_catalog=1 ;;
    *)
      if all_inputs | grep -qx -- "$t"; then
        echo "== input $t =="
        relock_input "$t" || true
        echo
      else
        echo "relock: unknown target '$t' (try --help)" >&2
        exit 2
      fi ;;
  esac
done

# Anything committed here must TRAVEL: the catalog pins rke2lab and the envs resolve
# through it, so a change that stops at this repo is a change the nodes never see. The
# operator therefore never has to pair a target with its env — see the note at the env
# targets.
if [ "$committed" = 1 ] && [ "$do_catalog" = 0 ] && [ -n "$CAT" ]; then
  do_catalog=1
  env_targets=("")
  echo "  (committed here ⇒ re-pinning the catalog and re-locking every env)"
  echo
fi

# Push whatever the artifacts did. The catalog hop resolves
# github:seedmatic/rke2lab/<branch> and therefore pins what the REMOTE answers, not this
# worktree — so pushing only on a change was the hole: any other commit left HEAD
# unpushed and the catalog silently pinned an older rev while reporting a clean bump
# (measured 2026-09-30: catalog at 9ccd89923 while HEAD was fec07de85).
git -C "$RKE" push
rke_head=$(git -C "$RKE" rev-parse HEAD)
echo "  @repoName@ @ ${rke_head:0:9} pushed"
echo

# ⚠️ `-n "$CAT"` is load-bearing, and its absence was a latent defect: the header claims this
# implementation serves a repo with no such branch, yet an unguarded hop would `lockrev "/flake.lock"`
# and die on exactly that repo. A repo without a catalog branch simply has nothing to re-pin.
if [ "$do_catalog" = 1 ] && [ -n "$CAT" ]; then
  echo "== own branch @catalogBranch@: pin @selfPinName@ =="
  rke_before=$(lockrev "$CAT/flake.lock" @selfPinName@)
  # The catalog branch is a different checkout with its own committed registry, so the pin is
  # re-resolved for it rather than inherited from the main one.
  set_registry_flag "$CAT"
  ( cd "$CAT" && nix "${registry_flag[@]}" flake update @selfPinName@ --refresh )
  rke_after=$(lockrev "$CAT/flake.lock" @selfPinName@)
  if [ "$rke_before" != "$rke_after" ]; then
    git -C "$CAT" commit -q -m "chore(flake): relock @selfPinName@ -> ${rke_after:0:9}" -- flake.lock
    echo "  pinned rke2lab ${rke_before:0:9} -> ${rke_after:0:9}"
  else
    echo "  already pinning rke2lab ${rke_after:0:9}"
  fi
  # NOW the envs, with the pin already moved — see the note at the env targets above.
  # lock-envs applies the same guard one level down: it re-locks and commits only real
  # derivation bumps, dropping locked-url churn.
  for e in "${env_targets[@]}"; do
    if [ -z "$e" ]; then
      ( cd "$CAT" && nix run .#lock-envs )
    else
      ( cd "$CAT" && nix run .#lock-envs -- "$e" )
    fi
  done
  # ASSERT the landing rather than trust the bump report: a lagging push, a stale
  # --refresh cache or a catalog tracking another branch all end here quietly on a rev
  # that is not what this run built. The point is to make ONE revision travel — prove it.
  if [ "$rke_after" != "$rke_head" ]; then
    echo "MISMATCH: the catalog pinned rke2lab ${rke_after:0:9} but this run pushed ${rke_head:0:9} —" >&2
    echo "the propagation did NOT carry this revision. Check that '$cur' is the branch the" >&2
    echo "catalog tracks and that the push above reached the remote." >&2
    exit 1
  fi
  echo "  verified: catalog pins rke2lab ${rke_head:0:9} — the revision this run pushed"
  ahead=$(git -C "$CAT" rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)
  if [ "${ahead:-0}" -gt 0 ] 2>/dev/null; then
    git -C "$CAT" push
    echo "  flox-catalog pushed $ahead commit(s) — FloxCatalog syncs, FloxEnvs re-realize"
  else
    echo "  flox-catalog already up to date"
  fi
  echo
fi

# Crossing a repo boundary is a REQUEST, never a reach-in: we do not edit a consumer's
# lock, we run the consumer's OWN relock. Off by default — it mutates another repo.
consumers=(@consumers@)
if [ "$downstream" = 1 ]; then
  echo "== downstream: request each consumer's own relock =="
  for c in "${consumers[@]}"; do
    printf '  %-28s ' "$c"
    if nix run "$c#relock" 2>/dev/null; then
      echo "  ^ done"
    else
      echo "exposes no #relock yet — skipped (that app is THAT repo's to add)"
    fi
  done
else
  echo "consumers NOT notified (pass --downstream): ${consumers[*]}"
fi
echo "DONE"
