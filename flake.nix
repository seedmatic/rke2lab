{
  description = "rke2lab RKE2 config installer — extracts the RKE2_CONFIG ConfigMaps of THIS node's cluster (namespace rke2lab-<cluster>, derived from the hostname) from this management branch into /etc/rancher/rke2/config.yaml.d. Run at boot by the self/root control-plane node (seed-master): nix run <this-branch>#install-rke2-config.";

  # Pinned to the node-base's own nixpkgs rev (injected at render from the source
  # flake.lock) so the installer's yq-go is a store cache-hit on the node — no cold fetch.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/b69de56fac8c2b6f8fd27f2eca01dcda8e0a4221";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-linux" "x86_64-linux" ];
      forEachSystem = f:
        nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      apps = forEachSystem (pkgs:
        let
          installer = pkgs.writeShellApplication {
            name = "install-rke2-config";
            runtimeInputs = [ pkgs.yq-go pkgs.coreutils pkgs.findutils ];
            text = ''
              dest=/etc/rancher/rke2/config.yaml.d
              install -d -m 0755 "$dest"
              # This node's cluster, from its hostname (<cluster>-<node>): a management branch
              # carries the config of EVERY cluster it manages (one namespace rke2lab-<cluster>
              # each), so install ONLY this node's fragments — never a co-located cluster's.
              cluster="$(cat /proc/sys/kernel/hostname)"
              cluster="''${cluster%-*}"
              # Reinstall from the branch: wipe the fragments this installer owns first. The
              # per-node oneshots (node-labels, provider-id, node-ip) write drop-ins AFTER this.
              find "$dest" -maxdepth 1 -type f \( -name '*.yaml' -o -name '*.yml' \) -delete
              count=0
              while IFS= read -r -d "" manifest; do
                marked="$(yq eval -r                   '.metadata.annotations["io.seedmatic.rke2lab/rke2-config"] // "false"'                   "$manifest")"
                [ "$marked" = "true" ] || continue
                # Only THIS node's cluster — the fragment's namespace (rke2lab-<cluster>)
                # encodes it; the mandatory filter that keeps co-located clusters apart.
                ns="$(yq eval -r '.metadata.namespace // ""' "$manifest")"
                [ "$ns" = "rke2lab-$cluster" ] || continue
                name="$(yq eval -r '.metadata.name' "$manifest")"
                # Extract the config payload (ConfigMap .data), parsing each value from its
                # embedded YAML. The fragments are non-secret ConfigMaps committed plaintext
                # (the sops gitattributes binds only *-secret-* files), so no decryption.
                yq eval -o=yaml                   '(.data // {}) | with_entries(.value |= from_yaml)'                   "$manifest" > "$dest/$name"
                count=$((count + 1))
              done < <(find "${self}" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)
              echo "[install-rke2-config] installed $count fragment(s) for cluster $cluster into $dest"
            '';
          };
          app = { type = "app"; program = "${installer}/bin/install-rke2-config"; };
        in {
          install-rke2-config = app;
          default = app;
        });
    };
}
