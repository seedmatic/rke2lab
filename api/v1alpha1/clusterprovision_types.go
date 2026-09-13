package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ClusterProvisionSpec is the INTENT seed-master renders for one cluster — the Flux-owned recipe
// for bringing a cluster into existence AND keeping it described in Cluster API. The controller
// reconciles it ADOPT-FIRST: every reconcile it tries to describe the running reality (match each
// pet by providerID, skip bootstrap) before it ever provisions; a node that is genuinely absent is
// the only case CAPN launches. From this intent the controller OWNS a ClusterAdoption (the mirror
// that describes the running cluster) — the ownership split: Flux owns the intent (git-backed,
// survives a cold-start), the controller owns the adoption (re-derived from reality).
//
// Every field is filled by seed-master from the ClusterNetworkBlueprint SSOT; the controller
// templates, it never computes addressing.
type ClusterProvisionSpec struct {
	// ClusterName is the CAPI Cluster name to bring to existence and adopt (e.g. "bioskop-wrkld").
	// +kubebuilder:validation:MinLength=1
	ClusterName string `json:"clusterName"`

	// Namespace is where the CAPI CR-set, the owned ClusterAdoption, and the referenced Secrets live
	// (e.g. "rke2lab-bioskop-wrkld").
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// Kind is the cluster's federated role — "workload" (a leaf) or "management" (a sub-plane that
	// itself runs a seed-incluster for ITS children). It records topological intent; the controller
	// provisions/adopts both identically (the difference is the render flavor seed-master delivers
	// INTO the cluster, not how it is birthed).
	// +kubebuilder:validation:Enum=workload;management
	// +kubebuilder:default=workload
	Kind ClusterKind `json:"kind,omitempty"`

	// ControlPlaneEndpoint is the kube-vip VIP fronting the apiserver.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// ClusterNetwork carries the pod/service CIDRs + the service domain.
	ClusterNetwork ClusterNetwork `json:"clusterNetwork"`

	// Image pins the nix-built node-base by fingerprint — the SAME node-base the instances boot on.
	Image ImageRef `json:"image"`

	// RKE2Version is the CAPRKE2 control-plane version (e.g. "v1.34.8+rke2r2").
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// KubeVIPVersion pins the kube-vip image the control-plane bootstrap deploys.
	// +kubebuilder:validation:MinLength=1
	KubeVIPVersion string `json:"kubeVIPVersion"`

	// Remote is the target Incus engine for THIS cluster's nodes — cluster-level, not per-node
	// (CAPN's LXCCluster.secretRef names one remote; a cluster's nodes share one host's fabric).
	Remote Remote `json:"remote"`

	// Nodes is the explicit PET list — every control-plane replica AND worker is a deterministic
	// blueprint name (CANONICAL_NODE_NAMES), adopted by providerID. No cattle / no MachineDeployment
	// replica count: a named pet is the only shape that re-matches its survivor after a cold-start.
	// +kubebuilder:validation:MinItems=1
	Nodes []NodeSpec `json:"nodes"`
}

// ClusterKind is a cluster's federated role.
type ClusterKind string

const (
	// ClusterKindWorkload is a leaf cluster (no seed-incluster inside it).
	ClusterKindWorkload ClusterKind = "workload"
	// ClusterKindManagement is a sub-management-plane (runs its own seed-incluster for its children).
	ClusterKindManagement ClusterKind = "management"
)

// Remote is the target Incus engine a cluster's nodes are provisioned on / adopted from.
type Remote struct {
	// Endpoint is the Incus API endpoint (e.g. "https://bioskop-nixos:8443") — the local engine, or a
	// cross-host one reached over the tailnet.
	// +kubebuilder:validation:MinLength=1
	Endpoint string `json:"endpoint"`

	// IdentitySecretName is the per-remote CAPN identity Secret that the LXCCluster.secretRef names,
	// resolved in Namespace. The controller ALSO uses its client cert to PROBE instance presence on
	// this remote (the adopt-vs-provision decision). Its trust is CA-based (our owned Incus PKI).
	// +kubebuilder:validation:MinLength=1
	IdentitySecretName string `json:"identitySecretName"`
}

