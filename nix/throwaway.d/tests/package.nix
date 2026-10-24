# The rke2lab-throwaway bats suite, as a flake check: the command as built against mocked grow, pulumi, incus and
# kubectl (./mocks), the real rke2lab-grow-profile gate, and files standing for the tty. The sandbox's only writable
# place is known at run time, so the paths built in are a placeholder the suite points at its own directory.
{ pkgs, growProfile }:
let
  mock = name: "@M@/bin/${name}";
  throwaway = import ../package.nix {
    inherit pkgs;
    growProfile = "${growProfile}/bin/rke2lab-grow-profile";
    grow = mock "grow";
    pulumi = mock "pulumi";
    incus = mock "incus";
    kubectl = mock "kubectl";
    ttyIn = "@M@/tty";
    ttyOut = "@M@/prompt";
    kubeDir = "@M@/kube";
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
  }
  ''
    cp -r ${./.} tests && chmod -R u+w tests
    cd tests && HOME=$TMPDIR bats --print-output-on-failure .
    touch $out
  ''
