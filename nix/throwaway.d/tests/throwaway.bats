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
  printf '{"throwawayNode":"nikopol-mgmt-master","throwawayIncusProject":"throwaway-nikopol","throwawayApiServer":"https://nikopol-mgmt-master.nikopol:6443"}\n' >"$m/outputs.json"
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
  [ "$(sed -n 1p "$m/calls")" = "incus list --all-projects --format csv --columns n,P" ]
  [ "$(sed -n 2p "$m/calls")" = "grow nikopol preview profile=throwaway" ]
  grep -qx "grow nikopol up profile=throwaway" "$m/calls"
  [ "$(stat -c %a "$m/kube")" = 700 ]
  [ "$(stat -c %a "$m/kube/nikopol.yaml")" = 600 ]
  [ "$(yq -r '.clusters[0].cluster.server' "$m/kube/nikopol.yaml")" = https://nikopol-mgmt-master.nikopol:6443 ]
  grep -q "incus file pull --project throwaway-nikopol nikopol-mgmt-master/etc/rancher/rke2/rke2.yaml -" "$m/calls"
  [[ $output == *"Ready: KUBECONFIG=$m/kube/nikopol.yaml"* ]]
  [ ! -e "$m/kube/.nikopol.yaml.new" ]
  : "a green up ran the six checks, in order, after the kubeconfig"
  order=$(grep -E -o 'check [1-6]/6' <<<"$stderr" | paste -sd,)
  [ "$order" = "check 1/6,check 2/6,check 3/6,check 4/6,check 5/6,check 6/6" ]
  [ ! -e "$m/probe-pod" ]
}

@test "Ready check 1: the node not Running on its member fails up, named" {
  touch "$m/node-stopped"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 1: nikopol-mgmt-master is not Running on member nikopol in project throwaway-nikopol"* ]]
  [[ $stderr != *"check 2/6"* ]]
}

@test "Ready check 2: rke2-server down, or a failed rke2lab-* unit, fails up, named" {
  touch "$m/node-rke2-down"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 2: rke2-server is not active"* ]]
  rm "$m/node-rke2-down"
  touch "$m/node-failed-unit"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 2: a rke2lab-* unit failed on nikopol-mgmt-master: rke2lab-identity.service"* ]]
}

@test "Ready check 4: a node not Ready, or CoreDNS not all Running, fails up, named" {
  touch "$m/kube-node-notready"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 4: node nikopol-mgmt-master is not Ready"* ]]
  rm "$m/kube-node-notready"
  touch "$m/kube-dns-pending"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 4: kube-system pods k8s-app=kube-dns are not Running"* ]]
}

@test "Ready check 5: a busybox pod that does not run fails up, and the pod is deleted anyway" {
  touch "$m/kube-pod-fails"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 5: a busybox pod did not run to completion"* ]]
  [ ! -e "$m/probe-pod" ]
}

@test "Ready check 6: any fleet material on the node fails up, named" {
  for path in /var/lib/rke2lab/sops-age.key /var/lib/rke2lab/cluster-ca-bundle.yaml /run/rke2lab/nix-github.conf \
    /run/rke2lab/rke2-config.env /var/lib/rke2lab/nix/machines /var/lib/rke2lab/nix/builders.conf \
    /var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml /root/.ssh/authorized_keys; do
    touch "$m/node-has-${path//\//_}"
    tw oui up nikopol
    [ "$status" -eq 3 ]
    [[ $stderr == *"not Ready, check 6: $path is on nikopol-mgmt-master"* ]]
    rm "$m/node-has-${path//\//_}"
  done
}

@test "Ready check 6: sops-nix secrets, a tailscale state or tailscaled, an authorized_keys under /home fail up" {
  for f in node-run-secrets node-has-_var_lib_tailscale_tailscaled.state node-tailscaled node-home-keys; do
    touch "$m/$f"
    tw oui up nikopol
    [ "$status" -eq 3 ]
    [[ $stderr == *"not Ready, check 6: "* ]]
    rm "$m/$f"
  done
}

@test "Ready check 4: metrics-server is found by its deployment, whatever its label" {
  touch "$m/kube-metrics-down"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 4: kube-system deployment rke2-metrics-server is not Available"* ]]
}

@test "the probe pod runs the image pinned by digest, never a tag" {
  tw oui up nikopol
  [ "$status" -eq 0 ]
  grep -E -q "kubectl run rke2lab-throwaway-probe --image=busybox@sha256:[0-9a-f]{64} " "$m/calls"
}

@test "mutant: an up that skips the fleet-material check is caught" {
  sed -i 's/^    ! on_node test -e "\$path" || failed 6 .*$/    :/' "$THROWAWAY"
  ! grep -q 'failed 6 "$path is on' "$THROWAWAY"
  touch "$m/node-has-_var_lib_rke2lab_sops-age.key"
  tw oui up nikopol
  : "under the mutant a node holding the fleet's age key passes as Ready: that is what the case above guards"
  [ "$status" -eq 0 ]
}

