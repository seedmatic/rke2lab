#!/usr/bin/env bats
#
# pre-push's skip of the plan's build (plan-closure.sh), in a throwaway repository with an origin/develop: a push owes
# the ~1 h build only when it touches the plan's Maven closure or moves the fabric-plan pin, and every doubt builds.

bats_require_minimum_version 1.5.0

lock() { # $1 fabric-plan rev, $2 another input's rev
  printf '{"nodes":{"fabric-plan":{"locked":{"rev":"%s"}},"nixpkgs":{"locked":{"rev":"%s"}}}}\n' "$1" "$2" >flake.lock
}
commit() { git add -A && git commit -qm "$1" && git rev-parse HEAD; }

setup() {
  . "$BATS_TEST_DIRNAME/../plan-closure.sh"
  export HOME="$BATS_TEST_TMPDIR" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
  export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
  cd "$BATS_TEST_TMPDIR" && git init -q -b develop repo && cd repo
  mkdir -p osgi/domains/x/src exec/plan-cli docs
  echo a >osgi/domains/x/src/A.java && echo '{}' >flake.nix && lock p1 n1
  base=$(commit base)
  git update-ref refs/remotes/origin/develop "$base"
  zero=0000000000000000000000000000000000000000
}

@test "a lock-only push that leaves fabric-plan alone skips" {
  lock p1 n2 && head=$(commit "nix bump")
  run plan_build_skippable "$head" "$base"
  [ "$status" -eq 0 ] && [[ "$output" == *"skipped"* ]]
}
@test "a flake.nix and docs push skips" {
  echo '{ x = 1; }' >flake.nix && echo d >docs/a.md && head=$(commit docs)
  run plan_build_skippable "$head" "$base"
  [ "$status" -eq 0 ]
}
@test "a .java in the closure builds" {
  echo b >osgi/domains/x/src/A.java && head=$(commit java)
  run plan_build_skippable "$head" "$base"
  [ "$status" -eq 1 ] && [[ "$output" == *"touches its Maven closure"* ]]
}
@test "a lock-only push that moves the fabric-plan pin builds" {
  lock p2 n1 && head=$(commit "relock fabric-plan")
  run plan_build_skippable "$head" "$base"
  [ "$status" -eq 1 ] && [[ "$output" == *"moves the fabric-plan pin"* ]]
}
@test "the root pom, .mvn and the flox env are in the closure" {
  for p in pom.xml .mvn/maven.config .flox/env/manifest.toml; do
    git reset -q --hard "$base"; mkdir -p "$(dirname "$p")"; echo x >"$p"; head=$(commit "$p")
    run plan_build_skippable "$head" "$base"
    [ "$status" -eq 1 ] || { echo "skipped for $p"; false; }
  done
}
@test "a remote head unknown here builds" {
  lock p1 n2 && head=$(commit bump)
  run plan_build_skippable "$head" 1111111111111111111111111111111111111111
  [ "$status" -eq 1 ] && [[ "$output" == *"no known base"* ]]
}
@test "a remote head that is not an ancestor (a forced push) builds" {
  git checkout -q -b other && echo o >docs/o.md && other=$(commit other)
  git checkout -q develop && lock p1 n2 && head=$(commit bump)
  run plan_build_skippable "$head" "$other"
  [ "$status" -eq 1 ]
}
@test "a new branch is judged from its merge-base with origin/develop" {
  lock p1 n2 && head=$(commit bump)
  run plan_build_skippable "$head" "$zero"
  [ "$status" -eq 0 ] && [[ "$output" == *"${base:0:12}..${head:0:12}"* ]]
}
@test "a new branch with no origin/develop builds" {
  git update-ref -d refs/remotes/origin/develop
  lock p1 n2 && head=$(commit bump)
  run plan_build_skippable "$head" "$zero"
  [ "$status" -eq 1 ]
}
