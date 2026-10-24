# rke2lab-throwaway: the operator's two gestures on a THROWAWAY cluster (a kube API on nikopol for another
# project's tests), each its own go, each checked by itself.
#
#   rke2lab-throwaway up <stack>      preview, the operator's « oui », up; then the kubeconfig, fetched from the node
#   rke2lab-throwaway reset <stack>   the operator TYPES the stack name; then everything the stack made, in order
#
# The stack is a throwaway one only if its COMMITTED Pulumi.<stack>.yaml says so (rke2lab-grow-profile, the gate #grow
# runs too): a fleet stack can never be grown or reset by this command. The throwaway decrypts nothing, so it takes no
# age identity. The kubeconfig carries a cluster-admin client key: it is piped from the node into its own 0600 file,
# never printed, never merged into ~/.kube/config; the whole script runs untraced.
#
# Tools come from the variables the package sets (grow, growProfile, pulumi, incus, kubectl, ttyIn, ttyOut, kubeDir,
# waitSeconds, pollSeconds, probeImage)
# so the tests run this same text against mocks.
set -euo pipefail
set +x

die() { echo "rke2lab-throwaway: $*" >&2; exit 3; }
step() { echo "==> $*" >&2; }

usage() {
  cat >&2 <<'USAGE'
usage: rke2lab-throwaway up <stack>      grow a throwaway stack: preview, your « oui », up, then its kubeconfig
       rke2lab-throwaway reset <stack>   destroy it: you type the stack name; resumable, never a failed step skipped
Run from the rke2lab repo root. The stack's COMMITTED Pulumi.<stack>.yaml must say rke2lab:cluster:profile: throwaway.
USAGE
}

case ${1:-} in
  -h | --help) usage; exit 0 ;;
  up | reset) ;;
  *) usage; exit 2 ;;
