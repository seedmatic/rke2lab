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
{ config, pkgs, ... }:
{
  services.rke2 = {
    enable = true;
    role = "server";
    cni = "cilium";
    # Disable rke2's embedded cloud-controller: we are NOT on the "rke2" cloud — every node is an
    # incus instance under Cluster API (CAPN). Left on, the CCM stamps the node providerID
    # `rke2://<node>` (immutable once set), which never matches the `lxc:///<node>` CAPN/CAPRKE2
    # require for the Machine↔Node bind, AND it would try to resolve the `lxc:///` id against the
    # rke2 cloud (fail → risk of tainting/deleting the node). The node's providerID is set instead by
    # the kubelet `provider-id` arg (rke2lab-provider-id, mgmt nodes) or CAPN's CloudProviderNodePatch
    # (workload nodes). kube-vip + cilium cover what the CCM would otherwise do (LB + node readiness).
    extraFlags = [
      "--disable-cloud-controller"
      # STATIC disables (same for every cluster) — reconciled here from the retired RKE2_CONFIG
      # `disable.yaml` ConfigMap (RuntimeRke2ConfigManifestsUnit), whose glob installer
      # (rke2lab-config-install.sh) was removed, so these silently came back on. rke2-ingress-nginx:
      # we route ingress via EnvoyGateway, never nginx. rke2-snapshot-controller + its validation
      # webhook: openebs-zfs ships its OWN snapshot-controller, so RKE2's is a second controller
      # reconciling the same VolumeSnapshot CRs. The snapshot-controller-CRD stays ENABLED (not
      # listed) — openebs-zfs hard-fails at startup without the VolumeSnapshot{,Content,Class} CRDs.
      "--disable=rke2-ingress-nginx"
      "--disable=rke2-snapshot-controller"
      "--disable=rke2-snapshot-validation-webhook"
    ];
  };

  # R1: hold the baked rke2-server until cloud-init has laid down its config — mgmt's node.env-derived
  # config.yaml.d drop-ins (below) + sops CA (./sops.nix), and a CAPRKE2 workload node's own
  # /etc/rancher/rke2/config.yaml (token + server URL, write_files in cloud-init.service). Without this
  # gate the wantedBy=multi-user rke2-server could start config-less and mis-cluster-init.
  systemd.services.rke2-server.after = [ "cloud-init.service" ];

  # The rke2 config installer for the self/root control-plane node. The per-cluster config (dual-stack
  # CIDRs, tls-san incl. the VIP) is rendered per (cluster × pool) onto the management branch by
  # RuntimeRke2ConfigManifestsUnit as non-secret ConfigMaps; this runs `nix run
  # <branch>#install-rke2-config`, which globs the branch for the RKE2_CONFIG fragments of THIS node's
  # own cluster (namespace rke2lab-<cluster>, derived from the hostname) and extracts each into
  # config.yaml.d. It gates on rke2-config.env; a CAPRKE2 workload node takes its config from its
  # RKE2Config (seed-incluster), not this app. Inputs arrive as files at fixed paths:
  #   - rke2-config.env : RKE2LAB_MANIFESTS_REF/REV (the branch + pinned rev to fetch) — non-secret.
  #   - nix-github.conf : an `access-tokens = github.com=<token>` nix.conf line (fresh App token,
  #                       root-only). SINGLE-USE: the script sets NIX_CONFIG="!include <file>" for
  #                       this run only (not a permanent unit env — that would outlive the value) and
  #                       DELETES the file on exit (trap), so no live/stale token lingers on the node.
  systemd.services.rke2lab-rke2-config = {
    description = "rke2lab rke2 config install (nix run <branch>#install-rke2-config)";
    # Gated on the ref file the grower delivers (not node.env).
    unitConfig.ConditionPathExists = "/run/rke2lab/rke2-config.env";
    after = [
      "cloud-init.service"
      "network-online.target"
      # install-rke2-config derives THIS node's cluster from its hostname (<cluster>-<node>) to filter
      # the branch to its own config; rke2lab-identity sets that hostname (transient, from node.env),
      # so it must run first — else the app reads the image's baked default and the filter matches
      # nothing. A no-op ordering on a workload node where rke2lab-identity is condition-skipped.
      "rke2lab-identity.service"
    ];
    wants = [ "network-online.target" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      # The external network can lag a few seconds past network-online.target at boot (github briefly
      # unreachable), so the script retries — give it room before systemd's start timeout fires.
      TimeoutStartSec = "600";
      EnvironmentFile = "/run/rke2lab/rke2-config.env";
    };
    script = ''
      set -euo pipefail
      # The grower MUST deliver the token alongside rke2-config.env (which gated this oneshot in) —
      # fail LOUD if absent rather than 404 anonymously. It is NOT erased on failure: a boot-time
      # fetch can fail transiently (external network not up yet), and the token must survive the retry.
      if [ ! -f /run/rke2lab/nix-github.conf ]; then
        echo "[rke2lab-rke2-config] FATAL: /run/rke2lab/nix-github.conf missing —" \
             "the grower must deliver the github token alongside rke2-config.env" >&2
        exit 1
      fi
      export NIX_CONFIG="!include /run/rke2lab/nix-github.conf"
      url="github:seedmatic/rke2lab?ref=$RKE2LAB_MANIFESTS_REF"
      if [ -n "''${RKE2LAB_MANIFESTS_REV:-}" ]; then
        url="$url&rev=$RKE2LAB_MANIFESTS_REV"
      fi
      echo "[rke2lab-rke2-config] installing rke2 config from $url"
      # Retry until the branch fetch succeeds: at boot the external network lags network-online, so
      # github is briefly unreachable and nix's own ~4s retry is too short. The token is kept across
      # retries (until succeeds without tripping set -e); only on SUCCESS is it erased below.
      attempt=1
      until ${config.nix.package}/bin/nix run \
              --extra-experimental-features "nix-command flakes" \
              --no-write-lock-file \
              "$url#install-rke2-config"; do
        if [ "$attempt" -ge 20 ]; then
          echo "[rke2lab-rke2-config] FATAL: install failed after $attempt attempts" \
               "(external network never came up?)" >&2
          exit 1
        fi
        echo "[rke2lab-rke2-config] attempt $attempt failed (github unreachable?); retry in 10s"
        attempt=$((attempt + 1))
        sleep 10
      done
      # Consumed: erase the single-use token so no live token lingers in the node's /run.
      rm -f /run/rke2lab/nix-github.conf
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

  # Kubelet node-labels — MUST exist in config.yaml.d before rke2-server starts, because
  # `--node-labels` is applied only at the node's FIRST registration; a fragment delivered later
  # arrives AFTER the join and is silently ignored. This is PER-NODE (RKE2LAB_NODE_* from node.env),
  # so it stays a boot-time oneshot here rather than a rendered branch fragment. It must run AFTER
  # rke2lab-rke2-config (which wipes+reinstalls config.yaml.d from the branch) so this drop-in
  # survives — that ordering is declared on rke2lab-rke2-config below.
  systemd.services.rke2lab-node-labels = {
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
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
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
  # right at first registration (Node.spec.providerID is immutable). Per-node (the name is in
  # node.env), so a runtime drop-in like rke2lab-node-labels; a CAPRKE2 workload node (no node.env)
  # skips this and takes its providerID from CAPN's CloudProviderNodePatch instead.
  systemd.services.rke2lab-provider-id = {
    description = "rke2lab kubelet provider-id drop-in (lxc:///<node> for CAPI adoption)";
    unitConfig.ConditionPathExists = "/var/lib/rke2lab/node.env";
    # After rke2lab-rke2-config's config.yaml.d wipe+reinstall, so this drop-in survives.
    after = [
      "rke2lab-identity.service"
      "rke2lab-rke2-config.service"
    ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/var/lib/rke2lab/node.env";
    };
    script = ''
      set -euo pipefail
      dropin=/etc/rancher/rke2/config.yaml.d/40-provider-id.yaml
      install -d -m 0755 "$(dirname "$dropin")"
      {
        # `kubelet-arg+` APPENDS: rke2 config.yaml.d REPLACES a list key with the alphabetically-last
        # file's value, and the 35-node-ip.yaml drop-in (rke2lab-node-ip, below) also sets
        # `kubelet-arg` (for --node-ip) and sorts BEFORE this file — so a plain `kubelet-arg:` here
        # would clobber it, the node registered with NO spec.providerID, and CAPI could never bind the
        # Machine to it (NodeHealthy stuck "Waiting for a Node with spec.providerID lxc:///<node> to
        # exist"). Both producers use the `+` append form so node-ip and provider-id coexist.
        echo "kubelet-arg+:"
        # RKE2LAB_NODE_HOSTNAME (the full <cluster>-<node>), NOT RKE2LAB_NODE_NAME (the short ref
        # `master`): the providerID must equal the incus instance name (= the hostname), which is
        # what CAPN/the LXCMachine carry.
        echo "  - provider-id=lxc:///''${RKE2LAB_NODE_HOSTNAME}"
      } >"$dropin"
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
  systemd.services.rke2lab-node-ip = {
    description = "rke2lab kubelet node-ip drop-in (the node's own dual-stack vmnet0 address)";
    after = [
      "rke2lab-identity.service"
      "rke2lab-rke2-config.service"
      "network-online.target"
    ];
    wants = [ "network-online.target" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
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
      {
        echo "node-ip: $nodeip"
        echo "kubelet-arg+:"
        echo "  - node-ip=$nodeip"
      } >"$dropin"
    '';
  };
}
