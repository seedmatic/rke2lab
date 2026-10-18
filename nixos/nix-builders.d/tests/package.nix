# The rke2lab-nix-builders gate's bats suite, as a flake check: the gate as built, a throwaway DIR.
{ pkgs }:
let
  gate = import ../package.nix { inherit pkgs; };
in
pkgs.runCommand "nix-builders-gate-bats"
  {
    nativeBuildInputs = [
      pkgs.bats
      pkgs.coreutils
      pkgs.findutils
    ];
    GATE = "${gate}/bin/rke2lab-nix-builders";
  }
  ''
    cp -r ${./.} tests && chmod -R u+w tests
    cd tests && HOME=$TMPDIR bats --print-output-on-failure .
    touch $out
  ''
