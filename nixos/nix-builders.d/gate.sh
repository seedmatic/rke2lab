# rke2lab-nix-builders — turn the delivered builder description into nix's delegation, or refuse.
#
# A cluster node builds nothing itself once its cluster builder is delivered: max-jobs = 0, every
# build to that builder over ssh-ng (ndh docs/private-binary-cache-spec.adoc § cluster-builds). The
# image is the same for every cluster, so the builder arrives at boot in $dir/machines, through
# cloud-init write_files: one nix machines line per builder, naming the ssh client key whose
# -cert.pub lies beside it, with the builder's host key in column 8. This gate writes
# $dir/builders.conf, which nix.conf !includes, only from a description every line of which
# passes. Anything else refuses, so a wrong description never falls back to local builds in
# silence. It never reads the client key, only stats it.
#
# The host-key pin protects nothing against whoever writes the pin, and the daemon imports its build
# hook's outputs without a signature: so $dir, the description, the key's directory, the key and its
# certificate must be owned by root, not writable by group or others, and not symlinks.
#
# Env: DIR (default /var/lib/rke2lab/nix); REQUIRED=1 makes a missing description a refusal too;
# OWNER is the uid they must belong to, and ANCESTOR_UIDS the uids $dir's ancestors may belong to:
# the node's unit sets both to 0 explicitly (an eval assertion holds it), and only the tests widen
# them, as they can neither chown nor own the sandbox's / (an unmapped root, uid 65534 there).

dir="${DIR:-/var/lib/rke2lab/nix}"
machines="$dir/machines"
conf="$dir/builders.conf"
owner="${OWNER:-0}"
ancestorUids="${ANCESTOR_UIDS:-0}"

refuse() {
  rm -f "$conf"
  echo "rke2lab-nix-builders: $*" >&2
  exit 1
}

# guarded <path> <what> [<allowed modes>]
guarded() {
  local path=$1 what=$2 only=${3:-} meta uid mode
  [[ ! -L $path ]] || refuse "$what $path is a symlink"
  meta="$(stat -c '%u %a' -- "$path")" || refuse "cannot stat $what $path"
  read -r uid mode <<<"$meta"
  [[ $uid == "$owner" ]] || refuse "$what $path is owned by uid $uid, not $owner"
  (((8#$mode & 8#022) == 0)) || refuse "$what $path is writable by group or others (mode $mode)"
  [[ -z $only || " $only " == *" $mode "* ]] || refuse "$what $path has mode $mode, not one of: $only"
}

# Even with no description: a writable $dir would let anyone drop the builders.conf nix includes,
# and a writable ancestor would let them swap $dir for their own.
[[ $dir == /* && -d $dir ]] || refuse "$dir is not an absolute directory"
guarded "$dir" "the delegation directory"
up="${dir%/*}"
while :; do
  above="${up:-/}"
  [[ ! -L $above ]] || refuse "$above, above $dir, is a symlink"
  meta="$(stat -c '%u %a' -- "$above")" || refuse "cannot stat $above, above $dir"
  read -r uid mode <<<"$meta"
  [[ " $ancestorUids " == *" $uid "* ]] || refuse "$above, above $dir, is owned by uid $uid"
  (((8#$mode & 8#022) == 0)) || refuse "$above, above $dir, is writable by group or others (mode $mode)"
  [[ -n $up ]] || break
  up="${up%/*}"
done

if [[ ! -e $machines && ! -L $machines ]]; then
  rm -f "$conf"
  if [[ ${REQUIRED:-0} == 1 ]]; then
    refuse "no builder description at $machines, and this node requires one"
  fi
  echo "rke2lab-nix-builders: no builder description at $machines — this node builds locally" >&2
  exit 0
fi
guarded "$machines" "the builder description"
[[ -f $machines && -r $machines ]] || refuse "$machines is not a readable file"

count=0
while read -r uri systems key _jobs _speed _features _mandatory hostKey _rest || [[ -n ${uri:-} ]]; do
  [[ -z ${uri:-} || $uri == \#* ]] && continue
  count=$((count + 1))
  [[ $uri == ssh-ng://* ]] ||
    refuse "builder $count: '$uri' is not ssh-ng:// — ssh:// assumes the builder trusts this node"
  [[ ,${systems:-}, == *,aarch64-linux,* ]] || refuse "builder $count does not build aarch64-linux"
  [[ -n ${key:-} && $key != - && -f $key ]] || refuse "builder $count: no client key file '${key:-}'"
  [[ $key == /* ]] || refuse "builder $count: the client key '$key' is not an absolute path"
  [[ -f "$key-cert.pub" ]] || refuse "builder $count: no certificate beside the client key ($key-cert.pub)"
  keyDir="${key%/*}"
  guarded "${keyDir:-/}" "builder $count: the client key's directory"
  guarded "$key" "builder $count: the client key" "400 600"
  guarded "$key-cert.pub" "builder $count: the certificate"
  [[ -n ${hostKey:-} && $hostKey != - ]] || refuse "builder $count: no host key in column 8"
  decoded="$(base64 -d <<<"$hostKey")" || refuse "builder $count: its host key is not base64"
  [[ $decoded == "ssh-ed25519 "* ]] || refuse "builder $count: its host key is not an ssh-ed25519 public"
done <"$machines"
((count > 0)) || refuse "$machines describes no builder"

tmp="$(mktemp "$dir/.builders.conf.XXXXXX")" || refuse "cannot write in $dir"
if ! printf '%s\n' \
  "# Written by rke2lab-nix-builders from $machines: every build goes to the cluster builder." \
  "max-jobs = 0" \
  "builders = @$machines" \
  "builders-use-substitutes = true" >"$tmp" ||
  ! chmod 0644 "$tmp" || ! mv -f "$tmp" "$conf"; then
  rm -f "$tmp"
  refuse "cannot install $conf"
fi
echo "rke2lab-nix-builders: $count builder(s); every build is delegated" >&2
