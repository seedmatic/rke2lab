# The server layer (nixosConfigurations.rke2-node-server): node-base, which is an agent, plus what only a server
# carries: the role, the CNI it deploys, the addons it disables, the bring-your-own CA (./sops.nix) and the root
# control plane's config installer. The base's units follow the role through rke2lab.rke2Unit.
{ config, lib, ... }:
{
  imports = [ ./sops.nix ];

  services.rke2 = {
    role = "server";
    cni = "cilium";
    # A server reads rke2's default config path without the flag, as it always has.
    configPath = null;

      # Disable rke2's embedded cloud-controller: we are NOT on the "rke2" cloud — every node is an
      # incus instance under Cluster API (CAPN). Left on, the CCM stamps the node providerID
      # `rke2://<node>` (immutable once set), which never matches the `lxc:///<node>` CAPN/CAPRKE2
      # require for the Machine↔Node bind, AND it would try to resolve the `lxc:///` id against the
      # rke2 cloud (fail → risk of tainting/deleting the node). The node's providerID is set instead by
      # the kubelet `provider-id` arg (rke2lab-provider-id, mgmt nodes) or CAPN's CloudProviderNodePatch
      # (workload nodes). kube-vip + cilium cover what the CCM would otherwise do (LB + node readiness).
      # Before the base's flags, the order every server has had.
      extraFlags = lib.mkBefore [
        "--disable-cloud-controller"
        # STATIC disables (same for every cluster) — reconciled here from the retired RKE2_CONFIG
        # `disable.yaml` ConfigMap (RuntimeRke2ConfigManifestsUnit), whose glob installer
        # (rke2lab-config-install.sh) was removed, so these silently came back on. rke2-ingress-nginx:
        # we route ingress via EnvoyGateway, never nginx. rke2-snapshot-controller + its validation
        # webhook: openebs-zfs ships its OWN snapshot-controller, so RKE2's is a second controller
        # reconciling the same VolumeSnapshot CRs. The snapshot-controller-CRD stays ENABLED (not
        # listed) — openebs-zfs hard-fails at startup without the VolumeSnapshot{,Content,Class} CRDs.
        "--disable=rke2-ingress-nginx"
        # Since rke2 1.36, rke2 deploys traefik when a NEW cluster names no ingress controller (bootstrap.go: no HelmChart
        # present → IngressItems[0] = traefik; 1.34 defaulted to ingress-nginx), with its CRDs as a second chart.
        "--disable=rke2-traefik"
        "--disable=rke2-traefik-crd"
        "--disable=rke2-snapshot-controller"
        "--disable=rke2-snapshot-validation-webhook"
      ];
  };

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
    before = [ config.rke2lab.rke2Unit ];
    requiredBy = [ config.rke2lab.rke2Unit ];
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
}
