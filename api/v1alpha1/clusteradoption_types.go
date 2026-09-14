package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ClusterAdoptionSpec is the CLUSTER-LEVEL mirror the controller owns from a ClusterIntention. From
// it the controller builds the cluster-scoped CAPI objects — Cluster + LXCCluster — and AGGREGATES
// the per-pool PoolAdoptions into cluster-level existence + reachability. The pool CR-set
// (RKE2ControlPlane / MachineDeployment / templates / Machines) is NOT built here; it belongs to the
// PoolAdoption mirror of each PoolIntention. The identity Secret is delivered by seed-master into
// Namespace (branch, sops-encrypted); this controller only references it.
type ClusterAdoptionSpec struct {
	// ClusterName is the CAPI Cluster name to create and adopt (e.g. "bioskop-wrkld").
	// +kubebuilder:validation:MinLength=1
	ClusterName string `json:"clusterName"`

	// Namespace is where the CAPI CR-set and the referenced Secrets live.
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// ControlPlaneEndpoint is the kube-vip VIP fronting the apiserver.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// ClusterNetwork carries the pod/service CIDRs + the service domain.
	ClusterNetwork ClusterNetwork `json:"clusterNetwork"`

	// Remote is the target Incus engine (endpoint + identity Secret) the LXCCluster.secretRef names.
	Remote Remote `json:"remote"`
}

// ClusterAdoptionPhase is the coarse AGGREGATE lifecycle of a cluster adoption — a roll-up of the
// per-pool PoolAdoption funnels plus cluster reachability (see the state machine in
// docs/architecture/cluster-api/cluster-seeding-controller.adoc). Adopting is the always-entry hub;
// a reconcile rests at Adopted (all pools present + apiserver reachable), Provisioning (a pool has an
// absent pet → CAPN launches), or Degraded (present but the control plane is unreachable — surface +
// retry, NEVER re-provision).
type ClusterAdoptionPhase string

const (
	// PhasePending — the Cluster/LXCCluster have not been created yet, or no pool has reported.
	PhasePending ClusterAdoptionPhase = "Pending"
	// PhaseAdopting — the adopt-first hub: the cluster CR-set is being aligned / pools are converging.
	PhaseAdopting ClusterAdoptionPhase = "Adopting"
	// PhaseProvisioning — at least one pool reports an absent pet (CAPN InstanceDeleted); it launches
	// the missing node(s). Present pets are NEVER touched (data-loss safety).
	PhaseProvisioning ClusterAdoptionPhase = "Provisioning"
	// PhaseAdopted — every pool is Adopted AND the apiserver is reachable (RemoteConnectionProbe);
	// the Cluster is unpaused.
	PhaseAdopted ClusterAdoptionPhase = "Adopted"
	// PhaseDegraded — the pools are present but the control plane is unreachable ("present-sick").
	// The controller retries adoption and NEVER re-provisions (destroying a present node loses etcd).
	PhaseDegraded ClusterAdoptionPhase = "Degraded"
	// PhaseFailed — a reconcile step errored; see the conditions for which and why.
	PhaseFailed ClusterAdoptionPhase = "Failed"
)

// The condition types the ClusterAdoption reconciler reports — the cluster-level aggregate steps.
const (
	// ConditionCRSetCreated — the cluster-scoped Cluster + LXCCluster exist.
	ConditionCRSetCreated = "CRSetCreated"
	// ConditionExistence — at least one pool reports a present pet by providerID: the cluster EXISTS
	// (the anti-greenfield guard — we adopt, never greenfield a rival).
	ConditionExistence = "Existence"
	// ConditionPoolsAdopted — every PoolAdoption of this cluster reports Adopted.
	ConditionPoolsAdopted = "PoolsAdopted"
	// ConditionAccessible — CAPI's RemoteConnectionProbe on the Cluster is True (the apiserver
	// answers). Gates Adopted together with pool adoption: complete only when all pools are Adopted
	// AND the cluster is reachable.
	ConditionAccessible = "Accessible"
	// ConditionReady — a roll-up: every aggregate step above succeeded this reconcile.
	ConditionReady = "Ready"
)

// ClusterAdoptionStatus records what the controller observed at the cluster grain.
type ClusterAdoptionStatus struct {
	// Phase is the coarse aggregate lifecycle stage.
	// +optional
	Phase ClusterAdoptionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// Existence is the OR of every PoolAdoption's presence — true once ANY pet of ANY pool is present
	// by providerID. The cluster EXISTS; the controller adopts and never greenfields a rival.
	// +optional
	Existence bool `json:"existence,omitempty"`

	// Reachable is CAPI's RemoteConnectionProbe on the Cluster (the apiserver answers).
	// +optional
	Reachable bool `json:"reachable,omitempty"`

	// PoolsTotal is the number of PoolAdoptions observed for this cluster.
	// +optional
	PoolsTotal int32 `json:"poolsTotal,omitempty"`

	// PoolsAdopted is how many of them report Adopted.
	// +optional
	PoolsAdopted int32 `json:"poolsAdopted,omitempty"`

	// LastReconcileTime is when the controller last reconciled this ClusterAdoption.
	// +optional
	LastReconcileTime metav1.Time `json:"lastReconcileTime,omitempty"`

	// Conditions follow the standard metav1.Condition contract — one per aggregate step.
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,shortName=capiadopt
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterName`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Exists",type=boolean,JSONPath=`.status.existence`
// +kubebuilder:printcolumn:name="Reachable",type=boolean,JSONPath=`.status.reachable`
// +kubebuilder:printcolumn:name="Pools",type=string,JSONPath=`.status.poolsAdopted`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClusterAdoption is the cluster-level mirror of a running RKE2-on-Incus cluster in CAPI — it owns
// the Cluster + LXCCluster and aggregates the per-pool PoolAdoptions into existence + reachability.
type ClusterAdoption struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClusterAdoptionSpec   `json:"spec,omitempty"`
	Status ClusterAdoptionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// ClusterAdoptionList is a list of ClusterAdoption.
type ClusterAdoptionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClusterAdoption `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ClusterAdoption{}, &ClusterAdoptionList{})
}
