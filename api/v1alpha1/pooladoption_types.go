package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// PoolAdoptionSpec is the POOL-LEVEL mirror the controller owns from a PoolIntention. From it the
// controller builds the pool CR-set — the RKE2ControlPlane (control-plane pool) or MachineDeployment
// + RKE2ConfigTemplate (worker pool), the LXCMachineTemplate, and — the part GitOps cannot do — the
// OWNED per-pet Machine + concrete LXCMachine (providerID) so CAPRKE2/CAPN adopt the RUNNING
// Pulumi-bootstrapped instances instead of provisioning fresh ones. It runs the per-pool adopt-first
// state machine and reports presence; cluster reachability is aggregated at the ClusterAdoption grain, not
// here. The BYO-CA Secrets (<cluster>-{ca,cca,etcd,peer-etcd}) — needed by a control-plane pool —
// are delivered by seed-master into Namespace; this controller only references them.
type PoolAdoptionSpec struct {
	// ClusterName is the CAPI Cluster this pool belongs to (the RCP/Machines reference it).
	// +kubebuilder:validation:MinLength=1
	ClusterName string `json:"clusterName"`

	// Namespace is where the CAPI CR-set and the referenced Secrets live.
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// Pool is the pool identity (names the LXCMachineTemplate / MachineDeployment).
	// +kubebuilder:validation:MinLength=1
	Pool string `json:"pool"`

	// Role is the CAPI treatment (control-plane vs worker).
	Role PoolRole `json:"role"`

	// Image pins the nix-built node-base the pool's LXCMachines boot on / adopt.
	Image ImageRef `json:"image"`

	// RKE2Version is the CAPRKE2 version for this pool.
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// KubeVIPVersion pins the kube-vip image a control-plane pool's bootstrap deploys.
	// +optional
	KubeVIPVersion string `json:"kubeVIPVersion,omitempty"`

	// ControlPlaneEndpoint is the kube-vip VIP a control-plane pool registers its replicas on.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// Nodes is the pool's explicit pet roster, adopted/provisioned by providerID (lxc:///<name>).
	// +kubebuilder:validation:MinItems=1
	Nodes []PetSpec `json:"nodes"`
}

// PoolAdoptionPhase is the per-pool adopt-first state machine — the grain it actually runs
// on (a pool is the natural unit of provision/adopt, like RKE2ControlPlane vs MachineDeployment
// reconcile separately). ClusterAdoption aggregates these + reachability.
type PoolAdoptionPhase string

const (
	// PoolPhasePending — the pool CR-set has not been fully created yet.
	PoolPhasePending PoolAdoptionPhase = "Pending"
	// PoolPhaseAdopting — the adopt-first hub: the pool CR-set is being aligned to running reality.
	PoolPhaseAdopting PoolAdoptionPhase = "Adopting"
	// PoolPhaseProvisioning — at least one pet is genuinely absent (CAPN InstanceDeleted); CAPN
	// launches it. Present pets are NEVER touched (data-loss safety).
	PoolPhaseProvisioning PoolAdoptionPhase = "Provisioning"
	// PoolPhaseAdopted — every pet in the pool is present and the pool CR-set is un-paused.
	PoolPhaseAdopted PoolAdoptionPhase = "Adopted"
	// PoolPhaseDegraded — pets present but a pool step failed; retry adopt, never re-provision.
	PoolPhaseDegraded PoolAdoptionPhase = "Degraded"
	// PoolPhaseFailed — a reconcile step errored; see the conditions.
	PoolPhaseFailed PoolAdoptionPhase = "Failed"
)

// The condition types the PoolAdoption reconciler reports, one per step of the per-pool flow.
const (
	// PoolConditionMaterialReady — a control-plane pool's BYO-CA Secrets are present (worker: n/a → True).
	PoolConditionMaterialReady = "MaterialReady"
	// PoolConditionCRSetCreated — the pool's RCP/MD + LXCMachineTemplate exist.
	PoolConditionCRSetCreated = "CRSetCreated"
	// PoolConditionControlPlaneObserved — a control-plane pool's RKE2ControlPlane UID was read (for
	// the per-pet Machine ownerRef); worker: n/a → True.
	PoolConditionControlPlaneObserved = "ControlPlaneObserved"
	// PoolConditionPetsPresent — every pet's owned Machine + LXCMachine(providerID) exist AND CAPN
	// confirms the instance is present.
	PoolConditionPetsPresent = "PetsPresent"
	// PoolConditionUnpaused — the pool's RCP/MD + the Cluster were un-paused (adoption released).
	PoolConditionUnpaused = "Unpaused"
	// PoolConditionReady — a roll-up: every step above succeeded this reconcile.
	PoolConditionReady = "Ready"
)

// PoolAdoptionStatus records what the controller observed at the pool grain.
type PoolAdoptionStatus struct {
	// Phase is the per-pool state machine stage.
	// +optional
	Phase PoolAdoptionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// Present is how many pets CAPN confirms present by providerID.
	// +optional
	Present int32 `json:"present,omitempty"`

	// Absent is how many pets CAPN reports genuinely absent (InstanceDeleted → provision).
	// +optional
	Absent int32 `json:"absent,omitempty"`

	// Pending is how many pets CAPN has not yet decided presence for.
	// +optional
	Pending int32 `json:"pending,omitempty"`

	// TotalPets is the pool's roster size (len(spec.Nodes)).
	// +optional
	TotalPets int32 `json:"totalPets,omitempty"`

	// VoidedPets is the reflected roster this reconciler last declared VOID — every one of its pets
	// confirmed gone, so the reflection had nothing left to protect and its CR-set was dropped for
	// CAPRKE2 to re-provision. It has to be REMEMBERED: once the CR-set is gone the pets read
	// *undecided* rather than absent (no LXCMachine to carry CAPN's verdict), which is the same
	// reading as a cold-start, and the adopt branch would faithfully re-mint the very phantoms that
	// were just dropped. The reflector rewrites the reflection within seconds of the Machine events,
	// so this normally holds for one or two passes; it also holds if that git push is slow, which is
	// the case it exists for. Cleared as soon as the reflected roster differs or any pet is present.
	// +optional
	VoidedPets []string `json:"voidedPets,omitempty"`

	// ControlPlaneUID is the observed RKE2ControlPlane UID the owned per-pet Machines' ownerRef
	// carries (control-plane pool only) — the piece GitOps could not pre-set.
	// +optional
	ControlPlaneUID string `json:"controlPlaneUID,omitempty"`

	// LastReconcileTime is when the controller last reconciled this PoolAdoption.
	// +optional
	LastReconcileTime metav1.Time `json:"lastReconcileTime,omitempty"`

	// Conditions follow the standard metav1.Condition contract — one per pool step.
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,shortName=capipooladopt
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterName`
// +kubebuilder:printcolumn:name="Pool",type=string,JSONPath=`.spec.pool`
// +kubebuilder:printcolumn:name="Role",type=string,JSONPath=`.spec.role`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Present",type=string,JSONPath=`.status.present`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// PoolAdoption is the pool-level mirror of one node pool of a running cluster in CAPI — it owns the
// pool's RCP/MD + templates + per-pet Machine/LXCMachine and runs the per-pool adopt-first state machine.
type PoolAdoption struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   PoolAdoptionSpec   `json:"spec,omitempty"`
	Status PoolAdoptionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// PoolAdoptionList is a list of PoolAdoption.
type PoolAdoptionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []PoolAdoption `json:"items"`
}

func init() {
	SchemeBuilder.Register(&PoolAdoption{}, &PoolAdoptionList{})
}
