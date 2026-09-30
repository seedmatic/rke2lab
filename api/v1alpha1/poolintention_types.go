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
// PoolNature says who NAMES a pool's instances — see PoolIntentionSpec.Nature.
type PoolNature string

const (
	// PoolNaturePet — the declaration names the instance (a host grow posed it). spec.nodes IS the
	// roster, and the pool can never be greenfielded.
	PoolNaturePet PoolNature = "pet"
	// PoolNatureCattle — the provisioner names the instance, so the roster is OBSERVED and the count
	// comes from spec.replicas.
	PoolNatureCattle PoolNature = "cattle"
)

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

	// Image NAMES the NodeImage the pool's LXCMachines boot on / adopt — the realised node-base, with
	// the runtime contract an instance needs to run it.
	Image ImageRef `json:"image"`

	// RKE2Version is the CAPRKE2 version for this pool (control-plane RCP, or worker RKE2ConfigTemplate).
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// ControlPlaneEndpoint is the kube-vip VIP — a control-plane pool registers its replicas on it.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// Nodes is the pool's explicit pet roster, adopted/provisioned by providerID (lxc:///<name>).
	// +kubebuilder:validation:MinItems=1
	Nodes []PetSpec `json:"nodes"`

	// Nature says WHO NAMES this pool's instances — the last of the triad's three questions to stop
	// being inferred.
	//
	// ★ It used to be read off the PRESENCE of a PoolReflection file, which is a runtime accident: the
	// same pool answered "declaration" before its first reflection existed and "provisioner" after,
	// with nothing declaring the difference. Q1 (who CREATES) and Q3 (who ADDRESSES) were already
	// derived from the role; only this one had no owner.
	//
	// `pet` — the DECLARATION names the instance (a host `grow` posed it, so spec.nodes IS the roster,
	// and such a pool can never be greenfielded). `cattle` — the PROVISIONER names it, so the roster
	// is OBSERVED (locally for the self cluster, from the reflection for a child) and the count comes
	// from Replicas.
	//
	// ⚠️ It is NOT derivable from the role: nikopol-mgmt is `kind: management` and cattle, because its
	// PARENT birthed it through CAPRKE2 (its node is …-control-plane-9f5kb, not the declared
	// …-master). What it tracks is whether the cluster was grown out-of-band — true of the root alone.
	// +optional
	// +kubebuilder:validation:Enum=pet;cattle
	Nature PoolNature `json:"nature,omitempty"`

	// Replicas is the INTENDED size of this pool — the desired count, declared.
	//
	// ★ It exists because the count used to be read from the ROSTER, and the roster is OBSERVED. So
	// once a pool had run at N, `len(roster)` returned N, the RKE2ControlPlane was sized N, CAPRKE2
	// provisioned N, the reflector observed N, and the loop closed on itself: the pool was pinned at
	// whatever it first came up as, for good. Measured 2026-09-29 and again after the 2026-09-30 cold
	// start: bioskop-wrkld declared THREE pets and stood at `replicas: 1`, because a one-node
	// reflection survives a cold start in git.
	//
	// So the count comes from the INTENT and the names from OBSERVATION. Those are different
	// questions and the roster only ever answered the second one.
	//
	// Optional in the SCHEMA so a pre-migration branch still applies (a required field would have the
	// apiserver reject it before the controller could name the remedy); zero is refused at the READ.
	// +optional
	// +kubebuilder:validation:Minimum=1
	Replicas int32 `json:"replicas,omitempty"`

	// NodeLabels are the kubelet --node-label values every node of this pool registers with, as
	// "key=value". Pool-scoped because that is the grain CAPRKE2's agentConfig has (one
	// RKE2ControlPlane per control-plane pool, one RKE2ConfigTemplate per worker pool) — so a future
	// pool can carry labels this one does not.
	//
	// The render declares them: a CAPN-provisioned node cannot get them from the nixos
	// rke2lab-node-labels oneshot, which is gated on /var/lib/rke2lab/node.env and so runs only on a
	// host-grown node.
	// +optional
	NodeLabels []string `json:"nodeLabels,omitempty"`

	// Devices are the Incus device definitions every node of this pool gets, posed INLINE on the
	// LXCMachine/template — each entry in CAPN's `<device>,<key>=<value>` form, e.g.
	// "vmnet0,type=nic,nictype=bridged,parent=vmnet-mgmt".
	//
	// PUBLISHED by the render, not derived here: the set comes from the addressing blueprint (which
	// bridge a cluster's role sits on, the root pool, the unix-char passthroughs), and deriving the
	// bridge name again in Go would re-create the cross-language naming convention that inlining these
	// removed. It replaces the "node-base" + "node-<cluster>" Incus profiles the HOST grow used to
	// create — two resources for clusters the host does not otherwise know about, behind a provider
	// that could create a profile's devices and never correct them.
	// +optional
	Devices []string `json:"devices,omitempty"`

	// Target is the Incus CLUSTER MEMBER this pool's instances must be created on — the value that
	// becomes LXCMachineTemplate.spec.target, and through it CAPN's placement.
	//
	// It has to be stated, because Incus will otherwise choose: "the automatic assignment picks the
	// cluster member that has the lowest number of instances. If several members have the same amount
	// of instances, one of the members is CHOSEN AT RANDOM." A cluster is addressed on ONE host's
	// segments, so a node born on the wrong member looks for a lease on a subnet whose DHCP
	// reservation lives elsewhere, and the addressing plan goes with it.
	//
	// Pool-scoped for the same reason NodeLabels is: that is the grain the consumer has — one
	// LXCMachineTemplate per pool. Not cluster-scoped, even though every pool of a cluster shares a
	// host today, because the field maps 1:1 onto the CR it fills.
	//
	// +optional for now, and that is a migration concession rather than a design one: objects already
	// live on the management cluster, and making it required would invalidate them on their next
	// write. Empty means "let Incus choose", which is only safe while exactly ONE member is eligible
	// for automatic placement (ndh pins every other member to scheduler.instance=manual). Every
	// producer should set it; tighten to required once they all do.
	// +optional
	Target string `json:"target,omitempty"`
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
