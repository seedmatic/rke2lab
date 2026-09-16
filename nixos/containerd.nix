# rke2's embedded containerd — the flox NRI workload runtime + the zfs snapshotter, and the mount of
# the snapshotter's backing dataset. The declarative form of the former rke2lab-server-pre-start.sh
# (NRI) + config-v3.toml (zfs snapshotter) + rke2lab-configure-containerd-zfs-mount.sh host scripts.
{
  pkgs,
  flox-runtime,
  ...
}:
let
  # The flox containerd runtime, from the flox-runtime flake input
  # (github:seedmatic/flox-nri-plugin): an NRI plugin that injects flox environments into workload
  # containers. containerd launches it from plugin_path.
  floxNriPlugin = flox-runtime.packages.${pkgs.stdenv.hostPlatform.system}.flox-nri-plugin;

  # rke2's embedded containerd auto-imports config-v3.toml.d/*.toml — cleaner than overriding the
  # whole config.toml.tmpl. Two drop-ins: NRI enablement + the zfs snapshotter.
  #
  # We only ENABLE NRI here (socket at /var/run/nri/nri.sock); we do NOT set
  # `plugin_path` — this rke2/containerd build does not reliably launch pre-installed
  # plugins from it (verified: socket up, binary connects fine manually, but
  # containerd never launched it). The plugin is instead run as a persistent systemd
  # service (rke2lab-flox-nri-plugin, below) that connects to the socket — the
  # baked-model equivalent of the old DaemonSet main-container.
  nriDropin = pkgs.writeText "90-nri.toml" ''
    [plugins."io.containerd.nri.v1.nri"]
      disable = false
      plugin_config_path = "/etc/nri/conf.d"

    [plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runc.options]
      SystemdCgroup = true
  '';
  zfsDropin = pkgs.writeText "10-zfs.toml" ''
    [plugins."io.containerd.grpc.v1.cri".containerd]
      snapshotter = "zfs"

    [plugins."io.containerd.snapshotter.v1.zfs"]
      root_path = "/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs"
  '';
in
{
  # containerd runtime config placed under /var/lib via tmpfiles (environment.etc can't target
  # /var/lib). This is where the flox containerd runtime lands: the flox-nri-plugin at
  # /opt/nri/plugins/10-flox, which containerd launches to inject flox envs into workload containers.
  systemd.tmpfiles.rules = [
    "d /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d 0755 root root - -"
    "d /opt/nri/plugins 0755 root root - -"
    "d /etc/nri/conf.d 0755 root root - -"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/10-zfs.toml - - - - ${zfsDropin}"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/90-nri.toml - - - - ${nriDropin}"
    "L+ /opt/nri/plugins/10-flox - - - - ${floxNriPlugin}/bin/flox-nri-plugin"
  ];

  # Run the flox NRI plugin as a persistent service connecting to containerd's NRI
  # socket (standalone mode). containerd does not launch it from plugin_path on this
  # rke2/containerd build, so we own its lifecycle — the baked-model equivalent of
  # the old DaemonSet main-container. Restart=always reconnects across containerd
  # restarts and retries until the socket (/var/run/nri/nri.sock) is up.
  systemd.services.rke2lab-flox-nri-plugin = {
    description = "rke2lab flox NRI plugin (injects flox envs into workload containers)";
    after = [ "rke2-server.service" ];
    wants = [ "rke2-server.service" ];
    wantedBy = [ "multi-user.target" ];
    serviceConfig = {
      ExecStart = "${floxNriPlugin}/bin/flox-nri-plugin";
      Restart = "always";
      RestartSec = 5;
    };
  };

  # The zfs snapshotter's backing dataset — a legacy-mountpoint dataset (owned by the incus guest)
  # whose leaf is the NODE NAME (not the hostname): tank/rke2lab/control-nodes/<node-name>/containerd.
  # The dataset is created out-of-band by ndh from rke2lab's dataplan (the SSOT of the tank/rke2lab
  # layout — see docs/architecture/patterns/dataplan-single-source.adoc), materialised on the host
  # pool before the cluster. The node name is dynamic (per-node), so the mount cannot be a static
  # systemd.mounts unit — this oneshot reads it from the identity env file and mounts before rke2.
  # mount.zfs comes from pkgs.zfs on the unit PATH.
  systemd.services.rke2lab-zfs-containerd = {
    description = "rke2lab containerd zfs snapshotter dataset mount";
    after = [ "rke2lab-identity.service" ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      # node.env is OPTIONAL (leading `-`): a CAPRKE2-driven greenfield node carries no node.env (it
      # owns its own identity — mirroring rke2lab-identity's ConditionPathExists no-op), so
      # RKE2LAB_NODE_NAME is unset there and the script falls back to the node's own hostname.
      EnvironmentFile = "-/var/lib/rke2lab/node.env";
    };
    path = [
      pkgs.util-linux
      pkgs.zfs
    ];
    # The tank pool + its parents are materialised by this node's own NixOS disko (the pool is
    # in-container, so the node owns zfs create). The per-node containerd dataset is CREATED here
    # if absent, then mounted: a standalone node's `master` dataset is baked by disko (create is a
    # no-op → mount), a greenfield node's random-named dataset does not exist yet (create → mount) —
    # so the snapshotter mount succeeds regardless of how the node was named/provisioned.
    script = ''
      set -euo pipefail
      node="''${RKE2LAB_NODE_NAME:-$(cat /proc/sys/kernel/hostname)}"
      mountpoint=/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs
      dataset="tank/rke2lab/control-nodes/''${node}/containerd"
      install -d -m 0755 "$mountpoint"
      if ! zfs list -H -o name "$dataset" >/dev/null 2>&1; then
        zfs create -p -o mountpoint=legacy "$dataset"
      fi
      if ! mountpoint -q "$mountpoint"; then
        mount -t zfs "$dataset" "$mountpoint"
      fi
    '';
    preStop = ''
      mountpoint=/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs
      if ${pkgs.util-linux}/bin/mountpoint -q "$mountpoint"; then
        ${pkgs.util-linux}/bin/umount "$mountpoint"
      fi
    '';
  };
}
