# The nodes' rke2, vendored: ./rke2-version.d holds upstream's versions.nix and images-versions.json for the release
# we run, written as nixpkgs' rke2 update script writes them (version, commit, hashes, image tags). No nixpkgs carries
# rke2 1.37, and nixpkgs-unstable's rke2 would drag its own glibc and systemd into the image (1995 derivations moved,
# measured on 1.36). So the STABLE nixpkgs' own rke2 builder builds it, and only rke2 moves. Remove this module, and its
# check, once the stable nixpkgs carries the release's minor.
{ lib, pkgs, ... }:
{
  services.rke2.package =
    pkgs.callPackage
      (import "${pkgs.path}/pkgs/applications/networking/cluster/rke2/builder.nix" lib (
        import ./rke2-version.d/versions.nix
      ))
      { };
}
