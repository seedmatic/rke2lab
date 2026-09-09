# rke2lab cloud-init datasource shim — the guest-side bridge that lets `services.cloud-init` consume
# the incus-provided `cloud-init.user-data` on a CONTAINER node-base. This is the foundation of the
# uniform bootstrap channel: both seed-master (standalone mgmt grow) and CAPN/CAPRKE2 (in-cluster
# workload grow) deliver via `cloud-init.user-data`, and this module is what makes the guest read it.
#
# Why a shim (not stock cloud-init): cloud-init's LXD datasource hardcodes /dev/lxd/sock and has no
# incus support; incus guests expose ONLY /dev/incus/sock (no compat symlink), and incus's own NoCloud
# seeding is performed by incus-agent — which runs in VMs, not containers. So on this privileged
# CONTAINER substrate neither the LXD datasource nor agent-seeding fires. This oneshot, ordered before
# cloud-init-local, reads the user-data off the same devlxd socket ./identity.nix already uses and lays
# a NoCloud seed under /var/lib/cloud/seed/nocloud, which cloud-init's NoCloud datasource consumes.
#
# Transition-safe: an instance carrying NO `cloud-init.user-data` (today's devlxd-only grow) gets no
# seed → cloud-init finds no datasource → stays inert → the devlxd oneshots (identity/sops/…) run
# unchanged. The source migration (identity/sops/InstanceGrow → cloud-init) rides on top in later steps.
{ pkgs, ... }:
{
  services.cloud-init = {
    enable = true;
    # cloud-init delivers CONFIG only; networking stays owned by the NixOS/netplan substrate.
    network.enable = false;
    settings.datasource_list = [ "NoCloud" ];
  };

  systemd.services.rke2lab-cloud-init-seed = {
    description = "rke2lab NoCloud seed from incus devlxd (cloud-init.user-data → /var/lib/cloud/seed)";
    # Bind to cloud-init-local.service (the NoCloud datasource probe) itself — a normal-deps service
    # WantedBy=multi-user.target on NixOS — NOT cloud-init.target (this setup never activates it, so a
    # unit wantedBy it never runs). wantedBy pulls the seed in whenever the probe runs; before orders
    # it first, so the seed exists at /var/lib/cloud/seed/nocloud when the probe reads it.
    before = [ "cloud-init-local.service" ];
    wantedBy = [ "cloud-init-local.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [ pkgs.curl ];
    script = ''
      set -euo pipefail
      sock=/dev/incus/sock
      # devlxd exposes cloud-init.* / user.* keys at /1.0/config/<key>; a 404 means this instance
      # carries no user-data (a devlxd-only grow) — leave no seed and let cloud-init stay inert.
      userdata=""
      for _ in $(seq 1 20); do
        if userdata="$(curl -sf --unix-socket "$sock" "http://x/1.0/config/cloud-init.user-data")"; then
          break
        fi
        userdata=""
        sleep 0.5
      done
      if [ -z "$userdata" ]; then
        echo "rke2lab-cloud-init-seed: no cloud-init.user-data on devlxd — leaving cloud-init inert" >&2
        exit 0
      fi
      seed=/var/lib/cloud/seed/nocloud
      install -d -m 0755 "$seed"
      umask 077
      printf '%s' "$userdata" >"$seed/user-data"
      # NoCloud requires a meta-data doc (at least instance-id). Prefer incus's cloud-init-compatible
      # meta-data; fall back to a fresh id (a container boots from an immutable image, so re-running
      # per-instance modules each boot is correct).
      if ! curl -sf --unix-socket "$sock" "http://x/1.0/meta-data" >"$seed/meta-data" \
        || [ ! -s "$seed/meta-data" ]; then
        printf 'instance-id: rke2lab-%s\n' "$(cat /proc/sys/kernel/random/uuid)" >"$seed/meta-data"
      fi
    '';
  };
}
