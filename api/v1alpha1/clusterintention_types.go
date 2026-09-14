package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ClusterIntentionSpec is the CLUSTER-LEVEL intent seed-master renders for one cluster — the
// Flux-owned recipe for its CAPI Cluster + LXCCluster. It carries ONLY cluster-scoped facts (VIP,
// CIDRs, remote+identity, federated kind); the per-pool roster + templates live in the N
// PoolIntention children (ownerRef'd to this ClusterIntention). The controller reconciles it
// adopt-first and OWNS a ClusterAdoption (the cluster mirror, re-derived from reality) — the
// ownership split: Flux owns the intent (git-backed, survives a cold-start), the controller owns the
// mirror. Every field is filled from the ClusterNetworkBlueprint SSOT; the controller never computes
// addressing.
type ClusterIntentionSpec struct {
	// ClusterName is the CAPI Cluster name to bring to existence and adopt (e.g. "bioskop-wrkld").
	// +kubebuilder:validation:MinLength=1
	ClusterName string `json:"clusterName"`

	// Namespace is where the CAPI CR-set, the owned ClusterAdoption, the PoolIntention children, and
	// the referenced Secrets live (e.g. "rke2lab-bioskop-wrkld").
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// Kind is the cluster's federated role — recorded topological intent (see ClusterKind).
	// +kubebuilder:default=workload
	Kind ClusterKind `json:"kind,omitempty"`

	// ControlPlaneEndpoint is the kube-vip VIP fronting the apiserver.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// ClusterNetwork carries the pod/service CIDRs + the service domain.
	ClusterNetwork ClusterNetwork `json:"clusterNetwork"`

	// Remote is the target Incus engine for THIS cluster's nodes (endpoint + identity Secret).
	Remote Remote `json:"remote"`
}

// ClusterIntentionPhase mirrors the ClusterAdoption aggregate phase back onto the intent, so
// `kubectl get clusterintention` is the single durable pane even after a cold-start wipes the
// ClusterAdoption. The states are the aggregate adopt-first state machine (see the state machine in
// docs/architecture/cluster-api/cluster-seeding-controller.adoc).
type ClusterIntentionPhase string

const (
	// IntentionPhasePending — the reconcile has not started (no adoption created yet).
	IntentionPhasePending ClusterIntentionPhase = "Pending"
	// IntentionPhaseProvisioning — a pool is provisioning one or more absent pets.
	IntentionPhaseProvisioning ClusterIntentionPhase = "Provisioning"
	// IntentionPhaseAdopting — pools are being aligned to the running reality.
	IntentionPhaseAdopting ClusterIntentionPhase = "Adopting"
	// IntentionPhaseAdopted — every pool is Adopted and the apiserver is reachable.
	IntentionPhaseAdopted ClusterIntentionPhase = "Adopted"
	// IntentionPhaseDegraded — pools present but the control plane is unreachable; retry adopt.
	IntentionPhaseDegraded ClusterIntentionPhase = "Degraded"
)

// The condition types the ClusterIntention reconciler reports.
const (
	// IntentionConditionAdoptionCreated — the owned ClusterAdoption exists (the intent handed off).
	IntentionConditionAdoptionCreated = "AdoptionCreated"
	// IntentionConditionReady — a roll-up: the owned ClusterAdoption reports Adopted.
	IntentionConditionReady = "Ready"
)

// ClusterIntentionStatus records what the controller observed.
type ClusterIntentionStatus struct {
	// Phase mirrors the owned ClusterAdoption aggregate phase.
	// +optional
	Phase ClusterIntentionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// AdoptionRef is the name of the ClusterAdoption this ClusterIntention owns (in Namespace).
	// +optional
	AdoptionRef string `json:"adoptionRef,omitempty"`

	// LastReconcileTime is when the controller last reconciled this ClusterIntention.
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
// +kubebuilder:resource:scope=Namespaced,shortName=capiintent
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterName`
// +kubebuilder:printcolumn:name="Kind",type=string,JSONPath=`.spec.kind`
// +kubebuilder:printcolumn:name="Endpoint",type=string,JSONPath=`.spec.controlPlaneEndpoint.host`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClusterIntention is the Flux-owned cluster-level intent to bring one cluster into existence and
// keep it described in Cluster API — reconciled adopt-first by the controller, which owns the
// derived ClusterAdoption and aggregates the per-pool PoolAdoptions into it.
type ClusterIntention struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClusterIntentionSpec   `json:"spec,omitempty"`
	Status ClusterIntentionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// ClusterIntentionList is a list of ClusterIntention.
type ClusterIntentionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClusterIntention `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ClusterIntention{}, &ClusterIntentionList{})
}
