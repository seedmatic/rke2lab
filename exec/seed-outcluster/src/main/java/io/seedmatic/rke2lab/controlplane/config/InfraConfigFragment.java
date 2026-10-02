package io.seedmatic.rke2lab.controlplane.config;

/**
 * Marker for the heterogeneous infra config fragments each {@link InfraDomain} contributes. Sealed
 * so the registry's single cast (in {@code Rke2labConfig}'s typed accessors) is provably
 * exhaustive: the permits list is the closed set of fragment types.
 */
public sealed interface InfraConfigFragment
    permits Rke2labConfig.IncusConfig,
        Rke2labConfig.ImageConfig,
        Rke2labConfig.NetworkConfig,
        Rke2labConfig.SystemdAdapterConfig,
        Rke2labConfig.HostAssetConfig {}
