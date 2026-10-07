# RKE2Lab Environment Variable Ownership Inventory

## Overview
This inventory maps all 17 env variable sections to their owning layer domains, establishing clear contracts for which layer manages which variables at runtime.

---

## Layer Domain Assignments

### 1. **Controlplane Domain** (Infrastructure Bootstrap)
**Responsibility:** Host filesystem paths, node identity, cluster identity  
**Materialization:** IncusResourceBootstrap during VM provisioning

| Section | Variables | Count | Purpose | Status |
|---------|-----------|-------|---------|--------|
| **paths.env** | `RKE2LAB_ROOT`, `RKE2LAB_ENV_DIR`, `RKE2LAB_SCRIPTS_DIR`, `RKE2LAB_SYSTEMD_DIR`, `RKE2LAB_CONFIG_DIR`, `RKE2LAB_CLOUDCONFIG_NO_CLOUD_DIR`, `RKE2LAB_MANIFESTS_DIR`, `RKE2LAB_SHARED_DIR`, `RKE2LAB_KUBECONFIG_DIR` | 9 | Host mount paths for all subsystems | ✅ Already in 99-configmap-env-section-controlplane-overrides |
| **node.env** | `RKE2LAB_NODE_ID`, `RKE2LAB_NODE_NAME`, `RKE2LAB_NODE_KIND` | 3 | Node identity within cluster | 🔄 Move to IncusResourceBootstrap |
| **cluster.env** | `RKE2LAB_CLUSTER_ID`, `RKE2LAB_CLUSTER_NAME`, `RKE2LAB_CLUSTER_TOKEN`, `RKE2LAB_CLUSTER_DOMAIN` | 4 | Cluster-wide identity (bootstrap time) | 🔄 Move to IncusResourceBootstrap |

**Total: 16 variables**

---

### 2. **Networking Layer Domain**
**Responsibility:** Network topology, interface config, CNI settings  
**Materialization:** During cluster networking setup (post-bootstrap)

| Section | Variables | Count | Purpose | Status |
|---------|-----------|-------|---------|--------|
| **cilium.env** | `CILIUM_CLI_MODE`, `CILIUM_CLI_CONTEXT`, `HUBBLE_SERVER`, `HUBBLE_TLS` | 4 | CNI plugin (Cilium) operational config | 🔄 Create `NetworkingLayerEnvContributor` |
| **network-cluster.env** | `RKE2LAB_NETWORK_CLUSTER_CIDR`, `RKE2LAB_NETWORK_CLUSTER_LB_CIDR`, `RKE2LAB_NETWORK_CLUSTER_LB_GATEWAY_INETADDR`, `RKE2LAB_NETWORK_CLUSTER_POD_CIDR`, `RKE2LAB_NETWORK_CLUSTER_SERVICE_CIDR`, `RKE2LAB_NETWORK_CLUSTER_GATEWAY_INETADDR` | 6 | Cluster-wide network topology | 🔄 Create `NetworkingLayerEnvContributor` |
| **network-node.env** | `RKE2LAB_NETWORK_NODE_HOST_INETADDR`, `RKE2LAB_NETWORK_NODE_CIDR`, `RKE2LAB_NETWORK_NODE_GATEWAY_INETADDR` | 3 | Per-node network config | 🔄 Create `NetworkingLayerEnvContributor` |
| **network-lan-wan.env** | `RKE2LAB_NETWORK_LAN_INTERFACE`, `RKE2LAB_NETWORK_LAN_HOST_INETADDR`, `RKE2LAB_NETWORK_LAN_LB_CIDR`, `RKE2LAB_NETWORK_WAN_INTERFACE` | 4 | External network bridging (LAN/WAN) | 🔄 Create `NetworkingLayerEnvContributor` |

**Total: 17 variables**

---

### 3. **HA (High Availability) Layer Domain**
**Responsibility:** Virtual IP config, control plane redundancy  
**Materialization:** Set up before cluster control plane promotion

| Section | Variables | Count | Purpose | Status |
|---------|-----------|-------|---------|--------|
| **network-vip.env** | `RKE2LAB_NETWORK_VIP_INTERFACE`, `RKE2LAB_NETWORK_VIP_CIDR`, `RKE2LAB_NETWORK_VIP_GATEWAY_INETADDR`, `RKE2LAB_NETWORK_VIP_HOST_INETADDR` | 4 | Virtual IP for control plane HA | 🔄 Create `HaLayerEnvContributor` |

**Total: 4 variables**

---

### 4. **Storage Layer Domain**
**Responsibility:** Distributed state store (etcd) operational config  
**Materialization:** Control plane integration, etcdctl client setup

| Section | Variables | Count | Purpose | Status |
|---------|-----------|-------|---------|--------|
| **etcdctl.env** | `ETCDCTL_API`, `ETCDCTL_CERT`, `ETCDCTL_KEY`, `ETCDCTL_CACERT`, `ETCDCTL_ENDPOINTS`, `ETCDCTL_WRITE_OUT`, `ETCDCTL_DIAL_TIMEOUT`, `ETCDCTL_COMMAND_TIMEOUT` | 8 | etcd control plane access (7 in original, +1 for API) | 🔄 Create `StorageLayerEnvContributor` |

**Total: 8 variables**

