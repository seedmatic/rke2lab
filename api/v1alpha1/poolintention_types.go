package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// PoolIntentionSpec is the POOL-LEVEL intent seed-master renders for one node pool of a cluster —
// the Flux-owned recipe for the pool's CAPI template shape (RKE2ControlPlane for a control-plane
// pool, MachineDeployment + RKE2ConfigTemplate for a worker pool) + its LXCMachineTemplate + its
// named pets. A cluster has N PoolIntentions (one per pool), each ownerRef'd to the ClusterIntention.
// The controller reconciles each adopt-first and OWNS a PoolAdoption (the pool mirror). The
// cluster-level facts (image, versions, VIP) are DUPLICATED here from the same blueprint SSOT so the
// pool reconcile is decoupled from the cluster reconcile.
type PoolIntentionSpec struct {
	// ClusterRef is the name of the owning ClusterIntention (= its clusterName). The CAPI objects
	// this pool builds reference the cluster by the deterministic name, so there is no runtime dep —
	// just the naming convention (<cluster>, <cluster>-control-plane).
	// +kubebuilder:validation:MinLength=1
	ClusterRef string `json:"clusterRef"`

	// Namespace is where the CAPI CR-set, the owned PoolAdoption, and the referenced Secrets live.
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// Pool is the pool identity (e.g. "control-node", "gpu-node"). It names the LXCMachineTemplate
	// and (for workers) the MachineDeployment; the CAPI TREATMENT is Role, not this name.
	// +kubebuilder:validation:MinLength=1
	Pool string `json:"pool"`

	// Role is the CAPI treatment this pool derives (control-plane vs worker).
	Role PoolRole `json:"role"`

	// Image pins the nix-built node-base the pool's LXCMachines boot on / adopt.
	Image ImageRef `json:"image"`

	// RKE2Version is the CAPRKE2 version for this pool (control-plane RCP, or worker RKE2ConfigTemplate).
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// KubeVIPVersion pins the kube-vip image a control-plane pool's bootstrap deploys (ignored by
	// worker pools).
	// +optional
	KubeVIPVersion string `json:"kubeVIPVersion,omitempty"`

	// ControlPlaneEndpoint is the kube-vip VIP — a control-plane pool registers its replicas on it.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// Nodes is the pool's explicit pet roster, adopted/provisioned by providerID (lxc:///<name>).
	// +kubebuilder:validation:MinItems=1
	Nodes []PetSpec `json:"nodes"`
}

// PoolIntentionPhase mirrors the owned PoolAdoption per-pool state machine phase back onto the intent (see
// the state machine in docs/architecture/cluster-api/cluster-seeding-controller.adoc).
type PoolIntentionPhase string

const (
	// PoolIntentionPhasePending — the reconcile has not started (no adoption created yet).
	PoolIntentionPhasePending PoolIntentionPhase = "Pending"
	// PoolIntentionPhaseProvisioning — one or more pets are ABSENT; CAPN is launching them.
	PoolIntentionPhaseProvisioning PoolIntentionPhase = "Provisioning"
	// PoolIntentionPhaseAdopting — the pets are present; the CR-set is being aligned to describe them.
	PoolIntentionPhaseAdopting PoolIntentionPhase = "Adopting"
	// PoolIntentionPhaseAdopted — every pet in the pool is present and described.
	PoolIntentionPhaseAdopted PoolIntentionPhase = "Adopted"
	// PoolIntentionPhaseDegraded — pets present but a pool step failed; surface + retry adopt.
	PoolIntentionPhaseDegraded PoolIntentionPhase = "Degraded"
)

// The condition types the PoolIntention reconciler reports.
const (
	// PoolIntentionConditionAdoptionCreated — the owned PoolAdoption exists (the intent handed off).
	PoolIntentionConditionAdoptionCreated = "AdoptionCreated"
	// PoolIntentionConditionReady — a roll-up: the owned PoolAdoption reports Adopted.
	PoolIntentionConditionReady = "Ready"
)

// PoolIntentionStatus records what the controller observed.
type PoolIntentionStatus struct {
	// Phase mirrors the owned PoolAdoption per-pool state machine phase.
	// +optional
	Phase PoolIntentionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// AdoptionRef is the name of the PoolAdoption this PoolIntention owns (in Namespace).
	// +optional
	AdoptionRef string `json:"adoptionRef,omitempty"`

	// LastReconcileTime is when the controller last reconciled this PoolIntention.
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
// +kubebuilder:resource:scope=Namespaced,shortName=capipool
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterRef`
// +kubebuilder:printcolumn:name="Pool",type=string,JSONPath=`.spec.pool`
// +kubebuilder:printcolumn:name="Role",type=string,JSONPath=`.spec.role`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// PoolIntention is the Flux-owned pool-level intent for one node pool of a cluster — reconciled
// adopt-first by the controller, which owns the derived PoolAdoption.
type PoolIntention struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   PoolIntentionSpec   `json:"spec,omitempty"`
	Status PoolIntentionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// PoolIntentionList is a list of PoolIntention.
type PoolIntentionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []PoolIntention `json:"items"`
}

func init() {
	SchemeBuilder.Register(&PoolIntention{}, &PoolIntentionList{})
}
