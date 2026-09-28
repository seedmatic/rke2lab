package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// NodeImageSpec is the nix-built node-base image seed-master ALREADY REALISED, described to the
// cluster that depends on it: its identity, where it lives, what it bakes, and the runtime contract
// an instance must carry to run it.
//
// It is a FACT, not an intention — the image exists on the source remote before this object is
// written, which is why there is no reconcile loop and no status: nothing here is converged toward.
// The consumer is the PoolAdoption reconciler, which derives BOTH the LXCMachineTemplate's image pin
// AND its instance config from this one object.
//
// ★ Why the image deserves an object at all. Its identity used to be SHREDDED: the fingerprint was
// embedded inside each pool's spec, the baked RKE2 version sat beside it as a sibling scalar
// (re-normalised at two render sites), the Incus project went into an identity Secret, and the
// alias / build checksum / source remote went into a `<cluster>-image-state` ConfigMap that NOTHING
// ever read. Because no object owned the image, the runtime contract of that image had nowhere to
// live — so it was restated twice, once in rke2lab's host-side `node-base` Incus profile and once
// inline here, and the two DIVERGED: the inline copy was missing xfrm_user, nft_compat and the four
// xt_* extensions, each of which had been established by loading it and watching the cilium agent.
// Without xfrm_user the agent's route reconciler dies on "protocol not supported"; without
// nft_compat only 10 of its 34 iptables rules land, host-originated traffic is identified as
// world-ipv4, and any pod carrying a policy denies the kubelet's health probes.
//
// One owner for the artifact makes that divergence structurally impossible rather than merely fixed.
type NodeImageSpec struct {
	// Alias is the Incus image alias the build published (e.g. "control-node"). Human-facing and
	// re-pointable, which is exactly why instances pin Fingerprint instead.
	// +kubebuilder:validation:MinLength=1
	Alias string `json:"alias"`

	// Fingerprint is the immutable content hash — sha256(metadata.tar.xz ++ rootfs.squashfs), the way
	// Incus derives a SPLIT image's fingerprint. This is what an LXCMachineTemplate pins, so a pool
	// launches from the exact image seed-master built rather than whatever the alias points at now.
	// +kubebuilder:validation:MinLength=1
	Fingerprint string `json:"fingerprint"`

	// BuildChecksum is the SHA-256 of the image BUILD INPUTS (the recipe digest and its artifacts),
	// as opposed to Fingerprint which hashes the OUTPUT. Two images with the same build checksum were
	// asked for from the same inputs; a mismatch against a later render means the recipe moved.
	//
	// Carried as part of the artifact's identity. Nothing reads it yet — drift detection is the reason
	// it exists and it is stated here rather than invented later, but no controller compares it today.
	// +optional
	BuildChecksum string `json:"buildChecksum,omitempty"`

	// RKE2Version is the RKE2 release BAKED INTO this image, read from the `rke2.version` artifact the
	// nix build emits beside the rootfs — never a hand-pinned literal. It is a property of the image
	// because the image is what contains it, and it is the source for RKE2ControlPlane.spec.version.
	//
	// Normalised to a leading "v" by the producer, so no consumer re-normalises.
	// +kubebuilder:validation:MinLength=1
	RKE2Version string `json:"rke2Version"`

	// Source is the Incus engine + project this image lives in.
	Source ImageSource `json:"source"`

	// Runtime is the container contract an instance of this image must carry to run a node.
	Runtime NodeRuntime `json:"runtime"`
}

// ImageSource is WHERE a node image lives — the Incus engine that holds it and the project within
// it. Distinct from Remote (the engine a cluster's INSTANCES are created on): those coincide today,
// but one is about finding an artifact and the other about placing a workload, and only Remote needs
// a client identity.
type ImageSource struct {
	// Endpoint is the Incus API endpoint holding the image (e.g.
	// "https://nixos.bioskop:8443"). The FABRIC FQDN, not a bare host name, because this value is
	// read from INSIDE the cluster: a pod resolves through CoreDNS, where a bare host name came back
	// as the host's own loopback.
	// +kubebuilder:validation:MinLength=1
	Endpoint string `json:"endpoint"`

	// Project is the Incus project the image lives in ("rke2lab"). Images are listed PER PROJECT, so
	// the project is part of the coordinate, not an ambient default.
	// +kubebuilder:validation:MinLength=1
	Project string `json:"project"`
}

