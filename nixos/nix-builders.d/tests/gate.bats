#!/usr/bin/env bats
#
# The rke2lab-nix-builders gate, as built (the same factory the node module uses), on a throwaway
# DIR. Every refusal is checked by the state it leaves: no builders.conf, a stale one removed too, so
# a refused description can never leave the node delegating to a builder nobody checked. The sandbox
# cannot chown to root, so OWNER is the test's own uid, except where the root default is the point.

bats_require_minimum_version 1.5.0

setup() {
  dir="$BATS_TEST_TMPDIR/nix"
  mkdir -p "$dir"
  key="$dir/cluster-build"
  : >"$key"
  chmod 0600 "$key"
  : >"$key-cert.pub"
  owner="$(id -u)"
  hostKey="$(printf 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBuilderHostKeyForTests builder' | base64 | tr -d '\n')"
}

line() { printf '%s aarch64-linux %s 4 1 - - %s\n' "${1:-ssh-ng://cluster-build@builder.bioskop}" "${2:-$key}" "${3:-$hostKey}"; }
gate() { DIR="$dir" REQUIRED="${REQUIRED:-0}" OWNER="$owner" run --separate-stderr "$GATE"; }
stale() { printf 'max-jobs = 0\nbuilders = @%s/machines\n' "$dir" >"$dir/builders.conf"; }
no_conf() { [ ! -e "$dir/builders.conf" ]; }

@test "no description: nothing is written, a stale builders.conf is removed, the node builds locally" {
  stale
  gate
  [ "$status" -eq 0 ]
  no_conf
  [[ $stderr == *"builds locally"* ]]
}

@test "no description on a node that requires one: refused" {
  REQUIRED=1 gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"requires one"* ]]
}

@test "a valid description delegates every build to it, and leaves no temporary file" {
  line >"$dir/machines"
  gate
  [ "$status" -eq 0 ]
  [ "$(cat "$dir/builders.conf")" = "# Written by rke2lab-nix-builders from $dir/machines: every build goes to the cluster builder.
max-jobs = 0
builders = @$dir/machines
builders-use-substitutes = true" ]
  [ "$(stat -c %a "$dir/builders.conf" 2>/dev/null || stat -f %Lp "$dir/builders.conf")" = 644 ]
  [ -z "$(find "$dir" -name '.builders.conf.*')" ]
}

@test "ssh:// is refused: the build hook would assume the builder trusts the node" {
  stale
  line ssh://cluster-build@builder.bioskop >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"is not ssh-ng://"* ]]
}

@test "a builder that does not build aarch64-linux is refused" {
  printf 'ssh-ng://cluster-build@builder.bioskop x86_64-linux %s 4 1 - - %s\n' "$key" "$hostKey" >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
}

@test "a missing client key, or a key without its certificate, is refused" {
  line ssh-ng://cluster-build@builder.bioskop "$dir/absent" >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"no client key file"* ]]
  rm "$key-cert.pub"
  line >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"no certificate"* ]]
}

@test "a missing, non-base64 or non-ed25519 host key is refused" {
  line ssh-ng://cluster-build@builder.bioskop "$key" - >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  [[ $stderr == *"no host key"* ]]
  line ssh-ng://cluster-build@builder.bioskop "$key" '%%%' >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  [[ $stderr == *"not base64"* ]]
  line ssh-ng://cluster-build@builder.bioskop "$key" "$(printf 'ssh-rsa AAAA x' | base64)" >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  [[ $stderr == *"not an ssh-ed25519 public"* ]]
  no_conf
}

@test "a description with no builder (empty, or comments only) is refused" {
  : >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  printf '# nothing here\n\n' >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  [[ $stderr == *"describes no builder"* ]]
  no_conf
}

@test "one bad builder among good ones refuses the whole description" {
  stale
  { line; line ssh://cluster-build@other.bioskop; } >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"builder 2"* ]]
}

@test "a last line without a newline is still checked" {
  printf 'ssh://cluster-build@builder.bioskop aarch64-linux %s 4 1 - - %s' "$key" "$hostKey" >"$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
}

@test "a description writable by group or others is refused" {
  stale
  line >"$dir/machines"
  chmod 0666 "$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"the builder description"*"writable by group or others"* ]]
}

@test "files not owned by root are refused by default, and by any other owner" {
  line >"$dir/machines"
  # Built as root, the test's files would satisfy the default: give the directory to nobody.
  [ "$(id -u)" != 0 ] || chown 65534 "$dir"
  DIR="$dir" REQUIRED=0 run --separate-stderr env -u OWNER "$GATE"
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"not 0"* ]]
  owner=$((owner + 1))
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"not $owner"* ]]
}

@test "a description that is a symlink, here to a file its user can write, is refused" {
  stale
  line >"$BATS_TEST_TMPDIR/elsewhere"
  ln -s "$BATS_TEST_TMPDIR/elsewhere" "$dir/machines"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"is a symlink"* ]]
}

@test "a client key readable by others is refused" {
  line >"$dir/machines"
  chmod 0644 "$key"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"the client key"*"not one of: 400 600"* ]]
}

@test "a writable delegation directory is refused even with no description" {
  stale
  chmod 0777 "$dir"
  gate
  [ "$status" -eq 1 ]
  no_conf
  [[ $stderr == *"the delegation directory"*"writable by group or others"* ]]
}