@test "Ready check 6: an ssh unit not masked, a socket active, or :22 listening fails up, named" {
  touch "$m/node-ssh-enabled"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 6: sshd.socket is 'enabled' on nikopol-mgmt-master, not masked-runtime"* ]]
  rm "$m/node-ssh-enabled"
  touch "$m/node-socket-active"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 6: sshd.socket is active"* ]]
  rm "$m/node-socket-active"
  touch "$m/node-listens-22"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 6: something listens on :22"* ]]
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

@test "Ready check 3: a kube API that does not answer ready fails up, naming what its certificate covers" {
  touch "$m/fail-ready"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"not Ready, check 3: the kube API at https://nikopol-mgmt-master.nikopol:6443 does not answer ready with its certificate verified (it covers: "* ]]
  [[ $stderr != *"check 4/6"* ]]
}

@test "up: a failed fetch leaves no kubeconfig, not even a partial one" {
  touch "$m/fail-pull"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"could not be fetched"* ]]
  [ ! -e "$m/kube/nikopol.yaml" ]
  [ ! -e "$m/kube/.nikopol.yaml.new" ]
}

@test "up: an instance of the node's name in another project (the fleet's) refuses before any preview" {
  printf 'nikopol-mgmt-master,rke2lab\nbioskop-mgmt-master,rke2lab\n' >"$m/instances.csv"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"an instance nikopol-mgmt-master already runs in Incus project rke2lab"* ]]
  ! grep -q "^grow" "$m/calls"
}

@test "up: the node already in its own throwaway project (a second up) is not refused" {
  printf 'nikopol-mgmt-master,throwaway-nikopol\nbioskop-mgmt-master,rke2lab\n' >"$m/instances.csv"
  tw oui up nikopol
  [ "$status" -eq 0 ]
}

@test "up: an instance list that cannot be read refuses, closed" {
  touch "$m/fail-list"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"cannot be listed"* ]]
  ! grep -q "^grow" "$m/calls"
}

@test "up: a stack exporting no node is refused before any fetch" {
  echo '{"throwawayIncusProject":"throwaway-nikopol","throwawayApiServer":"https://x:6443"}' >"$m/outputs.json"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"exports node '', not nikopol-mgmt-master"* ]]
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

@test "reset: outputs naming the fleet's project are refused, and nothing is deleted" {
  grown
  echo '{"throwawayNode":"nikopol-mgmt-master","throwawayIncusProject":"rke2lab","throwawayApiServer":"https://x.nikopol:6443"}' >"$m/outputs.json"
  tw nikopol reset nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"exports Incus project 'rke2lab', not throwaway-nikopol: refused"* ]]
  ! grep -q -E "pulumi destroy|incus image delete|incus project delete|pulumi stack rm" "$m/calls"
  [ -s "$m/images" ] && [ -e "$m/project" ] && [ -e "$m/instance" ]
}

@test "reset: outputs naming another node are refused, and nothing is deleted" {
  grown
  echo '{"throwawayNode":"bioskop-mgmt-master","throwawayIncusProject":"throwaway-nikopol","throwawayApiServer":"https://x.nikopol:6443"}' >"$m/outputs.json"
  tw nikopol reset nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"exports node 'bioskop-mgmt-master', not nikopol-mgmt-master: refused"* ]]
  ! grep -q -E "pulumi destroy|incus image delete|incus project delete|pulumi stack rm" "$m/calls"
}

@test "up: outputs naming the fleet's project are refused before any fetch" {
  echo '{"throwawayNode":"nikopol-mgmt-master","throwawayIncusProject":"rke2lab","throwawayApiServer":"https://x.nikopol:6443"}' >"$m/outputs.json"
  tw oui up nikopol
  [ "$status" -eq 3 ]
  [[ $stderr == *"exports Incus project 'rke2lab'"* ]]
  ! grep -q "incus file pull" "$m/calls"
}

@test "mutant: a reset that trusts the stack's project would delete the fleet's images" {
  sed -i 's/^  \[\[ \$got == "\$project" \]\] || die .*$/  project=$got/' "$THROWAWAY"
  grep -q '^  project=\$got$' "$THROWAWAY"
  grown
  echo '{"throwawayNode":"nikopol-mgmt-master","throwawayIncusProject":"rke2lab","throwawayApiServer":"https://x.nikopol:6443"}' >"$m/outputs.json"
  tw nikopol reset nikopol
  : "the case above refuses it; under the mutant, the deletion reaches project rke2lab: that is what the case guards"
  grep -q "incus project delete rke2lab" "$m/calls"
}

@test "refused: a committed throwaway stack file that names no host" {
  printf 'config:\n  rke2lab:cluster:\n    profile: throwaway\n' >Pulumi.nohost.yaml
  git add Pulumi.nohost.yaml && git commit -q -m nohost
  tw oui up nohost
  [ "$status" -eq 3 ]
  [[ $stderr == *"names no rke2lab:cluster host"* ]]
  [ ! -e "$m/calls" ]
}

@test "reset: a failed destroy stops there, says so, and a rerun resumes from the derived names" {
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
  : "the outputs are gone with a half-done destroy; the names derived from the stack carry the rerun"
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
  [[ $stderr == *"step 2/4 failed on project throwaway-nikopol"* ]]
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