// NodeSpec is one named pet of the cluster.
type NodeSpec struct {
	// Name is the deterministic instance name (e.g. "bioskop-wrkld-master") — the providerID basis
	// (lxc:///<name>) and the LXCMachine object name CAPN matches against the running instance.
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// Role is "control-plane" (an etcd-member server, counts toward the RKE2ControlPlane replicas) or
	// "worker".
	// +kubebuilder:validation:Enum=control-plane;worker
	Role NodeRole `json:"role"`
}

// NodeRole distinguishes a control-plane (etcd-member) node from a worker.
type NodeRole string

const (
	// NodeRoleControlPlane is an etcd-member server node.
	NodeRoleControlPlane NodeRole = "control-plane"
	// NodeRoleWorker is a worker node.
	NodeRoleWorker NodeRole = "worker"
)

// ClusterProvisionPhase is the coarse lifecycle of the adopt-first funnel (see the state machine in
// docs/architecture/cluster-api/cluster-seeding-controller.adoc). Every state passes back through
// Adopting — the adopt-first invariant.
type ClusterProvisionPhase string

const (
	// ProvisionPhasePending — the reconcile has not started (no adoption created yet).
	ProvisionPhasePending ClusterProvisionPhase = "Pending"
	// ProvisionPhaseProvisioning — one or more nodes are ABSENT; CAPN is launching them.
	ProvisionPhaseProvisioning ClusterProvisionPhase = "Provisioning"
	// ProvisionPhaseAdopting — the nodes are present; the CR-set is being aligned to describe them.
	ProvisionPhaseAdopting ClusterProvisionPhase = "Adopting"
	// ProvisionPhaseAdopted — the cluster is fully described in CAPI (all pets adopted, un-paused).
	ProvisionPhaseAdopted ClusterProvisionPhase = "Adopted"
	// ProvisionPhaseDegraded — a node is present but its control plane is unhealthy; NEVER
	// re-provision a present node (data loss). Surface + retry adopt.
	ProvisionPhaseDegraded ClusterProvisionPhase = "Degraded"
)

// The condition types the ClusterProvision reconciler reports.
const (
	// ProvisionConditionAdoptionCreated — the owned ClusterAdoption exists (the intent handed off).
	ProvisionConditionAdoptionCreated = "AdoptionCreated"
	// ProvisionConditionReady — a roll-up: the owned ClusterAdoption reports Adopted.
	ProvisionConditionReady = "Ready"
)

// ClusterProvisionStatus records what the controller observed.
type ClusterProvisionStatus struct {
	// Phase is the coarse funnel stage.
	// +optional
	Phase ClusterProvisionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// AdoptionRef is the name of the ClusterAdoption this ClusterProvision owns (in Namespace),
	// created once the intent is handed off; empty until then.
	// +optional
	AdoptionRef string `json:"adoptionRef,omitempty"`

	// LastReconcileTime is when the controller last reconciled this ClusterProvision.
	// +optional
	LastReconcileTime metav1.Time `json:"lastReconcileTime,omitempty"`

	// Conditions follow the standard metav1.Condition contract.
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,shortName=capiprov
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterName`
// +kubebuilder:printcolumn:name="Kind",type=string,JSONPath=`.spec.kind`
// +kubebuilder:printcolumn:name="Endpoint",type=string,JSONPath=`.spec.controlPlaneEndpoint.host`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClusterProvision is the Flux-owned intent to bring one cluster into existence and keep it
// described in Cluster API — reconciled adopt-first by the controller, which owns the derived
// ClusterAdoption.
type ClusterProvision struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClusterProvisionSpec   `json:"spec,omitempty"`
	Status ClusterProvisionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// ClusterProvisionList is a list of ClusterProvision.
type ClusterProvisionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClusterProvision `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ClusterProvision{}, &ClusterProvisionList{})
}
