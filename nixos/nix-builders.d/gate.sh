# rke2lab-nix-builders — turn the delivered builder description into nix's delegation, or refuse.
#
# A cluster node builds nothing itself once its cluster builder is delivered: max-jobs = 0, every
# build to that builder over ssh-ng (ndh docs/private-binary-cache-spec.adoc § cluster-builds). The
# image is the same for every cluster, so the builder arrives at boot in $dir/machines, through
# cloud-init write_files: one nix machines line per builder, naming the ssh client key whose
# -cert.pub lies beside it, with the builder's host key in column 8. This gate writes
# $dir/builders.conf, which nix.conf !includes, only from a description every line of which
# passes. Anything else refuses, so a wrong description never falls back to local builds in
# silence. It never reads the client key, only checks it is there.
#
# Env: DIR (default /var/lib/rke2lab/nix); REQUIRED=1 makes a missing description a refusal too.

dir="${DIR:-/var/lib/rke2lab/nix}"
machines="$dir/machines"
conf="$dir/builders.conf"

refuse() {
  rm -f "$conf"
  echo "rke2lab-nix-builders: $*" >&2
  exit 1
}

if [[ ! -e $machines ]]; then
  rm -f "$conf"
  if [[ ${REQUIRED:-0} == 1 ]]; then
    refuse "no builder description at $machines, and this node requires one"
  fi
  echo "rke2lab-nix-builders: no builder description at $machines — this node builds locally" >&2
  exit 0
fi
[[ -f $machines && -r $machines ]] || refuse "$machines is not a readable file"

count=0
while read -r uri systems key _jobs _speed _features _mandatory hostKey _rest || [[ -n ${uri:-} ]]; do
  [[ -z ${uri:-} || $uri == \#* ]] && continue
  count=$((count + 1))
  [[ $uri == ssh-ng://* ]] ||
    refuse "builder $count: '$uri' is not ssh-ng:// — ssh:// assumes the builder trusts this node"
  [[ ,${systems:-}, == *,aarch64-linux,* ]] || refuse "builder $count does not build aarch64-linux"
  [[ -n ${key:-} && $key != - && -f $key ]] || refuse "builder $count: no client key file '${key:-}'"
  [[ -f "$key-cert.pub" ]] || refuse "builder $count: no certificate beside the client key ($key-cert.pub)"
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
