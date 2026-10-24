# The grow's profile gate (see ./profile.sh). One factory, used by the #grow app and by its bats suite, so the
# suite tests what the grow runs.
{ pkgs }:
pkgs.writeShellApplication {
  name = "rke2lab-grow-profile";
  runtimeInputs = [
    pkgs.coreutils
    pkgs.git
    pkgs.yq-go
  ];
  text = builtins.readFile ./profile.sh;
}
