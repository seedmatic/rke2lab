# pre-push's skip of the plan's build (plan-closure.sh) as a flake check: its bats suite in a throwaway repository.
{ pkgs }:
pkgs.runCommand "prepush-plan-closure-bats"
  {
    nativeBuildInputs = with pkgs; [
      bats
      bash
      coreutils
      git
      gnugrep
      yq-go
    ];
  }
  ''
    mkdir -p h/tests && cp ${../plan-closure.sh} h/plan-closure.sh && cp ${./plan-closure.bats} h/tests/plan-closure.bats
    cd h/tests && HOME=$TMPDIR bats --print-output-on-failure plan-closure.bats
    touch $out
  ''
