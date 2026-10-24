#!/usr/bin/env bats
#
# rke2lab-throwaway as built, against mocked grow, pulumi, incus and kubectl over files in $m, and the real
# rke2lab-grow-profile gate in a throwaway git repository whose commit carries the stack files. The tty is a file.
# Each refusal is checked by its status, its message and what it left undone; the reset's order by the calls' log.

bats_require_minimum_version 1.5.0

secret='client-key-data: S0VZLU5FVkVSLVBSSU5URUQ='

setup() {
  export MOCK="$BATS_TEST_TMPDIR/mock"
  m=$MOCK
  mkdir -p "$m/bin"
  for tool in grow pulumi incus kubectl; do
    printf '#!%s\n' "$BASH" | cat - "$BATS_TEST_DIRNAME/mocks/$tool.sh" >"$m/bin/$tool"
    chmod +x "$m/bin/$tool"
  done
  : "the command as built, its placeholder pointed at this test's directory"
  THROWAWAY="$m/rke2lab-throwaway"
  sed "s|@M@|$m|g" "$THROWAWAY_BUILT" >"$THROWAWAY"
  chmod +x "$THROWAWAY"
  ! grep -q '@M@' "$THROWAWAY"
  cd "$BATS_TEST_TMPDIR"
  git init -q repo && cd repo
  git config user.email test@example.invalid
  git config user.name test
  git config commit.gpgsign false
  printf 'name: rke2lab\n' >Pulumi.yaml
  printf 'config:\n  rke2lab:cluster:\n    host: bioskop\n' >Pulumi.dev.yaml
  printf 'config:\n  rke2lab:cluster:\n    host: nikopol\n    profile: throwaway\n' >Pulumi.nikopol.yaml
  git add Pulumi.yaml Pulumi.dev.yaml Pulumi.nikopol.yaml && git commit -q -m stacks
  printf '{"throwawayNode":"nikopol-mgmt-master","throwawayIncusProject":"rke2lab-test","throwawayApiServer":"https://nikopol-mgmt-master.nikopol:6443"}\n' >"$m/outputs.json"
  printf 'apiVersion: v1\nclusters:\n- cluster:\n    server: https://127.0.0.1:6443\n  name: default\nusers:\n- name: default\n  user:\n    %s\n' "$secret" >"$m/rke2.yaml"
}

tw() { # <tty answer> <args>...
  printf '%s\n' "$1" >"$m/tty"
  shift
  run --separate-stderr "$THROWAWAY" "$@"
}

grown() { touch "$m/stack" "$m/instance" "$m/project"; printf 'aaa\nbbb\n' >"$m/images"; }

@test "up: preview, « oui », up, then the kubeconfig in its own 0600 file, its server rewritten, ready" {
  tw oui up nikopol
  [ "$status" -eq 0 ]
  [ "$(sed -n 1p "$m/calls")" = "grow nikopol preview profile=throwaway" ]
  grep -qx "grow nikopol up profile=throwaway" "$m/calls"
  [ "$(stat -c %a "$m/kube")" = 700 ]
  [ "$(stat -c %a "$m/kube/nikopol.yaml")" = 600 ]
  [ "$(yq -r '.clusters[0].cluster.server' "$m/kube/nikopol.yaml")" = https://nikopol-mgmt-master.nikopol:6443 ]
  grep -q "incus file pull --project rke2lab-test nikopol-mgmt-master/etc/rancher/rke2/rke2.yaml -" "$m/calls"
  [[ $output == *"ready: KUBECONFIG=$m/kube/nikopol.yaml"* ]]
  [ ! -e "$m/kube/.nikopol.yaml.new" ]
}

@test "up: without « oui » after the preview, nothing is grown" {
  tw non up nikopol
  [ "$status" -eq 5 ]
  [[ $output == *"nothing done"* ]]
  ! grep -q "grow nikopol up" "$m/calls"
  [ ! -e "$m/kube/nikopol.yaml" ]
}

@test "up: the kubeconfig's client key is never printed" {
  tw oui up nikopol
  [ "$status" -eq 0 ]
  [[ $output$stderr != *"S0VZLU5FVkVSLVBSSU5URUQ="* ]]
  [[ $(cat "$m/prompt") != *"S0VZLU5FVkVSLVBSSU5URUQ="* ]]
}

@test "up: a kube API that does not answer ready is a failure, loud" {
  touch "$m/fail-ready"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"does not answer ready with its certificate verified"* ]]
}

@test "up: a failed fetch leaves no kubeconfig, not even a partial one" {
  touch "$m/fail-pull"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"could not be fetched"* ]]
  [ ! -e "$m/kube/nikopol.yaml" ]
  [ ! -e "$m/kube/.nikopol.yaml.new" ]
}

@test "up: a stack exporting no node is refused before any fetch" {
  echo '{"throwawayIncusProject":"rke2lab-test","throwawayApiServer":"https://x:6443"}' >"$m/outputs.json"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"exports no throwawayNode"* ]]
  ! grep -q "incus file pull" "$m/calls"
}

