#!/usr/bin/env bats
#
# The rke2lab-grow-profile gate, as built (the same factory the #grow app runs), in a throwaway git repository
# whose commits carry the stack files. The profile comes from HEAD, never from the working tree, and only when
# RKE2LAB_GROW_PROFILE agrees: each refusal is checked by its status and its message, each acceptance by what
# it prints.

bats_require_minimum_version 1.5.0

fleet() { printf 'config:\n  rke2lab:cluster:\n    host: bioskop\n'; }
throwaway() { printf 'config:\n  rke2lab:cluster:\n    host: nikopol\n    profile: throwaway\n'; }

setup() {
  cd "$BATS_TEST_TMPDIR"
  git init -q repo
  cd repo
  git config user.email test@example.invalid
  git config user.name test
  git config commit.gpgsign false
  fleet >Pulumi.dev.yaml
  throwaway >Pulumi.nikopol.yaml
  printf 'config: [ unclosed\n' >Pulumi.broken.yaml
  git add Pulumi.dev.yaml Pulumi.nikopol.yaml Pulumi.broken.yaml
  git commit -q -m stacks
}

gate() { run --separate-stderr "$GATE" "$@"; }

@test "a fleet stack with no marker is the fleet profile" {
  gate dev
  [ "$status" -eq 0 ]
  [ "$output" = fleet ]
}

@test "a committed throwaway stack with its marker is the throwaway profile" {
  RKE2LAB_GROW_PROFILE=throwaway gate nikopol
  [ "$status" -eq 0 ]
  [ "$output" = throwaway ]
}

@test "a throwaway stack without the marker is refused: the fleet #grow cannot grow it" {
  gate nikopol
  [ "$status" -eq 2 ]
  [[ $stderr == *"is a throwaway one"* ]]
}

@test "a throwaway stack with another marker value is refused" {
  RKE2LAB_GROW_PROFILE=fleet gate nikopol
  [ "$status" -eq 2 ]
  [[ $stderr == *"is a throwaway one"* ]]
}

@test "a stray marker on a fleet stack is refused: it cannot skip the fleet's identity" {
  RKE2LAB_GROW_PROFILE=throwaway gate dev
  [ "$status" -eq 2 ]
  [[ $stderr == *"is not a throwaway stack"* ]]
}

@test "a marker for a stack with no committed file is refused" {
  RKE2LAB_GROW_PROFILE=throwaway gate nowhere
  [ "$status" -eq 2 ]
  [[ $stderr == *"is not a throwaway stack"* ]]
}

@test "a working copy that says throwaway is not enough: HEAD decides" {
  throwaway >Pulumi.dev.yaml
  RKE2LAB_GROW_PROFILE=throwaway gate dev
  [ "$status" -eq 2 ]
  [[ $stderr == *"is not a throwaway stack"* ]]
}

@test "HEAD says throwaway but the working copy differs: refused" {
  fleet >Pulumi.nikopol.yaml
  RKE2LAB_GROW_PROFILE=throwaway gate nikopol
  [ "$status" -eq 2 ]
  [[ $stderr == *"differs from HEAD"* ]]
}

@test "a committed stack file yq cannot read stops the grow, with or without a marker" {
  gate broken
  [ "$status" -eq 2 ]
  [[ $stderr == *"cannot be read"* ]]
  RKE2LAB_GROW_PROFILE=throwaway gate broken
  [ "$status" -eq 2 ]
  [[ $stderr == *"cannot be read"* ]]
}

@test "a stack name outside [a-z][a-z0-9-]* is refused before any git read" {
  for name in ../dev -dev Dev 'dev;x' 'a b' 9dev; do
    gate "$name"
    [ "$status" -eq 2 ]
  done
  gate ../dev
  [[ $stderr == *"is not a stack name"* ]]
}

@test "no stack at all is refused with the usage" {
  gate
  [ "$status" -ne 0 ]
  [[ $stderr == *"usage: rke2lab-grow-profile <stack>"* ]]
}
