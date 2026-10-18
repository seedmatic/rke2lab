# The boot gate that turns a delivered cluster-builder description into nix's delegation (see
# ./gate.sh). One factory, used by the node module and by its bats suite, so the suite tests what the
# node runs.
{ pkgs }:
pkgs.writeShellApplication {
  name = "rke2lab-nix-builders";
  runtimeInputs = [ pkgs.coreutils ];
  text = builtins.readFile ./gate.sh;
}
