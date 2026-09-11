package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ClusterAdoptionSpec is the recipe seed-master fills for one cluster to be adopted into
// Cluster API. The controller expands it into the full CAPI CR-set (Cluster + LXCCluster +
// RKE2ControlPlane + LXCMachineTemplate) and — the part GitOps cannot do — creates the OWNED
// control-plane Machine + concrete LXCMachine (providerID) so CAPRKE2/CAPN adopt the RUNNING
// Pulumi-bootstrapped instance instead of provisioning a fresh one. The BYO-CA Secrets
// (<clusterName>-{ca,cca,etcd,peer-etcd}) and the identity Secret are delivered by seed-master
// into Namespace (branch, sops-encrypted); this controller only references them.
type ClusterAdoptionSpec struct {
	// ClusterName is the CAPI Cluster name to create and adopt (e.g. "bioskop-mgmt"). The
	// control-plane instance adopted is "<ClusterName>-master" with providerID
	// "lxc:///<ClusterName>-master" — the deterministic name Pulumi grew.
	// +kubebuilder:validation:MinLength=1
	ClusterName string `json:"clusterName"`

	// Namespace is where the CAPI CR-set and the referenced Secrets live (e.g.
	// "rke2lab-bioskop-mgmt").
	// +kubebuilder:validation:MinLength=1
	Namespace string `json:"namespace"`

	// ControlPlaneReplicas is the RKE2ControlPlane replica count — 1 for a management
	// cluster (single control node), 3 for a workload HA plane.
	// +kubebuilder:validation:Minimum=1
	ControlPlaneReplicas int32 `json:"controlPlaneReplicas"`

	// ControlPlaneEndpoint is the kube-vip VIP fronting the apiserver.
	ControlPlaneEndpoint APIEndpoint `json:"controlPlaneEndpoint"`

	// ClusterNetwork carries the pod/service CIDRs + the service domain.
	ClusterNetwork ClusterNetwork `json:"clusterNetwork"`

	// Image pins the nix-built node-base by fingerprint and the RKE2 version — the SAME
	// node-base the running instance booted on (so the adopted machine template matches).
	Image ImageRef `json:"image"`

	// IdentitySecretName is the CAPN per-remote incus identity Secret the LXCCluster.secretRef
	// names (e.g. "bioskop-incus-identity"), resolved in Namespace.
	// +kubebuilder:validation:MinLength=1
	IdentitySecretName string `json:"identitySecretName"`

	// RKE2Version is the CAPRKE2 control-plane version (e.g. "v1.34.8+rke2r2").
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// KubeVIPVersion pins the kube-vip image the control-plane bootstrap deploys.
	// +kubebuilder:validation:MinLength=1
	KubeVIPVersion string `json:"kubeVIPVersion"`
}

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

// ImageRef pins the nix-built node-base image.
type ImageRef struct {
	// +kubebuilder:validation:MinLength=1
	Fingerprint string `json:"fingerprint"`
}

// ClusterAdoptionPhase is the coarse lifecycle of an adoption.
type ClusterAdoptionPhase string

const (
	// PhasePending — the CR-set has not been fully created yet.
	PhasePending ClusterAdoptionPhase = "Pending"
	// PhaseAdopting — the CR-set exists and the owned Machine has been created; waiting for
	// CAPRKE2/CAPN to bind it to the running instance.
	PhaseAdopting ClusterAdoptionPhase = "Adopting"
	// PhaseAdopted — the control plane is adopted (the Machine has a NodeRef / providerID
	// bound) and the Cluster is unpaused.
	PhaseAdopted ClusterAdoptionPhase = "Adopted"
	// PhaseFailed — a reconcile step errored; see the conditions for which and why.
	PhaseFailed ClusterAdoptionPhase = "Failed"
)

// The condition types the reconciler reports, one per step of the adoption flow — so `kubectl
// describe clusteradoption` shows exactly HOW FAR the reconcile got and WHERE it stopped. Each is
// set True on success, False (with a reason + the error message) on the step that failed.
const (
	// ConditionMaterialReady — the seed-master-delivered BYO-CA + identity Secrets are present.
	ConditionMaterialReady = "MaterialReady"
	// ConditionCRSetCreated — the Cluster/LXCCluster/LXCMachineTemplate/RKE2ControlPlane exist.
	ConditionCRSetCreated = "CRSetCreated"
	// ConditionControlPlaneObserved — the RKE2ControlPlane UID (for the Machine ownerRef) was read.
	ConditionControlPlaneObserved = "ControlPlaneObserved"
	// ConditionMachineCreated — the owned Machine + concrete LXCMachine(providerID) + sentinel exist.
	ConditionMachineCreated = "MachineCreated"
	// ConditionUnpaused — the RKE2ControlPlane + Cluster were un-paused (adoption released).
	ConditionUnpaused = "Unpaused"
	// ConditionReady — a roll-up: every step above succeeded this reconcile.
	ConditionReady = "Ready"
)

// ClusterAdoptionStatus records what the controller observed.
type ClusterAdoptionStatus struct {
	// Phase is the coarse lifecycle stage.
	// +optional
	Phase ClusterAdoptionPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status reflects.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// AdoptedInstance is the instance name the owned Machine binds (e.g.
	// "bioskop-mgmt-master").
	// +optional
	AdoptedInstance string `json:"adoptedInstance,omitempty"`

	// ProviderID is the providerID the owned Machine/LXCMachine carry (e.g.
	// "lxc:///bioskop-mgmt-master").
	// +optional
	ProviderID string `json:"providerID,omitempty"`

	// ControlPlaneUID is the observed RKE2ControlPlane UID the owned Machine's ownerRef carries —
	// the piece GitOps could not pre-set; empty until the RCP is observable.
	// +optional
	ControlPlaneUID string `json:"controlPlaneUID,omitempty"`

	// LastReconcileTime is when the controller last reconciled this ClusterAdoption.
	// +optional
	LastReconcileTime metav1.Time `json:"lastReconcileTime,omitempty"`

	// Conditions follow the standard metav1.Condition contract — one per adoption step (see the
	// Condition* constants), so the reconcile progress + any failure are visible per-step.
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
// +kubebuilder:printcolumn:name="Instance",type=string,JSONPath=`.status.adoptedInstance`
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClusterAdoption is the request to adopt one running RKE2-on-Incus control plane into CAPI.
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