// NodeRuntime is the container contract a node instance must carry — modelled on the Incus keys it
// becomes (`linux.kernel_modules`, `raw.lxc`, `security.*`) rather than as an opaque map, so a
// missing field is a schema error instead of a silent default.
//
// ⚠️ It is a function of the image AND of the substrate: the kernel-module set is what a
// nftables-only kernel-6.18 host has to give back to cilium. It sits on NodeImage because the fleet
// has ONE substrate today, every bare-metal being built by ndh. Were a host to diverge on kernel,
// this becomes a contract per (image × host) and would move accordingly — stated so the seam is
// visible rather than pre-abstracted.
type NodeRuntime struct {
	// KernelModules are loaded by Incus ON THE HOST for the container, because a container has neither
	// kernel nor module tree and cannot modprobe for itself. Rendered into `linux.kernel_modules`.
	//
	// ★ This MUST be posed at the instance level, not left to the profile: CAPN applies one of its
	// embedded instance profiles (kind/kubeadm/…) at instance-config level, and every one of them
	// sets `linux.kernel_modules` INCLUDING the legacy iptables trio (ip_tables/ip6_tables/
	// iptable_raw) that FATAL-modprobes on a nftables-only kernel. Instance config wins over both
	// that embedded default and our own profiles, so this list is the one that decides.
	// +kubebuilder:validation:MinItems=1
	KernelModules []string `json:"kernelModules"`

	// RawLXC is passed through as `raw.lxc` — the mount/apparmor/capability relaxations a Kubernetes
	// node needs inside a container.
	// +optional
	RawLXC string `json:"rawLXC,omitempty"`

	// Security is the `security.*` key group.
	Security NodeSecurity `json:"security"`
}

// NodeSecurity mirrors the Incus `security.*` keys a node instance needs. Named per key rather than
// collapsed into a single "privileged" flag, because they are independently meaningful to Incus and
// a reader should see exactly which ones we ask for.
type NodeSecurity struct {
	// Privileged maps to `security.privileged`.
	Privileged bool `json:"privileged"`

	// Nesting maps to `security.nesting` — required because the node runs containers itself.
	Nesting bool `json:"nesting"`

	// InterceptBPF maps to `security.syscalls.intercept.bpf`, which cilium's eBPF datapath needs.
	InterceptBPF bool `json:"interceptBPF"`

	// InterceptBPFDevices maps to `security.syscalls.intercept.bpf.devices`.
	InterceptBPFDevices bool `json:"interceptBPFDevices"`
}

// +kubebuilder:object:root=true
// +kubebuilder:resource:scope=Namespaced,shortName=nodeimg
// +kubebuilder:printcolumn:name="Alias",type=string,JSONPath=`.spec.alias`
// +kubebuilder:printcolumn:name="RKE2",type=string,JSONPath=`.spec.rke2Version`
// +kubebuilder:printcolumn:name="Fingerprint",type=string,JSONPath=`.spec.fingerprint`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// NodeImage is the realised node-base image a cluster's pools boot on, described to that cluster by
// seed-master. One per cluster namespace: every cluster receives the description of the resources it
// depends on, which is the same reason PoolIntention duplicates its cluster-level facts.
type NodeImage struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec NodeImageSpec `json:"spec,omitempty"`
}

// +kubebuilder:object:root=true

// NodeImageList is a list of NodeImage.
type NodeImageList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []NodeImage `json:"items"`
}

func init() {
	SchemeBuilder.Register(&NodeImage{}, &NodeImageList{})
}
