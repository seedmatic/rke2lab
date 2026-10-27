# RKE2 1.36 on the nodes (the operator, 2026-10-27: MutatingAdmissionPolicy is GA in 1.36). No stable nixpkgs
# carries rke2_1_36 yet, and nixpkgs-unstable's would drag its own glibc and systemd into the image (1995 derivations
# moved, measured). So rke2 1.36 is built by the STABLE nixpkgs' own rke2 builder from unstable's 1_36/versions.nix:
# upstream's version, commit and hashes, and only rke2 moves (14 derivations, measured). Remove this module, and its
# check, once the stable nixpkgs carries rke2_1_36.
{
  lib,
  pkgs,
  nixpkgs-unstable,
  ...
}:
{
  services.rke2.package =
    pkgs.callPackage
      (import "${pkgs.path}/pkgs/applications/networking/cluster/rke2/builder.nix" lib (
        import "${nixpkgs-unstable}/pkgs/applications/networking/cluster/rke2/1_36/versions.nix"
      ))
      { };
}
