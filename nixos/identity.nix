# rke2lab per-node hostname — set at boot from the cloud-init-delivered /run/rke2lab/node.env. The
# per-node identity scalars (node-name/hostname/kind/id + the per-cluster dual-stack CIDRs) now arrive
# through the UNIFORM cloud-init channel: seed-master (mgmt) poses them as a `write_files` entry
# writing node.env (see ./cloud-init.nix for the incus datasource shim; host GROW renders the
# cloud-config). This oneshot only sets the transient hostname from node.env (no dbus/hostnamed at
# this early ordering point), so mDNS resolves <cluster>-<node> and rke2 registers under it. The
# other node.env consumers (dual-stack/tls-san/node-labels drop-ins) live in ./rke2.nix.
#
# ConditionPathExists: a node whose channel carries no node.env (a CAPRKE2-driven workload node, which
# owns its own hostname/config via its own cloud-config) cleanly no-ops this unit.
{ ... }:
{
  systemd.services.rke2lab-identity = {
    description = "rke2lab node hostname (cloud-init node.env → transient hostname)";
    wantedBy = [ "multi-user.target" ];
    after = [ "cloud-init.service" ];
    before = [
      "rke2-server.service"
      "rke2lab-zfs-containerd.service"
      "avahi-daemon.service"
    ];
    unitConfig.ConditionPathExists = "/run/rke2lab/node.env";
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/run/rke2lab/node.env";
    };
    script = ''
      set -euo pipefail
      # node.env is the cloud-init-written source of truth; avahi and rke2 (ordered after) read the
      # hostname via gethostname().
      printf '%s' "$RKE2LAB_NODE_HOSTNAME" >/proc/sys/kernel/hostname
    '';
  };
}
