# rke2lab-throwaway (see ./throwaway.sh). One factory, used by the #throwaway app and by its bats suite with mocked
# tools, so the suite tests the text the operator runs. The tools are store paths fixed here, never a PATH lookup.
{
  pkgs,
  grow,
  growProfile,
  pulumi,
  incus,
  kubectl,
  ttyIn ? "/dev/tty",
  ttyOut ? "/dev/tty",
  kubeDir ? null,
}:
pkgs.writeShellApplication {
  name = "rke2lab-throwaway";
  runtimeInputs = [
    pkgs.coreutils
    pkgs.git
    pkgs.gnused
    pkgs.yq-go
  ];
  text = ''
    grow=${pkgs.lib.escapeShellArg grow}
    growProfile=${pkgs.lib.escapeShellArg growProfile}
    pulumi=${pkgs.lib.escapeShellArg pulumi}
    incus=${pkgs.lib.escapeShellArg incus}
    kubectl=${pkgs.lib.escapeShellArg kubectl}
    ttyIn=${pkgs.lib.escapeShellArg ttyIn}
    ttyOut=${pkgs.lib.escapeShellArg ttyOut}
    kubeDir=${if kubeDir == null then ''"$HOME/.kube/rke2lab-throwaway"'' else pkgs.lib.escapeShellArg kubeDir}
  ''
  + builtins.readFile ./throwaway.sh;
}
