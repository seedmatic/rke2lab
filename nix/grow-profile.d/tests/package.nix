# The rke2lab-grow-profile gate's bats suite, as a flake check: the gate as built, a throwaway git repository.
{ pkgs }:
let
  gate = import ../package.nix { inherit pkgs; };
in
pkgs.runCommand "grow-profile-bats"
  {
    nativeBuildInputs = [
      pkgs.bats
      pkgs.coreutils
      pkgs.git
    ];
    GATE = "${gate}/bin/rke2lab-grow-profile";
  }
  ''
    cp -r ${./.} tests && chmod -R u+w tests
    cd tests && HOME=$TMPDIR bats --print-output-on-failure .
    touch $out
  ''
