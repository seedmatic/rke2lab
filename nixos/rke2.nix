# RKE2 server + the node's substrate-ready target. The per-CLUSTER rke2 config (dual-stack CIDRs,
# apiserver tls-san incl. the kube-vip VIP, …) is NOT baked here — the node-base image is homogeneous
# across clusters. It is rendered per (cluster × pool) onto the MANAGEMENT cluster's manifests/<mgmt>
# branch by RuntimeRke2ConfigManifestsUnit and installed into config.yaml.d at boot by `nix run
# <branch>#install-rke2-config` (rke2lab-rke2-config, below) for the self/root control-plane node; a
# CAPRKE2 workload node takes it from its RKE2Config instead. Only the STATIC, cluster-invariant knobs
# live here (extraFlags disables). The PER-NODE facts (node-ip, node-labels, provider-id) are the
# node's own address/identity, resolved on-node by the oneshots below (never rendered per node); the
# rke2 join token is owned by CAPRKE2's bootstrap provider, not rendered on the branch.
# cni="cilium" so rke2 deploys its bundled cilium as the CNI
# (the node reaches Ready WITHOUT waiting on Flux); the bootstrap lane (bootstrap-manifests.nix) seeds
# the HelmChartConfig rke2-cilium that customises that addon (BGP, clustermesh, gatewayAPI, …), and
# the seeded Flux operator then reconciles the rest from the rendered branch.
{
  config,
  lib,
  pkgs,
  ...
}:
let
  unit = config.rke2lab.rke2Unit;
