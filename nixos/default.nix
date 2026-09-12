# rke2lab node-base — the homogeneous NixOS substrate every RKE2 node shares. This is the aggregator:
# each concern is a sibling module pulled in via `imports`; the flake imports the directory as
# `./nixos`. Role (server/agent) and per-node identity (node-ip, hostname, token) are layered on top;
# this is what is common to all nodes. See docs/architecture/nixos-substrate/substrate-model.adoc.
#
# Iteration 1 (path B): stock rke2 server, dual-stack, flox baked. Proven end-to-end by the spike
# (image builds, boots in Incus, rke2-server + containerd come up). Next iterations add the
# flox-nri-plugin (containerd NRI, from the flox-runtime flake input), cilium, and the zfs snapshotter.
{ lib, ... }:
{
  imports = [
    ./rke2.nix # rke2-server + dual-stack + rke2lab.target
    ./cloud-init.nix # cloud-init datasource shim (incus devlxd → NoCloud seed) — the uniform bootstrap channel
    ./capn-airgapped.nix # inert /opt/install.sh so CAPRKE2 airGapped no-ops onto the baked rke2 (workload nodes)
    ./identity.nix # per-node hostname from the cloud-init-delivered node.env
    ./sops.nix # sops-nix: PKI secret declaration + cloud-init runtime delivery
    ./containerd.nix # flox NRI runtime + zfs snapshotter + dataset mount
    ./zfs.nix # node zfs userland + FHS-compat symlinks (openebs-zfs CSI chroot wrapper)
    ./flox-runtime.nix # flox NRI plugin OCI hooks + /etc/flox.toml (workload envs = runtime FloxEnv CRs, not baked)
    ./flox-carrier.nix # the minimal nix OCI base image every flox-injected pod runs (baked → rke2 air-gap import)
    ./flox-controller.nix # the flox-controller node-agent image (baked → rke2 air-gap import; it produces the carriers)
    # NOTE: rke2-adoption-controller is NO LONGER baked — it rides the flox runtime like the mesh
    # workloads (cluster-api/rke2-adoption-controller FloxEnv installs the binary from the
    # flox-catalogue onto the flox carrier). See Rke2AdoptionControllerManifestsUnit.
    ./ndh-bringup-runtime.nix # ndh bringup-runtime profile symlink (manage-tailnet's trampoline command contract, visible in flox pods)
    ./host-access.nix # dbus-over-TCP + mDNS, so the operator reaches the node
    ./nix-env.nix # nix.settings + the node toolbox on PATH
  ];

  system.stateVersion = "24.11";

  # Per-node identity is NOT baked: the image is homogeneous. rke2lab-identity.service (./identity.nix)
  # reads the incus instance's user.rke2lab.node-* keys over devlxd at boot and sets the transient
  # hostname to <cluster>-<node>. Empty here means "NixOS manages no hostname" — the service owns it.
  networking.hostName = lib.mkForce "";

  # Lab node: no host firewall in the way of the rke2 control-plane / cluster ports.
  networking.firewall.enable = false;
}
