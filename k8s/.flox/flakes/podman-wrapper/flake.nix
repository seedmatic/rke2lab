{
  description = "podman-wrapper flake for k8s env";

  inputs.nixpkgs.url = "github:flox/nixpkgs";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "aarch64-linux" ];
      forAllSystems = f: builtins.listToAttrs (map (system: {
        name = system;
        value = f system;
      }) systems);
    in {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          podman-wrapper = pkgs.writeShellScriptBin "docker" (builtins.readFile ./podman-wrapper.sh);
          default = self.packages.${system}.podman-wrapper;
        });
    };
}
