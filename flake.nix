{
  description = "rke2lab per-cluster RKE2 config installer — extracts the RKE2_CONFIG ConfigMaps rendered on this manifests/<cluster> branch into /etc/rancher/rke2/config.yaml.d. Run identically at boot by a standalone (seed-master) or an in-cluster (CAPRKE2) node: nix run <this-branch>#install-rke2-config.";

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
            # sops decrypts the sensitive fragments (the rke2 token Secret) with SOPS_AGE_KEY
            # from the environment; sops only encrypts data/stringData VALUES, so kind +
            # annotations stay readable without a key.
            runtimeInputs = [ pkgs.yq-go pkgs.sops pkgs.coreutils pkgs.findutils ];
            text = ''
              dest=/etc/rancher/rke2/config.yaml.d
              install -d -m 0755 "$dest"
              # Reinstall from the branch: wipe the fragments this installer owns first. The
              # per-node oneshots (node-labels, provider-id) write their drop-ins AFTER this.
              find "$dest" -maxdepth 1 -type f \( -name '*.yaml' -o -name '*.yml' \) -delete
              count=0
              while IFS= read -r -d "" manifest; do
                marked="$(yq eval -r                   '.metadata.annotations["io.seedmatic.rke2lab/rke2-config"] // "false"'                   "$manifest")"
                [ "$marked" = "true" ] || continue
                name="$(yq eval -r '.metadata.name' "$manifest")"
                # Decrypt in place if the fragment carries a sops block (the token Secret);
                # otherwise read it as-is. Then extract the config payload (ConfigMap .data or
                # Secret .stringData), parsing each value from its embedded YAML.
                if [ "$(yq eval -r 'has("sops")' "$manifest")" = "true" ]; then
                  plain="$(sops --decrypt "$manifest")"
                else
                  plain="$(cat "$manifest")"
                fi
                printf '%s' "$plain" | yq eval -o=yaml                   '(.data // .stringData // {}) | with_entries(.value |= from_yaml)'                   > "$dest/$name"
                count=$((count + 1))
              done < <(find "${self}" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)
              echo "[install-rke2-config] installed $count RKE2_CONFIG fragment(s) into $dest"
            '';
          };
          app = { type = "app"; program = "${installer}/bin/install-rke2-config"; };
        in {
          install-rke2-config = app;
          default = app;
        });
    };
}