in
{
  # The one name of the node's rke2 unit. nixpkgs names it after the role (`rke2-server` or `rke2-agent`, the name
  # CAPRKE2's bootstrap enables), so every unit that orders against rke2 names it through this, never literally.
  options.rke2lab.rke2Unit = lib.mkOption {
    type = lib.types.str;
    readOnly = true;
    default = "rke2-${config.services.rke2.role}.service";
    description = "The node's rke2 systemd unit, named after services.rke2.role.";
  };

  config.services.rke2 = {
    enable = true;
    # The base is an agent; the server layer (./server.nix) sets the role, the CNI and the server's flags.
    role = lib.mkDefault "agent";
    # rke2's own default path, named so nixpkgs knows an agent's server URL and token come from it: CAPRKE2
    # writes it through cloud-init.
    configPath = lib.mkDefault "/etc/rancher/rke2/config.yaml";
    extraFlags = [
      # Hand kubelet resolved's UPLINK resolv.conf, so a pod resolves what the NODE resolves: the
      # bare-metal's dnsmasq, hence the `<host>` zone that names our Incus endpoint. Left to the
      # default kubelet reads /etc/resolv.conf, which under resolved is the STUB (127.0.0.53) — a
      # loopback nameserver, which rke2 refuses, replacing it with a `8.8.8.8` file every
      # dnsPolicy=Default pod inherits. Measured 2026-09-26 on a cold-started bioskop-mgmt: CAPN could
      # not build its Incus client (`lookup nixos.bioskop on 10.48.0.10:53: no such host`), so
      # LXCCluster/LXCMachine never provisioned and the whole adoption held — while TCP to
      # 172.16.0.1:8443 from that same pod was OPEN. The path was never the problem, only the name.
      # ⚠️ One change with `IPv6AcceptRA.UseDNS = false` in ./network.nix, which is what makes this
      # file acceptable to that same refusal — the full reasoning lives in its header.
      "--resolv-conf=/run/systemd/resolve/resolv.conf"
    ];
  };

  # R1: hold the baked rke2-server until cloud-init has laid down its config — mgmt's node.env-derived
  # config.yaml.d drop-ins (below) + sops CA (./sops.nix), and a CAPRKE2 workload node's own
  # /etc/rancher/rke2/config.yaml (token + server URL, write_files in cloud-init.service). Without this
  # gate the wantedBy=multi-user rke2-server could start config-less and mis-cluster-init.
  #
  # `requires`, not only `after`: ordering waits for cloud-init to END, not to SUCCEED. On T4's first boot
  # (2026-10-26) cloud-init found no datasource, failed, and rke2-server cluster-inited with defaults (no
  # tls-san, the default CIDRs), which no later config can undo. A failed cloud-init now stops rke2.
  #
  # zfs on the unit's PATH: the kubelet's cAdvisor (measured on rke2 1.36, cAdvisor v0.56.2) takes its ZFS branch when /dev/zfs
  # exists, which it does on our nodes, and execs `zfs get` to size the rootfs; with no zfs on PATH it gets no
  # filesystem for `/` and the kubelet fails « failed to get rootfs info ». nixpkgs adds zfs only when
  # boot.zfs.enabled, which our nodes leave off on purpose (./zfs.nix): the userland alone, the host owns the module.
  config.systemd.services.${lib.removeSuffix ".service" unit} = {
    after = [ "cloud-init.service" ];
    requires = [ "cloud-init.service" ];
    path = [ pkgs.zfs ];
  };

  # rke2lab.target — the node's substrate-ready signal the seed-outcluster systemd adapter probes as its
  # mandatory target. It aggregates exactly the rke2lab units that remain on this homogeneous node:
  # identity resolved (devlxd → node.env + hostname), the zfs snapshotter dataset mounted, and
  # rke2-server started. `wants` (weak) so the target still activates for the probe to read even if a
  # unit degraded — the adapter's snapshot reports failedUnits separately; `after` so the target only
  # goes active once the boot has actually reached this stage. Pulled into the boot by multi-user.
  config.systemd.targets.rke2lab = {
    description = "rke2lab node substrate ready";
    wants = [
      "rke2lab-identity.service"
      "rke2lab-zfs-containerd.service"
      unit
    ];
    after = [
      "rke2lab-identity.service"
      "rke2lab-zfs-containerd.service"
      unit
    ];
    wantedBy = [ "multi-user.target" ];
  };

  # Kubelet node-labels — MUST exist in config.yaml.d before rke2-server starts, because
  # `--node-labels` is applied only at the node's FIRST registration; a fragment delivered later
  # arrives AFTER the join and is silently ignored. This is PER-NODE (RKE2LAB_NODE_* from node.env),
  # so it stays a boot-time oneshot here rather than a rendered branch fragment. It must run AFTER
  # rke2lab-rke2-config (which wipes+reinstalls config.yaml.d from the branch) so this drop-in
  # survives — that ordering is declared on rke2lab-rke2-config below.
  config.systemd.services.rke2lab-node-labels = {
    description = "rke2lab kubelet node-labels drop-in (per-node identity + flox-runtime)";
    # Skip on a node whose channel carries no node.env (a CAPRKE2 workload node owns its rke2 config).
    unitConfig.ConditionPathExists = "/var/lib/rke2lab/node.env";
    # After rke2lab-rke2-config: it wipes+reinstalls config.yaml.d from the branch, so this per-node
    # drop-in must land afterwards to survive.
    after = [
      "rke2lab-identity.service"
      "rke2lab-rke2-config.service"
    ];
    requires = [ "rke2lab-identity.service" ];
    before = [ unit ];
    requiredBy = [ unit ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/var/lib/rke2lab/node.env";
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

  # The node's providerID for Cluster API adoption. With the rke2 cloud-controller disabled (above),
  # nothing stamps a providerID; the kubelet sets its own to `lxc:///<node>` — the SAME id the CAPN
  # LXCMachine carries (GetExpectedProviderID), so the CAPI Machine↔Node bind succeeds. It MUST be
  # right at first registration (Node.spec.providerID is immutable).
  #
  # RUNS ON EVERY NODE, and the id comes from the node's own HOSTNAME — not from node.env. This unit
  # was gated like its node-labels sibling and read RKE2LAB_NODE_HOSTNAME, on the belief that a CAPN
  # node would get its providerID from CAPN's own remote patch instead. It does — but only if it is
  # the FIRST machine of its control plane. Measured 2026-09-24 on bioskop-wrkld:
  #
  #   controller_normal.go:57  "ControlPlane is initialized, waiting for Machine to become Running
  #                             before updating control plane load balancer"  phase="Provisioned"
  #
  # CAPN returns there every 10s and never reaches its node_patch, because it defers the patch until
  # the Machine is Running — while CAPI promotes a Machine to Running only once it has a nodeRef,
  # which it can only find by matching the Node's providerID. The patch waits on the state only the
  # patch can produce, so a SECOND control-plane machine deadlocks: Node Ready, Machine parked at
  # Provisioned, the rollout stalled with no outage and no signal. Stamping the providerID by hand
  # released the whole sequence in one reconcile (load balancer updated, old machine drained), which
  # is what confirms the cycle.
  #
  # A node can always state this itself: the incus instance name IS the container's hostname AND the
  # kubelet's node name, so `lxc:///$(uname -n)` is correct on both node kinds — verified against
  # what CAPN itself writes (`providerID="lxc:///bioskop-mgmt-master" nodeName="bioskop-mgmt-master"`).
  # With every node stamping its own, CAPN's patch becomes a no-op and the deadlock never forms.
  #
  # ★ Third instance of one gate copied by analogy (node-ip was the second, same day): the sibling
  # genuinely READS its identity out of node.env, this one only needs its own name. `requires` on
  # rke2lab-identity is dropped with the gate — identity is legitimately node.env-gated, so requiring
  # it would re-import the condition through the back door. The ordering stays: when identity does
  # run, it sets the transient hostname, so it must run first.
  config.systemd.services.rke2lab-provider-id = {
    description = "rke2lab kubelet provider-id drop-in (lxc:///<node> for CAPI adoption)";
    # After rke2lab-rke2-config's config.yaml.d wipe+reinstall, so this drop-in survives.
    after = [
      "rke2lab-identity.service"
      "rke2lab-rke2-config.service"
    ];
    before = [ unit ];
    requiredBy = [ unit ];
    path = [ pkgs.coreutils ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/40-provider-id.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      # `uname -n`, not RKE2LAB_NODE_HOSTNAME: the full <cluster>-<node>, which is the incus instance
      # name AND the kubelet's node name. The short ref (`master`) would not match either.
      nodename="$(uname -n)"
      # `kubelet-arg+` APPENDS: rke2 config.yaml.d REPLACES a list key with the alphabetically-last
      # file's value, and the 35-node-ip.yaml drop-in (rke2lab-node-ip, below) also sets
      # `kubelet-arg` (for --node-ip) and sorts BEFORE this file — so a plain `kubelet-arg:` here
      # would clobber it, the node registered with NO spec.providerID, and CAPI could never bind the
      # Machine to it (NodeHealthy stuck "Waiting for a Node with spec.providerID lxc:///<node> to
      # exist"). Both producers use the `+` append form so node-ip and provider-id coexist.
      cat >"$dropin" <<EOF
      kubelet-arg+:
        - provider-id=lxc:///$nodename
      EOF
    '';
  };

  # The node's --node-ip, its own dual-stack vmnet0 address (the DHCP reservation the vmnet bridge
  # pins from the blueprint). PER-NODE (the address is the node's own), so an on-node oneshot read
  # LIVE from vmnet0 — never rendered per node on the branch. rke2 does NOT propagate the `node-ip`
  # config to the kubelet's `--node-ip` for a DHCP-`dynamic` address (our vmnet addresses are DHCP
  # reservations), so the kubelet auto-detects and registers cilium_host (a pod-cidr IP absent from
  # the serving cert) → x509 mismatch breaking `kubectl logs`/`exec` + metrics. Forcing --node-ip via
  # kubelet-arg (the documented escape hatch) pins InternalIP to node-ip, which IS in the cert.
  # RUNS ON EVERY NODE — deliberately NOT gated on node.env, unlike its two siblings above.
  #
  # It was, by analogy with them, and the analogy was false: node-labels and provider-id genuinely
  # READ the node's identity out of node.env (its name, its labels), so a CAPN node that has no
  # node.env legitimately takes those from CAPN instead. This one reads nothing but `ip addr show
  # dev vmnet0`, so the gate bought nothing and cost the workload cluster its control plane.
  #
  # Measured 2026-09-24, restart counter 102 on bioskop-wrkld-control-plane-d4977:
  #
  #   fatal: cluster-cidr: [10.45.0.0/16 fd00:45::/56] and node-ip: [172.16.0.9],
  #   must share the same IP version
  #
  # Skipped here, nothing sets node-ip at all (checked: absent from config.yaml and every drop-in),
  # so rke2 AUTO-DETECTS it from the default-route device — `fabric0`. That used to be harmless because
  # fabric0 sat on the home LAN and carried a v6 alongside its v4, making the auto-detected pair
  # dual-stack by accident. fabric0 now sits on `fabric-br`, which is declared `ipv6.address = none`, so
  # the pair collapsed to v4 while cluster-cidr stayed dual — and rke2 refuses the mismatch.
  #
  # ★ The gate's cost was invisible until the bridge moved: a condition that skipped the ONE unit
  # able to state the answer, on nodes that could not state it any other way.
  #
  # `requires` on rke2lab-identity is dropped with the gate for the same reason — identity IS
  # node.env-gated, legitimately, so requiring it would re-import the condition through the back
  # door. The ordering stays: when identity does run, this must follow it.
  config.systemd.services.rke2lab-node-ip = {
    description = "rke2lab kubelet node-ip drop-in (the node's own dual-stack vmnet0 address)";
    after = [
      "rke2lab-identity.service"
      "rke2lab-rke2-config.service"
      "network-online.target"
    ];
    wants = [ "network-online.target" ];
    before = [ unit ];
    requiredBy = [ unit ];
    path = [ pkgs.iproute2 pkgs.gawk pkgs.coreutils ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/35-node-ip.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      # BOTH families, guaranteed by ORDERING rather than checked here: vmnet0 declares
      # `RequiredFamilyForOnline = "both"` (./network.nix), so network-online.target — which this
      # unit is ordered after — cannot be reached until the stateful DHCPv6 lease has landed.
      #
      # This used to degrade to v4-only when the v6 was absent, "rather than wedge the boot on a
      # lagging v6 lease". The trade was FALSE: cluster-cidr is podCidrDualStack(), unconditionally
      # "<v4>,<v6>", so a v4-only node-ip can NEVER be valid — rke2 refuses it outright (measured
      # 2026-09-24: `node-ip: [10.80.8.5]` against `[10.45.0.0/16 fd00:45::/56]`). The fallback did
      # not avoid a wedge, it turned a WAIT into a crash-loop whose message blamed the address while
      # the cause was the lease.
      #
      # ★ And the wait belonged in the ORDERING, not in a poll here: the dependency already existed,
      # it was merely under-specified ("routable" is satisfied by the v4 alone). A defensive branch
      # with no valid case is worse than none — it converts "not yet" into "wrong", losing the reason.
      v4="$(ip -4 -o addr show dev vmnet0 scope global | awk '{print $4}' | cut -d/ -f1 | head -n1)"
      v6="$(ip -6 -o addr show dev vmnet0 scope global | awk '{print $4}' | cut -d/ -f1 | head -n1)"
      if [ -z "$v4" ] || [ -z "$v6" ]; then
        echo "[rke2lab-node-ip] FATAL: vmnet0 is not dual-stack past network-online" \
             "(v4='$v4' v6='$v6') — RequiredFamilyForOnline=both should have made this" \
             "unreachable, so suspect the link match or the DHCPv6 reservation, not this unit." >&2
        exit 1
      fi
      nodeip="$v4,$v6"
      # A heredoc, so the drop-in's SHAPE is visible here — it is YAML, where the two-space indent of
      # the list item is load-bearing. Nix strips the common indentation of this indented-string
      # block, leaving the body at column 0 and the list item at two, exactly as written. (Do not
      # name the nix indented-string delimiter in this comment: two single quotes START AN ESCAPE
      # inside it, so writing them here terminates the script itself — measured, one eval ago.)
      cat >"$dropin" <<EOF
      node-ip: $nodeip
      kubelet-arg+:
        - node-ip=$nodeip
      EOF
    '';
  };
}
