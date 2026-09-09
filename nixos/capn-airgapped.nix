# CAPRKE2 air-gapped install shim — the node-base BAKES rke2 (nix, immutable /nix/store), but
# CAPRKE2 has no "skip install" mode: even with airGapped=true its bootstrap runs
# `INSTALL_RKE2_ARTIFACT_PATH=/opt/rke2-artifacts sh /opt/install.sh`. This bakes an INERT
# /opt/install.sh (exit 0) + an empty /opt/rke2-artifacts so that step is a no-op onto the baked
# binary — CAPRKE2 then only writes /etc/rancher/rke2/config.yaml (join token + server URL, which
# MERGES with the node-base's config.yaml.d drop-ins) and enable/start rke2-server (the nix unit).
#
# Only CAPRKE2-provisioned WORKLOAD nodes invoke this; the seed-master-grown mgmt master never does
# (harmless there — homogeneous image). See ClusterApiWorkloadManifestsUnit (RKE2ControlPlane /
# RKE2ConfigTemplate airGapped=true) and docs/architecture/cluster-api/.
{ pkgs, ... }:
let
  inertInstall = pkgs.writeShellScript "capn-airgap-install" ''
    exit 0
  '';
in
{
  systemd.tmpfiles.rules = [
    "d /opt 0755 root root -"
    "d /opt/rke2-artifacts 0755 root root -"
    "L+ /opt/install.sh - - - - ${inertInstall}"
  ];
}
