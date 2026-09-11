package io.seedmatic.rke2lab.incus.ingress;

/**
 * The flat IDENTITY view the GROW poses on the instance as {@code user.rke2lab.node-*} config keys
 * — the per-node facts the guest reads back over devlxd ({@code /dev/incus/sock}) to resolve its
 * hostname, its zfs dataset and its rke2 role at boot. There is NO cloud-init and NO host file
 * mount: the NixOS {@code node-base} substrate is homogeneous, and the only per-node difference is
 * these four scalars, delivered declaratively on the Instance resource.
 *
 * <p>Like {@link GrowNetworkView}, every field ORIGINATES in the {@code ClusterNetworkBlueprint}
 * ({@code netplan-contract}, OSGi-only): the {@code nodeRef}/{@code nodeId} are the blueprint's
 * node ref (the SHORT in-cluster node identifier — {@code master}, {@code peer1} — NOT a name), the
 * {@code nodeKind} is {@code NodeType.kind()} (server/agent — the k8s role), and the {@code
 * nodeHostname} is the FULL {@code <cluster>-<node>} (the OS hostname the node sets, and thus the
 * k8s node name + the incus instance name — what CAPN adopts by). The two are distinct on purpose:
 * reach for {@code nodeHostname} when you want the node's NAME, {@code nodeRef} only for the short
 * ordinal. The host cannot read the blueprint typed, so the scion resolves it and projects these
 * flat values here; the host only poses them.
 *
 * <p>The {@code clusterPodCidr}/{@code clusterServiceCidr} are the PER-CLUSTER dual-stack spans
 * ({@code 10.<44+id>.0.0/16,fd00:<44+id>::/56}) the guest bakes into rke2's {@code cluster-cidr}/
 * {@code service-cidr} — homogeneous image, so the CIDRs cannot be a static nix literal (they
 * differ per cluster on the shared host); the node reads them back over devlxd and writes the rke2
 * config drop-in at boot (see {@code nixos/rke2.nix} {@code rke2lab-dualstack}).
 */
public record GrowIdentityView(
    String nodeRef,
    String nodeHostname,
    String nodeKind,
    int nodeId,
    String clusterPodCidr,
    String clusterServiceCidr) {}