esac
(($# == 2)) || { usage; exit 2; }
action=$1 stack=$2
[[ $stack =~ ^[a-z][a-z0-9-]*$ ]] || die "'$stack' is not a stack name"
[[ -f Pulumi.yaml ]] || die "run from the rke2lab repo root (no Pulumi.yaml here)"

: "the committed stack file decides, the same gate #grow runs; this command's marker is the other half"
export RKE2LAB_GROW_PROFILE=throwaway
[[ $("$growProfile" "$stack") == throwaway ]] || die "$stack is not a throwaway stack"

# What reset deletes is DERIVED, never read from the stack: a wrong output (a bug, an edited state) can then never name
# the fleet's project. The project is what bounds every deletion, so it is the stack's own and can be no other; the
# node keeps the blueprint's name (its hostname and its reserved address follow it), from the committed host. The
# stack's outputs are only compared with these.
project="throwaway-$stack"
host=$(git show "HEAD:Pulumi.$stack.yaml" | yq '.config."rke2lab:cluster".host // ""') ||
  die "the committed Pulumi.$stack.yaml cannot be read"
[[ $host =~ ^[a-z][a-z0-9-]*$ ]] || die "the committed Pulumi.$stack.yaml names no rke2lab:cluster host"
node="$host-mgmt-master"
kubeconfig="$kubeDir/$stack.yaml"
# Marks a reset begun and not finished, so up refuses until it is. It holds nothing: the names are derived.
resetMark="$kubeDir/.$stack.reset"

: "the same local backend #grow uses (its env mirrors the flox env's [vars]), for the outputs, destroy and stack rm"
export PULUMI_HOME="${PULUMI_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/pulumi}"
export PULUMI_BACKEND_URL="${PULUMI_BACKEND_URL:-file://$PWD/.pulumi-state}"
export PULUMI_CONFIG_PASSPHRASE="${PULUMI_CONFIG_PASSPHRASE:-}"

ask() { # <prompt> <expected>: the answer read on the tty, never stdin
  local answer
  printf '%s ' "$1" >>"$ttyOut"
  exec 7<"$ttyIn"
  read -r answer <&7 || answer=""
  exec 7<&-
  [[ $answer == "$2" ]]
}

outputs() { # the stack's three exports, none secret: the node and project must be the derived ones; the server is used
  local json got
  json=$("$pulumi" stack output --stack "$stack" --json) || die "the stack's outputs cannot be read"
  got=$(yq -p json -r '.throwawayIncusProject // ""' <<<"$json")
  [[ $got == "$project" ]] || die "the stack exports Incus project '$got', not $project: refused"
  got=$(yq -p json -r '.throwawayNode // ""' <<<"$json")
  [[ $got == "$node" ]] || die "the stack exports node '$got', not $node: refused"
  server=$(yq -p json -r '.throwawayApiServer // ""' <<<"$json")
  [[ $server =~ ^https://[a-z0-9.-]+:[0-9]+$ ]] || die "the stack exports no throwawayApiServer as an https://host:port"
}

stack_exists() {
  S="$stack" yq -p json -e '.[] | select(.name == strenv(S))' <<<"$("$pulumi" stack ls --json)" >/dev/null 2>&1
}

fetch_kubeconfig() { # rke2's own admin kubeconfig, piped from the node into its 0600 file, its server rewritten
  local tmp="$kubeDir/.$stack.yaml.new"
  (
    umask 077
    "$incus" file pull --project "$project" "$node/etc/rancher/rke2/rke2.yaml" - |
      S="$server" yq '.clusters[].cluster.server = strenv(S)' >"$tmp"
  ) || { rm -f "$tmp"; die "the kubeconfig could not be fetched from $node"; }
  [[ -s $tmp ]] || { rm -f "$tmp"; die "the kubeconfig fetched from $node is empty"; }
  mv -f "$tmp" "$kubeconfig"
  [[ $(stat -c %a "$kubeconfig") == 600 ]] || die "$kubeconfig is not 0600"
}

# « Ready » is this command's (☸️): the JVM skips its readiness crossings for a throwaway, so a green up must mean a
# cluster that works and holds nothing of the fleet. The checks run in order; the first that fails stops up, named.
member="$host"
# node-base's sshd is socket-activated with a vsock listener: these four are what the profile masks (☸️, 🌱's bootcmd).
sshUnits=(sshd.socket sshd-vsock.socket sshd@.service sshd-keygen.service)
# What the fleet's grow writes on a node (InstanceGrow's write_files, nixos/): none may be on a throwaway one.
fleetPaths=(
  /var/lib/rke2lab/sops-age.key
  /var/lib/rke2lab/cluster-ca-bundle.yaml
  /run/rke2lab/nix-github.conf
  /run/rke2lab/rke2-config.env
  /var/lib/rke2lab/nix/machines
  /var/lib/rke2lab/nix/builders.conf
  /var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml
  /root/.ssh/authorized_keys
)

kube() { KUBECONFIG="$kubeconfig" "$kubectl" "$@"; }
on_node() { "$incus" exec --project "$project" "$node" -- "$@"; }
failed() { die "not Ready, check $1: $2"; }

eventually() { # <what> <command>...: retried every pollSeconds for waitSeconds
  local deadline=$((SECONDS + waitSeconds))
  until "${@:2}" >/dev/null 2>&1; do
    ((SECONDS < deadline)) || return 1
    step "waiting for $1"
    sleep "$pollSeconds"
  done
}

running_on_member() {
  local row
  row=$("$incus" list --project "$project" --format csv --columns n,s,L "^$node\$") || return 1
  [[ $row == "$node,RUNNING,$member" ]]
}

server_san() { # the names the API's serving certificate covers: it is public, the kubeconfig never goes near it
  # A DIAGNOSTIC only: s_client verifies nothing here, so this certificate is read, never trusted; trust is check 3's.
  local hostport=${server#https://}
  openssl s_client -connect "$hostport" -servername "${hostport%:*}" </dev/null 2>/dev/null |
    openssl x509 -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' '
}

api_ready() { [[ $(kube get --raw /readyz) == ok ]]; }

all_running() { # <label selector>: at least one kube-system pod, every one Running
  local phases
  phases=$(kube get pods -n kube-system -l "$1" -o jsonpath='{.items[*].status.phase}') || return 1
  [[ ${phases// /} =~ ^(Running)+$ ]]
}

# rke2's metrics-server chart has carried more than one label over its versions: its deployment's name is stable.
deployment_available() { kube wait -n kube-system --for=condition=Available "deployment/$1" --timeout=10s; }

pod_runs() {
  kube run rke2lab-throwaway-probe --image="$probeImage" --restart=Never --command -- true >/dev/null &&
    kube wait --for=jsonpath='{.status.phase}'=Succeeded pod/rke2lab-throwaway-probe --timeout="${waitSeconds}s" >/dev/null
  local ok=$?
  kube delete pod rke2lab-throwaway-probe --ignore-not-found --wait=false >/dev/null 2>&1 || true
  return "$ok"
}

ready() {
  local unit path state
  step "check 1/6: $node Running on member $member, in project $project"
  eventually "$node to run" running_on_member || failed 1 "$node is not Running on member $member in project $project"

  step "check 2/6: rke2-server active, no failed rke2lab-* unit"
  eventually "rke2-server" on_node systemctl is-active --quiet rke2-server.service ||
    failed 2 "rke2-server is not active on $node"
  [[ -z $(on_node systemctl list-units --failed --plain --no-legend 'rke2lab-*') ]] ||
    failed 2 "a rke2lab-* unit failed on $node: $(on_node systemctl list-units --failed --plain --no-legend 'rke2lab-*' | cut -d' ' -f1 | paste -sd' ')"

  step "check 3/6: /readyz at $server, its certificate verified"
  if ! eventually "the kube API" api_ready; then
    failed 3 "the kube API at $server does not answer ready with its certificate verified (it covers: $(server_san || echo unknown))"
  fi

  step "check 4/6: the node Ready; cilium, CoreDNS, metrics-server Running"
  eventually "the node to be Ready" kube wait --for=condition=Ready "node/$node" --timeout=10s ||
    failed 4 "node $node is not Ready"
  for app in k8s-app=cilium k8s-app=kube-dns; do
    eventually "$app" all_running "$app" || failed 4 "kube-system pods $app are not Running"
  done
  eventually "rke2-metrics-server" deployment_available rke2-metrics-server ||
    failed 4 "kube-system deployment rke2-metrics-server is not Available"

  step "check 5/6: a busybox pod scheduled and run, then deleted"
  pod_runs || failed 5 "a busybox pod did not run to completion"

  step "check 6/6: nothing of the fleet on $node"
  for path in "${fleetPaths[@]}"; do
    ! on_node test -e "$path" || failed 6 "$path is on $node"
  done
  [[ -z $(on_node find /home -mindepth 3 -maxdepth 3 -path '*/.ssh/authorized_keys' 2>/dev/null) ]] ||
    failed 6 "an authorized_keys is under /home on $node"
  for path in /run/secrets /run/secrets.d; do
    [[ -z $(on_node find "$path" -mindepth 1 -maxdepth 1 2>/dev/null) ]] || failed 6 "$path is not empty on $node"
  done
  ! on_node test -e /var/lib/tailscale/tailscaled.state || failed 6 "a tailscale state is on $node"
  ! on_node systemctl is-active --quiet tailscaled.service || failed 6 "tailscaled is active on $node"
  for unit in "${sshUnits[@]}"; do
    state=$(on_node systemctl is-enabled "$unit" 2>/dev/null || true)
    [[ $state == masked-runtime ]] || failed 6 "$unit is '$state' on $node, not masked-runtime"
  done
  for unit in sshd.socket sshd-vsock.socket; do
    ! on_node systemctl is-active --quiet "$unit" || failed 6 "$unit is active on $node"
  done
  [[ -z $(on_node ss -Hltn 'sport = :22') ]] || failed 6 "something listens on :22 on $node"
}

(umask 077 && mkdir -p "$kubeDir")
chmod 0700 "$kubeDir"

case $action in
  up)
    [[ ! -e $resetMark ]] || die "a reset of $stack was interrupted: finish it (rke2lab-throwaway reset $stack) first"
    : "the node takes the blueprint's hostname and reserved address: an instance of that name in another project (the"
    : "fleet's own cluster) would put two of them on the fabric, so up refuses; an unreadable list refuses too"
    instances=$("$incus" list --all-projects --format csv --columns n,P) || die "the Incus instances cannot be listed"
    others=$(N="$node" P="$project" awk -F, '$1 == ENVIRON["N"] && $2 != ENVIRON["P"] { print $2 }' <<<"$instances")
    [[ -z $others ]] || die "an instance $node already runs in Incus project ${others//$'\n'/, }: reset that one first"
    step "preview of throwaway stack $stack"
    "$grow" "$stack" preview
    ask "grow throwaway stack $stack as previewed above? [oui/non]" oui ||
      { echo "nothing done: the answer is not « oui »"; exit 5; }
    step "up"
    "$grow" "$stack" up
    outputs
    "$incus" info --project "$project" "$node" >/dev/null || die "$node is not reachable in Incus project $project"
    step "kubeconfig from $node into $kubeconfig"
    fetch_kubeconfig
    ready
    echo "Ready: KUBECONFIG=$kubeconfig"
    ;;
  reset)
    : "a stack still there must export the derived names; once destroy has emptied its outputs, they carry the rerun"
    if stack_exists && [[ ! -e $resetMark ]]; then
      outputs
    fi
    [[ ! -e $resetMark ]] || echo "resuming an interrupted reset of $stack"
    printf 'reset throwaway stack %s, in order:\n' "$stack"
    printf '  1. pulumi destroy (instance %s, in Incus project %s)\n' "$node" "$project"
    printf '  2. the images left in project %s, then the project\n' "$project"
    printf '  3. pulumi stack rm %s\n' "$stack"
    printf '  4. %s\n' "$kubeconfig"
    ask "type the stack name to reset it:" "$stack" || { echo "nothing done: the name typed is not $stack"; exit 5; }
    : >"$resetMark"

    step "1/4 destroy"
    if stack_exists; then
      "$pulumi" destroy --yes --stack "$stack" || die "step 1/4 (destroy) failed: nothing after it was done; rerun reset"
    fi
    ! "$incus" info --project "$project" "$node" >/dev/null 2>&1 ||
      die "step 1/4: instance $node is still there; rerun reset"

    step "2/4 images and project"
    if "$incus" project show "$project" >/dev/null 2>&1; then
      mapfile -t images < <("$incus" image list --project "$project" --format csv --columns f)
      for image in "${images[@]}"; do
        "$incus" image delete --project "$project" "$image" || die "step 2/4 failed on image $image; rerun reset"
      done
      "$incus" project delete "$project" || die "step 2/4 failed on project $project; rerun reset"
    fi
    ! "$incus" project show "$project" >/dev/null 2>&1 || die "step 2/4: project $project is still there; rerun reset"

    step "3/4 stack rm"
    if stack_exists; then
      "$pulumi" stack rm --yes --stack "$stack" || die "step 3/4 (stack rm) failed; rerun reset"
    fi
    ! stack_exists || die "step 3/4: stack $stack is still there; rerun reset"

    step "4/4 kubeconfig"
    rm -f "$kubeconfig"
    [[ ! -e $kubeconfig ]] || die "step 4/4: $kubeconfig is still there"
    rm -f "$resetMark"
    echo "reset: throwaway stack $stack is gone"
    ;;
esac