@test "refused: a fleet stack, for up and for reset, before anything runs" {
  tw oui up dev
  [ "$status" -ne 0 ]
  [ ! -e "$m/calls" ]
  tw dev reset dev
  [ "$status" -ne 0 ]
  [ ! -e "$m/calls" ]
}

@test "refused: a throwaway stack whose working copy differs from HEAD" {
  printf 'config:\n  rke2lab:cluster:\n    host: bioskop\n' >Pulumi.nikopol.yaml
  tw oui up nikopol
  [ "$status" -ne 0 ]
  [ ! -e "$m/calls" ]
}

@test "refused: a name that is not a stack, a missing argument, outside the repo root" {
  tw oui up 'nikopol;rm -rf /'
  [ "$status" -eq 3 ]
  [[ $stderr == *"is not a stack name"* ]]
  tw oui up
  [ "$status" -eq 2 ]
  cd "$BATS_TEST_TMPDIR"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"repo root"* ]]
}

@test "--help prints the usage and runs nothing" {
  run --separate-stderr "$THROWAWAY" --help
  [ "$status" -eq 0 ]
  [[ $stderr == *"usage: rke2lab-throwaway up"* ]]
  [ ! -e "$m/calls" ]
}

@test "reset: the stack name typed, then destroy, images and project, stack rm, kubeconfig, in that order" {
  grown
  mkdir -p "$m/kube" && touch "$m/kube/nikopol.yaml"
  tw nikopol reset nikopol
  [ "$status" -eq 0 ]
  order=$(grep -E -o 'pulumi destroy|incus image delete|incus project delete|pulumi stack rm' "$m/calls" | uniq | paste -sd,)
  [ "$order" = "pulumi destroy,incus image delete,incus project delete,pulumi stack rm" ]
  [ ! -e "$m/kube/nikopol.yaml" ]
  [ ! -e "$m/kube/.nikopol.reset" ]
  [ ! -e "$m/stack" ] && [ ! -e "$m/project" ] && [ ! -s "$m/images" ]
}

@test "reset: « oui » is not the stack name; nothing is destroyed" {
  grown
  tw oui reset nikopol
  [ "$status" -eq 5 ]
  [[ $output == *"the name typed is not nikopol"* ]]
  ! grep -q "pulumi destroy" "$m/calls"
  [ -e "$m/instance" ]
}

@test "reset: a failed destroy stops there, says so, and a rerun resumes from the noted node" {
  grown
  mkdir -p "$m/kube" && touch "$m/kube/nikopol.yaml"
  touch "$m/fail-destroy"
  tw nikopol reset nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"step 1/4 (destroy) failed"* ]]
  ! grep -q "incus project delete" "$m/calls"
  ! grep -q "pulumi stack rm" "$m/calls"
  [ -e "$m/kube/nikopol.yaml" ]
  rm "$m/fail-destroy" "$m/calls"
  : "the outputs are gone with a half-done destroy; the noted node and project carry the rerun"
  echo '{}' >"$m/outputs.json"
  tw nikopol reset nikopol
  [ "$status" -eq 0 ]
  [[ $output == *"resuming an interrupted reset"* ]]
  [ ! -e "$m/kube/nikopol.yaml" ]
}

@test "reset: a project that cannot be deleted stops before stack rm, and up refuses until the reset is finished" {
  grown
  touch "$m/fail-project-delete"
  tw nikopol reset nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"step 2/4 failed on project rke2lab-test"* ]]
  ! grep -q "pulumi stack rm" "$m/calls"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"a reset of nikopol was interrupted"* ]]
}

@test "reset: a failed stack rm is a failure, the kubeconfig kept" {
  grown
  mkdir -p "$m/kube" && touch "$m/kube/nikopol.yaml"
  touch "$m/fail-stack-rm"
  tw nikopol reset nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"step 3/4 (stack rm) failed"* ]]
  [ -e "$m/kube/nikopol.yaml" ]
}

@test "mutant: a reset that goes on past a failed destroy is caught by the order case" {
  sed -i 's/|| die "step 1\/4 (destroy) failed[^"]*"/|| true/' "$THROWAWAY"
  ! grep -q 'step 1/4 (destroy) failed' "$THROWAWAY"
  grown
  touch "$m/fail-destroy"
  tw nikopol reset nikopol
  : "the mutant would destroy nothing and still delete the project: the instance check after step 1 stops it"
  [ "$status" -eq 3 ]
  [[ $stderr == *"step 1/4: instance nikopol-mgmt-master is still there"* ]]
  ! grep -q "incus project delete" "$m/calls"
}

@test "mutant: a kubeconfig written without umask 077 is caught" {
  sed -i 's/^    umask 077$/    umask 022/' "$THROWAWAY"
  grep -q '^    umask 022$' "$THROWAWAY"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"is not 0600"* ]]
}

@test "the script runs untraced as a whole, and reads its answer from the tty, never stdin" {
  grep -qx 'set +x' "$THROWAWAY_BUILT"
  grown
  printf 'non\n' >"$m/tty"
  run --separate-stderr bash -c '"$1" reset nikopol <<<nikopol' _ "$THROWAWAY"
  [ "$status" -eq 5 ]
  ! grep -q "pulumi destroy" "$m/calls"
}
