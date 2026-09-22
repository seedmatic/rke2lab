package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// VolumeIntentionSpec is the Flux-owned intent for ONE persist volume of the cluster this controller
// runs in: the dataset to adopt, its size, the claim to satisfy, and the KIND of node eligible to
// serve it. It names NO node, and that absence is the whole point.
//
// A pre-declared PV needs a node twice over — `spec.nodeAffinity` for scheduling and the openebs
// `ZFSVolume.ownerNodeID` for the CSI — and on a CAPI-provisioned cluster the node's name is RANDOM
// (…-control-plane-v9fhz), so no render-time literal can ever match. The literal that used to sit in
// the render (`bioskop-mgmt-master`) was not a value to parameterise but the admission that the unit
// had only ever been meant to run on a host-grown cluster. So the render declares, and the controller
// — which is IN the cluster and can see its nodes — elects and stamps.
//
// The static-adopt shape itself is load-bearing and unchanged: a cold start wipes etcd, so the PVC
// object is gone and dynamic provisioning would mint a fresh pvc-<uuid>, leak the old dataset and
// lose the content. A pre-declared volume adopting a dataset by STABLE name is the only handle that
// survives. Keeping openebs in the path is also what ENFORCES single access — exactly one ZFSNode
// serves the volume — rather than leaving ReadWriteOnce as a contract nobody polices.
type VolumeIntentionSpec struct {
	// Pool is the absolute ZFS path of this cluster's persist parent, from the dataplan SSOT
	// (tank/rke2lab/<role>/persist). The CSI creates/adopts the volume dataset UNDER it; openebs does
	// not create its own poolname, which is why the dataplan declares the parent.
	// +kubebuilder:validation:MinLength=1
	Pool string `json:"pool"`

	// Dataset is the stable dataset name under Pool — the openebs volumeHandle, so the PV ADOPTS the
	// pre-created dataset instead of provisioning a divergent one.
	// +kubebuilder:validation:MinLength=1
	Dataset string `json:"dataset"`

	// Capacity is the PV/PVC size (a Kubernetes quantity, e.g. "16Mi"). The ZFS dataset is not
	// pre-sized, so this is the claim's accounting figure, not a reservation.
	// +kubebuilder:validation:MinLength=1
	Capacity string `json:"capacity"`

	// StorageClassName is the Retain, non-default class the PV and PVC agree on
	// (openebs-zfs-persist).
	// +kubebuilder:validation:MinLength=1
	StorageClassName string `json:"storageClassName"`

	// ClaimRef is the PVC this volume must satisfy — pre-bound on the PV, so no other claim can take
	// it.
	ClaimRef VolumeClaimRef `json:"claimRef"`

	// NodeRole restricts the election to nodes carrying the node-role.kubernetes.io/<NodeRole> label.
	// Defaults to control-plane: a persist volume belongs with the control plane, which is the part of
	// a cluster that does not come and go.
	// +kubebuilder:default="control-plane"
	// +optional
	NodeRole string `json:"nodeRole,omitempty"`
}

// VolumeClaimRef names the PVC a persist volume is pre-bound to.
type VolumeClaimRef struct {
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// VolumeIntentionPhase is the small state machine the election runs through.
type VolumeIntentionPhase string

const (
	// VolumePhasePending — no eligible node has been elected yet (none carries the role label, or
	// none is Ready).
	VolumePhasePending VolumeIntentionPhase = "Pending"
	// VolumePhasePlaced — a node is elected and both the ZFSVolume and the PV carry it.
	VolumePhasePlaced VolumeIntentionPhase = "Placed"
	// VolumePhaseFailed — a reconcile step errored; see the conditions.
	VolumePhaseFailed VolumeIntentionPhase = "Failed"
)

// The condition types the VolumeIntention reconciler reports, one per step.
const (
	// VolumeConditionNodeElected — an eligible node was chosen (and is recorded in status.node).
	VolumeConditionNodeElected = "NodeElected"
	// VolumeConditionVolumePlaced — the ZFSVolume and the static PV exist and name the elected node.
	VolumeConditionVolumePlaced = "VolumePlaced"
	// VolumeConditionReady — the volume is placed; the PVC can bind.
	VolumeConditionReady = "Ready"
)

// VolumeIntentionStatus records WHICH node was elected, so the choice is visible and stable.
type VolumeIntentionStatus struct {
	// Phase is the coarse state.
	// +optional
	Phase VolumeIntentionPhase `json:"phase,omitempty"`

	// Node is the elected node's NAME — the value stamped into ZFSVolume.ownerNodeID (which must
	// equal the ZFSNode object's name) and into the PV's nodeAffinity. It is STICKY: once a node is
	// elected and still eligible, the election does not move, because moving it would strand the
	// dataset on the previous node.
	// +optional
	Node string `json:"node,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// LastReconcileTime is when the controller last reconciled this VolumeIntention.
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
// +kubebuilder:resource:scope=Namespaced,shortName=volint
// +kubebuilder:printcolumn:name="Dataset",type=string,JSONPath=`.spec.dataset`
// +kubebuilder:printcolumn:name="Node",type=string,JSONPath=`.status.node`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// VolumeIntention is the Flux-owned intent for one of the cluster's persist volumes. The controller
// elects a node and owns the resulting ZFSVolume + static PV — the half GitOps cannot pre-set,
// because a managed node's name is not knowable until the node exists.
//
// Unlike the {Cluster,Pool}Intention pair there is no Adoption mirror: those exist because a cluster
// can be found ALREADY RUNNING and must be reconciled with rather than created. A volume has no such
// duality — it is placed or it is not — so the intent carries its own status.
type VolumeIntention struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   VolumeIntentionSpec   `json:"spec,omitempty"`
	Status VolumeIntentionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// VolumeIntentionList is a list of VolumeIntention.
type VolumeIntentionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []VolumeIntention `json:"items"`
}

func init() {
	SchemeBuilder.Register(&VolumeIntention{}, &VolumeIntentionList{})
}
