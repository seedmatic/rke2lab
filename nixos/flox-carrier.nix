# Bake the flox carrier image into the node. The image itself is the shared leaf
# ./flox-carrier-image.nix, imported here with the NODE's pkgs — and by the flake with the
# same pkgs (nixosConfigurations.rke2-node-base.pkgs) to publish its RepoTag, so the tag
# the synthesis renders and the image baked here cannot disagree.
#
# DELIVERY — no registry: buildLayeredImage yields a store `.tar.gz`; a tmpfiles
# symlink drops it into /var/lib/rancher/rke2/agent/images/ where rke2's air-gap
# path auto-imports it into local containerd (k8s.io namespace) at boot. Pods
# reference it by its exact RepoTag with imagePullPolicy: IfNotPresent — present
# locally ⇒ never pulled.
#
# ⚠️ That last property is why the tag must be content-derived, and it is (the leaf omits
# `tag`, so dockerTools uses the output hash). `IfNotPresent` means a node that already
# holds a RepoTag NEVER replaces it: with the former literal `0.1.0`, a rebuilt carrier
# shipped inside the node image while every pod kept running the old one, and nothing
# reported it. A derived tag makes the policy correct — new content is a tag the node
# cannot already hold — and leaves the old tag in place for a rollback.
#
# The RepoTag is therefore READ by the manifest synthesis (staged at /image-refs/), never
# restated: a Java constant carrying it could not be right any more.
{ pkgs, ... }:
let
  carrier = import ./flox-carrier-image.nix { inherit pkgs; };
in
{
  systemd.tmpfiles.rules = [
    "L+ /var/lib/rancher/rke2/agent/images/flox-carrier.tar.gz - - - - ${carrier}"
  ];
}
