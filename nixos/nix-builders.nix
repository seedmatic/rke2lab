# A cluster node delegates every build to its cluster builder, over ssh-ng, and to it only (ndh
# docs/private-binary-cache-spec.adoc § cluster-builds). The builder differs per metalspace and the
# image is the same for every cluster, so nothing about it is baked: nix.conf !includes a
# builders.conf that the rke2lab-nix-builders gate writes at boot from the description cloud-init
# delivers (./nix-builders.d/gate.sh). Until a description is delivered (the cluster builder is not
# built yet) the include is absent and the node builds locally, as before; set
# rke2lab.nodeBuilders.required once every grow delivers one, and a node without it refuses to start
# rke2.
{
  config,
  lib,
  pkgs,
  ...
}:
let
  dir = "/var/lib/rke2lab/nix";
  gate = import ./nix-builders.d/package.nix { inherit pkgs; };
in
{
  options.rke2lab.nodeBuilders.required = lib.mkOption {
    type = lib.types.bool;
    default = false;
    description = "Refuse to start rke2 when no cluster-builder description is delivered.";
  };

  config = {
    # !include, not include: an absent file is ignored (measured with nix 2.34: max-jobs keeps its
    # value), so a node without a description builds locally. Last in nix.conf, so it wins.
    nix.extraOptions = ''
      !include ${dir}/builders.conf
    '';

    # The delegation has ONE source, the gate: a static builders list or machines file here would
    # be baked into a homogeneous image, and could carry an ssh:// entry the gate never sees.
    assertions = [
      {
        assertion = config.nix.buildMachines == [ ] && !config.nix.distributedBuilds;
        message = "rke2lab node-base: the cluster builder is delivered at boot (nix-builders.nix); do not set nix.buildMachines or nix.distributedBuilds";
      }
    ];

    systemd.tmpfiles.rules = [ "d ${dir} 0755 root root -" ];

    systemd.services.rke2lab-nix-builders = {
      description = "rke2lab cluster-builder delegation (delivered description → nix builders.conf)";
      after = [ "cloud-init.service" ];
      wants = [ "cloud-init.service" ];
      # Before the daemon reads nix.conf, and before the first build of the boot (the rke2 config
      # fetch). Required by that fetch, so a REFUSED description stops it instead of letting it build
      # locally.
      before = [
        "nix-daemon.service"
        "rke2lab-rke2-config.service"
      ];
      requiredBy = [ "rke2lab-rke2-config.service" ];
      wantedBy = [ "multi-user.target" ];
      environment = {
        DIR = dir;
        REQUIRED = if config.rke2lab.nodeBuilders.required then "1" else "0";
      };
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        ExecStart = "${gate}/bin/rke2lab-nix-builders";
      };
    };
  };
}