---

### 5. **Runtime Layer Domain**
**Responsibility:** Cluster component integration (RKE2, containerd, kubectl), tooling paths  
**Materialization:** Post-cluster-bootstrap, operational defaults

| Section | Variables | Count | Purpose | Status |
|---------|-----------|-------|---------|--------|
| **rke2.env** | `RKE2_SERVER_MANIFESTS_DIR` | 1 | RKE2 server manifests location | 🔄 Create `RuntimeLayerEnvContributor` |
| **config.env** | `RKE2LAB_DEBUG` | 1 | Global debug flag | 🔄 Create `RuntimeLayerEnvContributor` |
| **containerd.env** | `CONTAINERD_ADDRESS`, `CONTAINERD_NAMESPACE`, `CONTAINERD_CONFIG_FILE` | 3 | Container runtime socket & config | 🔄 Create `RuntimeLayerEnvContributor` |
| **cri.env** | `CRI_CONFIG_FILE` | 1 | CRI client config | 🔄 Create `RuntimeLayerEnvContributor` |
| **helm.env** | `HELM_DATA_HOME`, `HELM_CONFIG_HOME`, `HELM_CACHE_HOME`, `HELM_REPOSITORY_CONFIG`, `HELM_REPOSITORY_CACHE`, `HELM_PLUGINS` | 6 | Package manager paths | 🔄 Create `RuntimeLayerEnvContributor` |
| **kubectl.env** | `KUBECTL_OUTPUT`, `KUBECTL_EXTERNAL_DIFF`, `KREW_ROOT` | 3 | Kubernetes client tooling | 🔄 Create `RuntimeLayerEnvContributor` |
| **user.env** | `USER`, `HOME` | 2 | Shell environment | 🔄 Create `RuntimeLayerEnvContributor` |

**Total: 17 variables**

---

## Ownership Summary

| Layer | Sections | Variables | Phase |
|-------|----------|-----------|-------|
| **Controlplane** | paths, node, cluster | 16 | Phase 1b ✅ (partially in 99-configmap) |
| **Networking** | cilium, network-cluster, network-node, network-lan-wan | 17 | Phase 2a (ready) |
| **HA** | network-vip | 4 | Phase 2b (ready) |
| **Storage** | etcdctl | 8 | Phase 2c (ready) |
| **Runtime** | rke2, config, containerd, cri, helm, kubectl, user | 17 | Phase 2d (ready) |
| **Total** | **16 sections** | **62 variables** | — |

---

## Implementation Notes

### ConfigMap Override Naming Convention
- ConfigMaps contributed by layers use `env-section-<section-name>` naming
- Controlplane bootstrap writes `99-configmap-env-section-controlplane-overrides.yml` (prefix ensures ordering)
- Runtime env-load.sh processes YAML files in sorted order, later ones override earlier

### Variable Precedence
1. **Bootstrap-time overrides** (99-configmap-env-section-*) — loaded first by env-load.sh
2. **Layer contributions** (01-configmap-env-section-*) — loaded in domain init order (storage → networking → ha → runtime)
3. **Manifests defaults** (existing 01-configmap-env-section-*) — fallback if not overridden

### Design Constraints
- **Controlplane paths** must be known before any other layer initializes → contribute at bootstrap time (99-configmap-*) ✅
- **Network topology** determines service discovery config → contribute before cluster setup
- **HA config** (VIP) needed for control plane redundancy → contribute early in control plane setup
- **Storage (etcd)** config depends on control plane paths → contribute after Controlplane, before Runtime
- **Runtime tooling** (helm, kubectl, containerd) are operational defaults → contribute at any time post-bootstrap

---

## Next Steps

### Phase 2A: Networking Layer Contributor
Create `io.seedmatic.rke2lab.manifests.layers.networking.NetworkingLayerEnvContributor`:
- [ ] Contribute `cilium.env`, `network-cluster.env`, `network-node.env`, `network-lan-wan.env`
- [ ] Write 4 ConfigMaps to runtime env-config directory during `NetworkingDomainRegistrar` init

### Phase 2B: HA Layer Contributor
Create `io.seedmatic.rke2lab.manifests.layers.ha.HaLayerEnvContributor`:
- [ ] Contribute `network-vip.env`
- [ ] Write 1 ConfigMap to runtime env-config directory

### Phase 2C: Storage Layer Contributor
Create `io.seedmatic.rke2lab.manifests.layers.storage.StorageLayerEnvContributor`:
- [ ] Contribute `etcdctl.env`
- [ ] Write 1 ConfigMap to runtime env-config directory

### Phase 2D: Runtime Layer Contributor
Create `io.seedmatic.rke2lab.manifests.layers.runtime.RuntimeLayerEnvContributor`:
- [ ] Contribute `rke2.env`, `config.env`, `containerd.env`, `cri.env`, `helm.env`, `kubectl.env`, `user.env`
- [ ] Write 7 ConfigMaps to runtime env-config directory

### Phase 2E: Wire Contributors into Controlplane
Update `IncusResourceBootstrap`:
- [ ] Collect all `LayerEnvContributor` implementations
- [ ] Merge contributions in order (storage → networking → ha → runtime)
- [ ] Write aggregated 99-configmap-env-section-layer-contributions.yml during bootstrap
