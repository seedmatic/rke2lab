# The agent node: node-base with rke2 as an agent (nixosConfigurations.rke2-node-agent). CAPRKE2 writes its
# /etc/rancher/rke2/config.yaml (server URL and join token) through cloud-init and enables `rke2-agent`; the units
# node-base orders before rke2 follow the role through rke2lab.rke2Unit.
{ ... }:
{
  services.rke2 = {
    role = "agent";
    # rke2's own default path, named so nixpkgs knows the server URL and the token come from it, written at boot.
    configPath = "/etc/rancher/rke2/config.yaml";
  };
}
