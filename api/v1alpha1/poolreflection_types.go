package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// PoolReflectionSpec is the OBSERVED, DURABLE roster of one node pool — the reflector's cluster→git
// record of what the pool ACTUALLY ran. It completes the role triad with the 2×2: PoolIntention
// (desired, Flux/git) · PoolAdoption (observed EPHEMERAL, controller/etcd) · PoolReflection (observed
// DURABLE, reflector/git). The reflector watches the pool's Machines and commits this onto the
// managing branch (manifests/<cluster>); Flux applies it back, so it survives a cold-start that wipes
// etcd. Its PRESENCE is the adopt-vs-greenfield switch: present ⇒ the pool has lived, ADOPT the
// observed roster (pre-create the named Machines so CAPRKE2 adopts by name); absent ⇒ first boot or a
// deliberate reset, GREENFIELD (see docs/architecture/cluster-api/cluster-seeding-controller.adoc).
type PoolReflectionSpec struct {
	// ClusterRef is the cluster this pool belongs to (= ClusterIntention.spec.clusterName). Carried so
	// the cluster view can list its pools' reflections (LabelCluster), like PoolAdoption.
	// +kubebuilder:validation:MinLength=1
	ClusterRef string `json:"clusterRef"`

	// Namespace is where the pool's CAPI CR-set lives (the reflection is applied here by Flux).
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// Pool is the pool identity the roster belongs to (e.g. "control-node").
	// +kubebuilder:validation:MinLength=1
	Pool string `json:"pool"`

	// Nodes is the OBSERVED roster — the deterministic node names the reflector saw running (whatever
	// CAPRKE2 minted in greenfield, or the adopted names). This is the roster the controller re-adopts
	// by name on the next boot; the canonical PoolIntention seed is used ONLY to size a greenfield RCP.
	// +optional
	Nodes []PetSpec `json:"nodes,omitempty"`
}

// PoolReflectionStatus is a light observability record — the reflection's VALUE is its spec (the
// roster). It is not a state machine.
type PoolReflectionStatus struct {
	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// NodeCount is len(spec.nodes) — surfaced for a print column.
	// +optional
	NodeCount int32 `json:"nodeCount,omitempty"`

	// LastReflectTime is when the reflector last wrote this roster (cluster→git export).
	// +optional
	LastReflectTime metav1.Time `json:"lastReflectTime,omitempty"`

	// Conditions follow the standard metav1.Condition contract.
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,shortName=capipoolrefl
// +kubebuilder:printcolumn:name="Cluster",type=string,JSONPath=`.spec.clusterRef`
// +kubebuilder:printcolumn:name="Pool",type=string,JSONPath=`.spec.pool`
// +kubebuilder:printcolumn:name="Nodes",type=string,JSONPath=`.status.nodeCount`
// +kubebuilder:printcolumn:name="Reflected",type=date,JSONPath=`.status.lastReflectTime`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// PoolReflection is the reflector-owned, git-durable observed roster of one node pool — the DURABLE
// sibling of the ephemeral PoolAdoption. Its presence is the adopt-vs-greenfield switch.
type PoolReflection struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   PoolReflectionSpec   `json:"spec,omitempty"`
	Status PoolReflectionStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true

// PoolReflectionList is a list of PoolReflection.
type PoolReflectionList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []PoolReflection `json:"items"`
}

func init() {
	SchemeBuilder.Register(&PoolReflection{}, &PoolReflectionList{})
}
