# Seed the ndh bringup-runtime nix profile onto the node.
#
# ndh's shell scripts run under `nix-bash-trampoline.sh`, which prepends a dedicated
# root profile — /nix/var/nix/profiles/per-user/root/io-seedmatic-ndh-bringup-runtime —
# to PATH and verifies a command contract (bash, nix, age, awk, sed, grep, ssh,
# ssh-keygen, yq, git) before running the script; if the profile bin is missing it
# aborts (NDH_BOOTSTRAP_STRICT defaults on → "sed: command not found").
#
# manage-tailnet (packaged on the flox-catalog branch, run in-cluster by the mesh
# TailnetPurgeManifestsUnit Job) rides that trampoline. flox-injected pods share the
# node's /nix, so seeding the profile symlink HERE makes it visible inside the purge
# pod. Without it the purge fail-opens (the trampoline aborts, `|| true` swallows it).
#
# We seed ONLY the declarative profile symlink — the same `L+` tmpfiles rule ndh's own
# modules/nixos/bringup-runtime.nix uses — NOT ndh's activation/installer apparatus,
# which carries option deps (config.profile.user, config.ndh.sopsAgeKeyBootstrap) the
# minimal node substrate does not have. A symlinkJoin at $profileDir is all the
# trampoline's runtime:verify needs: $profileDir/bin must hold the required commands,
# which ndh's `nerd-bringup-runtime` package provides.
{
  ndh,
  pkgs,
  ...
}:
let
  system = pkgs.stdenv.hostPlatform.system; # aarch64-linux on the node image
  bringupRuntime = ndh.packages.${system}.nerd-bringup-runtime;
in
{
  systemd.tmpfiles.rules = [
    "d /nix/var/nix/profiles/per-user/root 0755 root root -"
    "L+ /nix/var/nix/profiles/per-user/root/io-seedmatic-ndh-bringup-runtime - - - - ${bringupRuntime}"
  ];
}
