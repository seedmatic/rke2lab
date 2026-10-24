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
# Tools come from the variables the package sets (grow, growProfile, pulumi, incus, kubectl, ttyIn, ttyOut, kubeDir)
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

kubeconfig="$kubeDir/$stack.yaml"
# What reset needs once destroy has emptied the stack's outputs: noted before step 1, so a rerun resumes. Not secret.
resetState="$kubeDir/.$stack.reset"

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

outputs() { # the stack's three exports, none secret; any one missing refuses
  local json
  json=$("$pulumi" stack output --stack "$stack" --json) || die "the stack's outputs cannot be read"
  node=$(yq -p json -r '.throwawayNode // ""' <<<"$json")
  project=$(yq -p json -r '.throwawayIncusProject // ""' <<<"$json")
  server=$(yq -p json -r '.throwawayApiServer // ""' <<<"$json")
  [[ -n $node && -n $project && -n $server ]] ||
    die "the stack exports no throwawayNode, throwawayIncusProject or throwawayApiServer"
  [[ $node =~ ^[a-z][a-z0-9-]*$ && $project =~ ^[a-z][a-z0-9-]*$ ]] || die "the stack's node or project is not a name"
  [[ $server =~ ^https://[a-z0-9.-]+:[0-9]+$ ]] || die "throwawayApiServer is not an https://host:port"
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

ready() { # /readyz through the kubeconfig, its certificate verified; only the status is printed
  local status
  status=$(KUBECONFIG="$kubeconfig" "$kubectl" get --raw /readyz 2>/dev/null) || return 1
  [[ $status == ok ]]
}

(umask 077 && mkdir -p "$kubeDir")
chmod 0700 "$kubeDir"

case $action in
  up)
    [[ ! -e $resetState ]] || die "a reset of $stack was interrupted: finish it (rke2lab-throwaway reset $stack) first"
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
    ready || die "the kube API at $server does not answer ready with its certificate verified"
    echo "ready: KUBECONFIG=$kubeconfig"
    ;;
  reset)
    if [[ -e $resetState ]]; then
      node=$(sed -n 's/^node=//p' "$resetState")
      project=$(sed -n 's/^project=//p' "$resetState")
      [[ $node =~ ^[a-z][a-z0-9-]*$ && $project =~ ^[a-z][a-z0-9-]*$ ]] ||
        die "$resetState is malformed: remove it only once you know the stack is gone"
      echo "resuming an interrupted reset of $stack"
    else
      outputs
    fi
    printf 'reset throwaway stack %s, in order:\n' "$stack"
    printf '  1. pulumi destroy (instance %s, in Incus project %s)\n' "$node" "$project"
    printf '  2. the images left in project %s, then the project\n' "$project"
    printf '  3. pulumi stack rm %s\n' "$stack"
    printf '  4. %s\n' "$kubeconfig"
    ask "type the stack name to reset it:" "$stack" || { echo "nothing done: the name typed is not $stack"; exit 5; }
    printf 'node=%s\nproject=%s\n' "$node" "$project" >"$resetState"

    step "1/4 destroy"
    if stack_exists; then
      "$pulumi" destroy --yes --stack "$stack" || die "step 1/4 (destroy) failed: nothing after it was done; rerun reset"
    fi
    ! "$incus" info --project "$project" "$node" >/dev/null 2>&1 ||
      die "step 1/4: instance $node is still there; rerun reset"

    step "2/4 images and project"
    if "$incus" project show "$project" >/dev/null 2>&1; then
      for image in $("$incus" image list --project "$project" --format csv --columns f); do
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
    rm -f "$resetState"
    echo "reset: throwaway stack $stack is gone"
    ;;
esac
