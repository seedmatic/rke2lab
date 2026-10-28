# bump's bats suites as a flake check: the real bump.sh with its placeholders filled by values the stubs replace (every
# mechanism and the checks come from the BUMP_* variables the suite sets), and the real rke2-overlay.sh against stubs
# of gh, curl, nix-prefetch-url and nix; a throwaway git repo as the root.
{ pkgs }:
let
  bump = pkgs.writeShellScript "bump.sh" (
    builtins.replaceStrings
      [ "@nix@" "@versions@" "@relock@" "@nfcRelock@" "@manifests@" "@overlay@" "@checks@" ]
      [ "false" "false" "false" "false" "false" "false" "" ]
      (builtins.readFile ../bump.sh)
  );
in
pkgs.runCommand "bump-bats"
  {
    nativeBuildInputs = with pkgs; [
      bats
      bash
      coreutils
      gawk
      git
      gnugrep
      gnused
      jq
    ];
  }
  ''
    mkdir -p b/tests && cp ${bump} b/bump.sh && cp ${../rke2-overlay.sh} b/rke2-overlay.sh
    cp ${./bump.bats} b/tests/bump.bats && cp ${./rke2-overlay.bats} b/tests/rke2-overlay.bats && chmod -R u+w b
    cd b/tests && HOME=$TMPDIR bats --print-output-on-failure bump.bats rke2-overlay.bats
    touch $out
  ''
