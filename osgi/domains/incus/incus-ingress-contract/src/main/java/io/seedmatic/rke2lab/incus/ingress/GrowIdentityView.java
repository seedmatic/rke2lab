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
 * <p>The per-cluster rke2 config (dual-stack CIDRs, apiserver tls-san incl. the VIP, node-ip, …) is
 * NOT delivered here: it is rendered onto the {@code manifests/<cluster>} branch by {@code
 * RuntimeRke2ConfigManifestsUnit} and installed at boot by {@code nix run
 * <branch>#install-rke2-config} — the same delivery for a standalone (seed-master) and an
 * in-cluster (CAPRKE2) node. This view carries only the four per-node identity scalars.
 */
public record GrowIdentityView(String nodeRef, String nodeHostname, String nodeKind, int nodeId) {}
