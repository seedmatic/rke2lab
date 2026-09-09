# RKE2 server + the node's substrate-ready target. cidrs are DUAL-STACK — the spike proved a
# single-family node-ip crashes kube-apiserver ("service IP family must match public address
# family"). IPv4 primary, IPv6 secondary. They are PER-CLUSTER (10.<44+id>/10.<48+id>), written at
# boot by rke2lab-dualstack from devlxd (NOT a static literal — the image is homogeneous across
# clusters). cni="cilium" so rke2 deploys its bundled cilium as the CNI
# (the node reaches Ready WITHOUT waiting on Flux); the bootstrap lane (bootstrap-manifests.nix) seeds
# the HelmChartConfig rke2-cilium that customises that addon (BGP, clustermesh, gatewayAPI, …), and
# the seeded Flux operator then reconciles the rest from the rendered branch.
{ ... }:
{
  services.rke2 = {
    enable = true;
    role = "server";
    cni = "cilium";
  };

  # R1: hold the baked rke2-server until cloud-init has laid down its config — mgmt's node.env-derived
  # config.yaml.d drop-ins (below) + sops CA (./sops.nix), and a CAPRKE2 workload node's own
  # /etc/rancher/rke2/config.yaml (token + server URL, write_files in cloud-init.service). Without this
  # gate the wantedBy=multi-user rke2-server could start config-less and mis-cluster-init.
  systemd.services.rke2-server.after = [ "cloud-init.service" ];
  # Dual-stack cluster-cidr/service-cidr drop-in — PER-CLUSTER, so NOT a static image literal (the
  # homogeneous node-base image serves every cluster; their CIDRs differ). rke2lab-identity resolves
  # them from devlxd into /run/rke2lab/node.env; this oneshot writes rke2's config drop-in from there
  # before rke2-server reads config.yaml.d. Same runtime-drop-in pattern as rke2lab-tls-san.
  systemd.services.rke2lab-dualstack = {
    description = "rke2lab rke2 dual-stack cluster/service CIDR drop-in (per-cluster)";
    # Skip on a node whose channel carries no node.env (a CAPRKE2 workload node owns its rke2 config).
    unitConfig.ConditionPathExists = "/run/rke2lab/node.env";
    after = [ "rke2lab-identity.service" ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/run/rke2lab/node.env";
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/10-dualstack.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      {
        echo "cluster-cidr: ''${RKE2LAB_CLUSTER_POD_CIDR}"
        echo "service-cidr: ''${RKE2LAB_CLUSTER_SERVICE_CIDR}"
      } >"$dropin"
    '';
  };

  # rke2lab.target — the node's substrate-ready signal the seed-master systemd adapter probes as its
  # mandatory target. It aggregates exactly the rke2lab units that remain on this homogeneous node:
  # identity resolved (devlxd → node.env + hostname), the zfs snapshotter dataset mounted, and
  # rke2-server started. `wants` (weak) so the target still activates for the probe to read even if a
  # unit degraded — the adapter's snapshot reports failedUnits separately; `after` so the target only
  # goes active once the boot has actually reached this stage. Pulled into the boot by multi-user.
  systemd.targets.rke2lab = {
    description = "rke2lab node substrate ready";
    wants = [
      "rke2lab-identity.service"
      "rke2lab-zfs-containerd.service"
      "rke2-server.service"
    ];
    after = [
      "rke2lab-identity.service"
      "rke2lab-zfs-containerd.service"
      "rke2-server.service"
    ];
    wantedBy = [ "multi-user.target" ];
  };

  # Deterministic apiserver SAN. The operator's probe reaches the API at the node's mDNS name
  # (<cluster>-<node>.local — avahi advertises it on lan0; the lan0 IP itself is router-DHCP and NOT
  # stable, so the NAME is the deterministic endpoint, not the address). rke2 issues the apiserver
  # serving cert from the seeded server-ca and must carry that name in its SAN, or the kubeconfig
  # (server = https://<cluster>-<node>.local:6443, CA = the ndh root) fails TLS. rke2 reads tls-san
  # from config.yaml.d before issuing the cert; this oneshot writes the drop-in at runtime (the name
  # is per-node) after identity resolves the hostname, before rke2-server.
  systemd.services.rke2lab-tls-san = {
    description = "rke2lab apiserver tls-san drop-in (<cluster>-<node>[.local])";
    # Skip on a node whose channel carries no node.env (a CAPRKE2 workload node owns its rke2 config).
    unitConfig.ConditionPathExists = "/run/rke2lab/node.env";
    after = [ "rke2lab-identity.service" ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/run/rke2lab/node.env";
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/20-tls-san.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      {
        echo "tls-san:"
        echo "  - ''${RKE2LAB_NODE_HOSTNAME}"
        echo "  - ''${RKE2LAB_NODE_HOSTNAME}.local"
      } >"$dropin"
    '';
  };

  # Kubelet node-labels — MUST exist in config.yaml.d before rke2-server starts, because
  # `--node-labels` is applied only at the node's FIRST registration; a fragment delivered
  # later (the former RKE2_CONFIG ConfigMap glob'd from the cluster by rke2lab-config-install.sh)
  # arrives AFTER the join and is silently ignored. Same boot-time drop-in pattern as
  # rke2lab-dualstack / rke2lab-tls-san, from per-node identity in node.env. This SUPERSEDES the
  # node-labels fragment RuntimeRke2ConfigManifestsUnit used to emit.
  systemd.services.rke2lab-node-labels = {
    description = "rke2lab kubelet node-labels drop-in (per-node identity + flox-runtime)";
    # Skip on a node whose channel carries no node.env (a CAPRKE2 workload node owns its rke2 config).
    unitConfig.ConditionPathExists = "/run/rke2lab/node.env";
    after = [ "rke2lab-identity.service" ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/run/rke2lab/node.env";
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/30-node-labels.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      {
        echo "node-label:"
        echo "  - node.kubernetes.io/instance-name=''${RKE2LAB_NODE_NAME}"
        echo "  - node.kubernetes.io/instance-kind=''${RKE2LAB_NODE_KIND}"
        # flox-runtime: every node runs flox in the current homogeneous lab. Promote to a
        # devlxd per-node key (user.rke2lab.node-flox-enabled) once node roles diverge.
        # MUST match ManifestAnnotations.NODE_FLOX_RUNTIME_LABEL (the DaemonSet nodeSelectors).
        echo "  - flox.seedmatic.io/enabled=true"
      } >"$dropin"
    '';
  };
}
