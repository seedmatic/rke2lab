# Nix substrate config + the node toolbox on PATH. The declarative form of what
# rke2lab-nix-install.sh + rke2lab-flox-install.sh wrote into /etc/nix/{nix,flox}.conf and of the
# two former nocloud + rke2 flox envs — now plain systemPackages. On NixOS the closure IS the env
# (no `flox activate` to put yq on PATH); flox itself is kept ONLY for its irreplaceable role, the
# containerd NRI workload runtime (see ./containerd.nix).
{
  pkgs,
  flox,
  upstreamCaches,
  ...
}:
{
  # NOT translated here (handled elsewhere on purpose):
  #   - access-tokens (github.com=<secret>): a secret → injected per-node via sops-nix, never baked.
  #   - extra-platforms=aarch64-darwin + the hardcoded qemu-x86_64-binfmt sandbox path: a Mac /
  #     emulation artifact (that store path won't exist on NixOS). x86_64 emulation, if wanted,
  #     is boot.binfmt.emulatedSystems — but in a container binfmt comes from the host kernel.
  nix.settings = {
    experimental-features = [
      "nix-command"
      "flakes"
      "ca-derivations"
    ];
    # The upstream caches from nix-flake-commons, which every seedmatic flake shares. cache.nixos.org and its key
    # are NixOS's own default, appended after these; restating them listed it twice.
    substituters = [ upstreamCaches.aseippFastly.substituter ];
    # flox public store (mirrors the family's flox.conf).
    extra-trusted-substituters = [ upstreamCaches.flox.substituter ];
    extra-trusted-public-keys = [
      upstreamCaches.flox.publicKey
      # floxhub's, which only the nodes' flox runtime needs: kept here, not shared.
      "floxhub-1:0QOAlcobcEvq1mqEf4qAYCaWnTTOXpyoRv/PmqfSixM="
    ];
    trusted-users = [
      "root"
      "@wheel"
    ];
    sandbox = false;
    accept-flake-config = true;
    # flox.conf tuning: fail fast on flaky Fastly routing to cache.nixos.org; GC thresholds.
    connect-timeout = 10;
    stalled-download-timeout = 30;
    min-free = 128000000;
    max-free = 1000000000;
  };

  # The node toolbox, baked on PATH: the union of what the former nocloud + rke2 flox envs installed
  # (yq-go/dasel/git/gh + kubectl/helm). flox itself is kept for the NRI plugin (./containerd.nix).
  #
  # What the flox profiles ALSO carried, and where it goes instead (NOT here):
  #   - /srv/host RKE2LAB_* path vars  -> dissolve with the /srv/host delivery mechanism itself.
  #   - RKE2LAB_NODE_{ID,NAME,KIND}    -> per-node identity, injected via a generated EnvironmentFile.
  #   - .secrets tokens (flox)         -> sops-nix (as secret env vars).
  environment.systemPackages = [
    flox.packages.${pkgs.stdenv.hostPlatform.system}.default
    pkgs.yq-go
    pkgs.dasel
    pkgs.git
    pkgs.gh
    pkgs.kubectl
    pkgs.kubernetes-helm
    # cilium-cli — `cilium status`, `cilium bgp peers`, `clustermesh` from the node's root shell.
    pkgs.cilium-cli
    # zfs userspace: the containerd zfs snapshotter shells `zfs`/`zpool` for its per-container
    # datasets, and rke2lab-zfs-containerd.service needs `mount.zfs` to mount the legacy dataset (it
    # also carries pkgs.zfs on its own unit PATH). The kernel module comes from the host (this is an
    # incus container with /dev/zfs passed in) — only the userspace tools belong here.
    pkgs.zfs
  ];
  # KUBECONFIG ambient for root's shell, so kubectl AND cilium work bare (no `flox activate`, no
  # per-command KUBECONFIG= prefix) — the node's admin kubeconfig rke2 writes at boot.
  environment.sessionVariables.KUBECONFIG = "/etc/rancher/rke2/rke2.yaml";
  environment.shellAliases.k = "kubectl";
}
