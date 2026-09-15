package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// PoolReflectionSpec is the OBSERVED, DURABLE roster of one node pool — the reflector's cluster→git
// record of what the pool ACTUALLY ran. It completes the role triad with the 2×2: PoolIntention
// (desired, Flux/git) · PoolAdoption (observed EPHEMERAL, controller/etcd) · PoolReflection (observed
// DURABLE, reflector/git). The reflector watches the pool's Machines and commits this as a YAML
// document onto the managing manifests/<cluster> branch; it is git-only (never applied to etcd) and
// survives a cold-start because git is durable. The reflector reads it back for the decision. Its
// PRESENCE is the adopt-vs-greenfield switch: present ⇒ the pool has lived, ADOPT the observed roster
// (pre-create the named Machines so CAPRKE2 adopts by name); absent ⇒ first boot or a deliberate
// reset, GREENFIELD (see docs/architecture/cluster-api/cluster-seeding-controller.adoc).
//
// +kubebuilder:object:generate=false
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

// PoolReflectionStatus is a light in-document record (when it was last reflected) — the reflection's
// VALUE is its spec (the roster). It is not a k8s status subresource (the document is git-only).
//
// +kubebuilder:object:generate=false
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

// +kubebuilder:object:generate=false

// PoolReflection is the reflector-owned, git-durable observed roster of one node pool — a **GIT-ONLY
// YAML document**, NOT an installed CRD and never applied to etcd (see the reflector design in
// docs/architecture/cluster-api/cluster-seeding-controller.adoc). CR-shaped for readability; the
// reflector marshals it onto the managing `manifests/<cluster>` branch (alongside the intentions,
// preserved by the render's escape allow-list) and reads it back for the adopt-vs-greenfield decision.
// Its PRESENCE in git is the switch. So it is a plain document type: no scheme registration, no
// DeepCopyObject, no CRD manifest — it round-trips through sigs.k8s.io/yaml only.
type PoolReflection struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   PoolReflectionSpec   `json:"spec,omitempty"`
	Status PoolReflectionStatus `json:"status,omitempty"`
}
