# rke2lab-throwaway (see ./throwaway.sh). One factory, used by the #throwaway app and by its bats suite with mocked
# tools, so the suite tests the text the operator runs. The tools are store paths fixed here, never a PATH lookup.
{
  pkgs,
  grow,
  growProfile,
  pulumi,
  incus,
  kubectl,
  # The probe pod's image, by digest: a test pod on a work project's cluster never pulls a moving tag.
  probeImage,
  ttyIn ? "/dev/tty",
  ttyOut ? "/dev/tty",
  kubeDir ? null,
  waitSeconds ? 600,
  pollSeconds ? 10,
}:
assert pkgs.lib.assertMsg (builtins.match ".+@sha256:[0-9a-f]{64}" probeImage != null)
  "rke2lab-throwaway: probeImage must be pinned by digest (<image>@sha256:<64 hex>), not '${probeImage}'";
pkgs.writeShellApplication {
  name = "rke2lab-throwaway";
  runtimeInputs = [
    pkgs.coreutils
    pkgs.gawk
    pkgs.git
    pkgs.gnused
    pkgs.openssl
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
    waitSeconds=${toString waitSeconds}
    pollSeconds=${toString pollSeconds}
    probeImage=${pkgs.lib.escapeShellArg probeImage}
    kubeDir=${if kubeDir == null then ''"$HOME/.kube/rke2lab-throwaway"'' else pkgs.lib.escapeShellArg kubeDir}
  ''
  + builtins.readFile ./throwaway.sh;
}
