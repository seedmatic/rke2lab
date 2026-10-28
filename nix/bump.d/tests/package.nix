# bump's bats suite as a flake check: the real bump.sh with its placeholders filled by values the stubs replace (every
# mechanism and the checks come from the BUMP_* variables the suite sets), a throwaway git repo as the root.
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
      jq
    ];
  }
  ''
    mkdir -p b/tests && cp ${bump} b/bump.sh && cp ${./bump.bats} b/tests/bump.bats && chmod -R u+w b
    cd b/tests && HOME=$TMPDIR bats --print-output-on-failure bump.bats
    touch $out
  ''
