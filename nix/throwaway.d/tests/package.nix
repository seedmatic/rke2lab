# The rke2lab-throwaway bats suite, as a flake check: the command as built against mocked grow, pulumi, incus and
# kubectl (./mocks), the real rke2lab-grow-profile gate, and files standing for the tty. The sandbox's only writable
# place is known at run time, so the paths built in are a placeholder the suite points at its own directory.
# realGrow: the #grow app as built, whose PATH the stubs must come before for every tool they stand for.
{
  pkgs,
  growProfile,
  realGrow,
}:
let
  mock = name: "@M@/bin/${name}";
  throwaway = import ../package.nix {
    inherit pkgs;
    growProfile = "${growProfile}/bin/rke2lab-grow-profile";
    grow = mock "grow";
    pulumi = mock "pulumi";
    incus = mock "incus";
    kubectl = mock "kubectl";
    # A made-up digest: the suite checks that the pinned image is the one run, not which image it is.
    probeImage = "busybox@sha256:${pkgs.lib.fixedWidthString 64 "0" "1"}";
    ttyIn = "@M@/tty";
    ttyOut = "@M@/prompt";
    kubeDir = "@M@/kube";
    waitSeconds = 2;
    pollSeconds = 1;
  };
in
pkgs.runCommand "throwaway-bats"
  {
    nativeBuildInputs = [
      pkgs.bats
      pkgs.coreutils
      pkgs.git
      pkgs.gnused
      pkgs.yq-go
    ];
    THROWAWAY_BUILT = "${throwaway}/bin/rke2lab-throwaway";
    GROW_BUILT = "${realGrow}/bin/rke2lab-grow";
  }
  ''
    cp -r ${./.} tests && chmod -R u+w tests
    cd tests && HOME=$TMPDIR bats --print-output-on-failure .
    touch $out
  ''
