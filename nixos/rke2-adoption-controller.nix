# The rke2-adoption-controller image, baked into the node substrate.
#
# WHY baked (same air-gap path as flox-controller / the NRI plugin): the controller
# runs in-cluster and adopts the RUNNING RKE2-on-Incus control plane into CAPI. It
# must be present on the node BEFORE the cluster describes itself in CAPI (it is the
# actor that creates the CR-set), and there is no registry on the private LAN.
#
# DELIVERY — no registry: the rke2-adoption-controller flake's buildLayeredImage yields
# a store `.tar.gz`; a tmpfiles symlink drops it into /var/lib/rancher/rke2/agent/images/
# where rke2 auto-imports it into local containerd (k8s.io namespace) at boot. The
# Rke2AdoptionControllerManifestsUnit Deployment references it by RepoTag with
# imagePullPolicy: IfNotPresent — present locally ⇒ never pulled. The RepoTag MUST match
# the Deployment's image (io.seedmatic.rke2-adoption-controller:<VERSION>).
{ pkgs, rke2-adoption-controller, ... }:
let
  image =
    rke2-adoption-controller.packages.${pkgs.stdenv.hostPlatform.system}.rke2-adoption-controller-image;
in
{
  systemd.tmpfiles.rules = [
    "L+ /var/lib/rancher/rke2/agent/images/rke2-adoption-controller.tar.gz - - - - ${image}"
  ];
}
