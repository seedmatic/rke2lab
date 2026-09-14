package v1alpha1

// Shared value types, enums and label conventions for the cluster.seedmatic.io CR set. The set is a
// 2×2 — {ClusterIntention, PoolIntention} (the Flux-owned INTENT) × {ClusterAdoption, PoolAdoption}
// (the controller-owned MIRROR) — on the {cluster, pool} grains. Every value here is a projection of
// the one ClusterNetworkBlueprint SSOT; the controller templates, it never computes addressing.

// APIEndpoint is a host:port control-plane endpoint (the kube-vip VIP).
type APIEndpoint struct {
	// +kubebuilder:validation:MinLength=1
	Host string `json:"host"`
	// +kubebuilder:validation:Minimum=1
	Port int32 `json:"port"`
}

// ClusterNetwork carries the pod/service CIDRs and the service domain.
type ClusterNetwork struct {
	// +kubebuilder:validation:MinItems=1
	PodCIDRs []string `json:"podCIDRs"`
	// +kubebuilder:validation:MinItems=1
	ServiceCIDRs []string `json:"serviceCIDRs"`
	// +kubebuilder:default="cluster.local"
	ServiceDomain string `json:"serviceDomain,omitempty"`
}

// ImageRef pins the nix-built node-base image by fingerprint — the SAME node-base the instances
// boot on (so the adopted machine template matches).
type ImageRef struct {
	// +kubebuilder:validation:MinLength=1
	Fingerprint string `json:"fingerprint"`
}

// Remote is the target Incus engine a cluster's nodes are provisioned on / adopted from —
// cluster-level, not per-node (a cluster's nodes share one host's fabric; CAPN's LXCCluster.secretRef
// names one remote).
type Remote struct {
	// Endpoint is the Incus API endpoint (e.g. "https://bioskop-nixos:8443") — the local engine, or a
	// cross-host one reached over the tailnet. Empty = the local engine, resolved from the identity
	// Secret's `server` (the management cluster's own host).
	// +optional
	Endpoint string `json:"endpoint,omitempty"`

	// IdentitySecretName is the per-remote CAPN identity Secret the LXCCluster.secretRef names,
	// resolved in Namespace. Its CA-based trust is our owned Incus PKI.
	// +kubebuilder:validation:MinLength=1
	IdentitySecretName string `json:"identitySecretName"`
}

// ClusterKind is a cluster's federated role — "workload" (a leaf) or "management" (a sub-plane that
// itself runs a seed-incluster for ITS children). It records topological intent; the controller
// provisions/adopts both identically (the difference is the render flavor delivered INTO the
// cluster, not how it is birthed).
// +kubebuilder:validation:Enum=workload;management
type ClusterKind string

const (
	// ClusterKindWorkload is a leaf cluster (no seed-incluster inside it).
	ClusterKindWorkload ClusterKind = "workload"
	// ClusterKindManagement is a sub-management-plane (runs its own seed-incluster for its children).
	ClusterKindManagement ClusterKind = "management"
)

// PoolRole is the CAPI treatment a pool DERIVES: control-plane (an RKE2ControlPlane of etcd members,
// the object the Cluster references) or worker (a MachineDeployment + RKE2ConfigTemplate). It lifts
// the old per-node role to the pool grain — a pool is one profile + one template, so every pet in it
// shares the role. There is no magic pool-NAME string: "control-node" is an identity, this is the
// treatment.
// +kubebuilder:validation:Enum=control-plane;worker
type PoolRole string

const (
	// PoolRoleControlPlane — an etcd-member control-plane pool (RKE2ControlPlane, referenced by the Cluster).
	PoolRoleControlPlane PoolRole = "control-plane"
	// PoolRoleWorker — a worker pool (MachineDeployment / RKE2ConfigTemplate).
	PoolRoleWorker PoolRole = "worker"
)

// PetSpec is one named pet of a pool — the deterministic instance name (CANONICAL_NODE_NAMES), the
// providerID basis (lxc:///<name>) and the LXCMachine object name CAPN matches the running instance
// against. No cattle / no replica count: a named pet is the only shape that re-matches its survivor
// after a cold-start.
type PetSpec struct {
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// Label conventions. A PoolIntention/PoolAdoption carries LabelCluster so a ClusterIntention can
// list + watch its pools and aggregate their presence into cluster-level existence.
const (
	// LabelCluster names the cluster a pool belongs to (= ClusterIntention.spec.clusterName).
	LabelCluster = "cluster.seedmatic.io/cluster"
	// LabelPool names the pool (= PoolIntention.spec.pool).
	LabelPool = "cluster.seedmatic.io/pool"
)
