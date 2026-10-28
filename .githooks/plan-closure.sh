# Sourced by pre-push: whether a push can skip the plan's build. The build (~1 h, a cold archive, no build cache)
# judges a pin against what the PUSHED sources produce, so it is only owed when the push changes what the plan reads:
# a path in the Maven closure of :plan-cli, or the fabric-plan pin itself. Fail closed: every doubt builds.

# The closure by path prefix, coarser than :plan-cli's reactor (42 modules) so that a new module cannot slip out of it.
PLAN_CLOSURE_RE='^(osgi/|host/|exec/plan-cli/|build-parent/|pom\.xml$|\.mvn/|mvnw$|\.flox/|\.flox-envs\.d/)'

# The base the push is judged against: the remote's old head when it is ours and an ancestor, else the merge-base with
# origin/develop for a new branch; nothing when neither is known, so the caller builds.
plan_base() { # $1 local sha, $2 remote sha
  local zero=0000000000000000000000000000000000000000
  if [[ "$2" != "$zero" ]]; then
    git cat-file -e "$2^{commit}" 2>/dev/null && git merge-base --is-ancestor "$2" "$1" 2>/dev/null && echo "$2"
    return 0
  fi
  git merge-base "$1" refs/remotes/origin/develop 2>/dev/null || true
}

fabric_plan_rev() { # $1 commit
  git show "$1:flake.lock" 2>/dev/null | yq -p=json '.nodes["fabric-plan"].locked.rev // ""' 2>/dev/null
}

# True (0) when the push from $2 to $1 owes no build: a base is known, no changed path is in the closure, and the
# fabric-plan pin did not move. Prints the reason either way, one line.
plan_build_skippable() { # $1 local sha, $2 remote sha
  local base changed
  base="$(plan_base "$1" "$2")"
  if [[ -z "$base" ]]; then
    echo "pre-push: :plan-cli builds — no known base for ${1:0:12}" >&2
    return 1
  fi
  if ! changed="$(git diff --name-only "$base" "$1" 2>/dev/null)"; then
    echo "pre-push: :plan-cli builds — ${base:0:12}..${1:0:12} cannot be diffed" >&2
    return 1
  fi
  if grep -Eq "$PLAN_CLOSURE_RE" <<<"$changed"; then
    echo "pre-push: :plan-cli builds — ${base:0:12}..${1:0:12} touches its Maven closure" >&2
    return 1
  fi
  if [[ "$(fabric_plan_rev "$base")" != "$(fabric_plan_rev "$1")" ]]; then
    echo "pre-push: :plan-cli builds — ${base:0:12}..${1:0:12} moves the fabric-plan pin" >&2
    return 1
  fi
  echo "pre-push: :plan-cli skipped — no closure path and no fabric-plan pin change in ${base:0:12}..${1:0:12}" >&2
  return 0
}
