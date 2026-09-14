package controller

import (
	"context"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

const (
	pausedAnnotation  = "cluster.x-k8s.io/paused"
	clusterNameLabel  = "cluster.x-k8s.io/cluster-name"
	controlPlaneLabel = "cluster.x-k8s.io/control-plane"
	clusterSecretType = "cluster.x-k8s.io/secret"

	// The annotation rke2lab stamps on the RKE2 config ConfigMaps it renders per (cluster × pool)
	// into namespace rke2lab-<cluster> (RuntimeRke2ConfigManifestsUnit). seed-incluster reads them to
	// bootstrap-inject a managed cluster's config.yaml.d — the same fragments a standalone node
	// git-fetches via install-rke2-config, delivered here through CAPRKE2 instead.
	rke2ConfigAnnotation = "io.seedmatic.rke2lab/rke2-config"

	// CAPN's LXCMachine instance-presence signal (cluster-api-provider-incus
	// api/v1alpha2/condition_consts.go) — read from the LXCMachine WE create, instead of probing
	// Incus ourselves. InstanceProvisioned=True (reason InstanceProvisioned) = instance present
	// (adopted); =False reason InstanceDeleted = absent ("does not exist anymore").
	capnInstanceProvisionedCondition = "InstanceProvisioned"
	capnInstanceDeletedReason        = "InstanceDeleted"

	// CAPI's Cluster accessibility signal — "the apiserver is reachable" (the blocker that flipped
	// True at the mgmt self-adoption WIN). Cluster-grain: presence answers "does the cluster exist?",
	// this answers "is its access available?".
	capiRemoteConnectionProbeCondition = "RemoteConnectionProbe"
)

// The GVKs of the CAPI/CAPN/CAPRKE2 objects the controllers build. Handled UNSTRUCTURED so the
// controller carries no typed CAPI/CAPRKE2/CAPN module dependency — it speaks their group/kind.
var (
	gvkCluster            = schema.GroupVersionKind{Group: "cluster.x-k8s.io", Version: "v1beta2", Kind: "Cluster"}
	gvkMachine            = schema.GroupVersionKind{Group: "cluster.x-k8s.io", Version: "v1beta2", Kind: "Machine"}
	gvkRKE2ControlPlane   = schema.GroupVersionKind{Group: "controlplane.cluster.x-k8s.io", Version: "v1beta2", Kind: "RKE2ControlPlane"}
	gvkRKE2Config         = schema.GroupVersionKind{Group: "bootstrap.cluster.x-k8s.io", Version: "v1beta2", Kind: "RKE2Config"}
	gvkLXCCluster         = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCCluster"}
	gvkLXCMachine         = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCMachine"}
	gvkLXCMachineTemplate = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCMachineTemplate"}
	gvkSecret             = schema.GroupVersionKind{Version: "v1", Kind: "Secret"}
)

// newObj is a blank unstructured of the given GVK, named + namespaced.
func newObj(gvk schema.GroupVersionKind, name, namespace string) *unstructured.Unstructured {
	obj := &unstructured.Unstructured{}
	obj.SetGroupVersionKind(gvk)
	obj.SetName(name)
	obj.SetNamespace(namespace)
	return obj
}

// ensure creates obj if it does not exist; an already-existing object is left untouched (the
// controller does not fight drift on the CAPI objects — CAPRKE2/CAPN own their evolution).
func ensure(ctx context.Context, c client.Client, obj *unstructured.Unstructured) error {
	existing := &unstructured.Unstructured{}
	existing.SetGroupVersionKind(obj.GroupVersionKind())
	err := c.Get(ctx, types.NamespacedName{Namespace: obj.GetNamespace(), Name: obj.GetName()}, existing)
	if err == nil {
		return nil
	}
	if !apierrors.IsNotFound(err) {
		return err
	}
	return c.Create(ctx, obj)
}

// setCondition upserts a condition, preserving LastTransitionTime unless the Status flips.
func setCondition(conditions *[]metav1.Condition, cond metav1.Condition) {
	for i := range *conditions {
		if (*conditions)[i].Type == cond.Type {
			if (*conditions)[i].Status != cond.Status {
				(*conditions)[i] = cond
			} else {
				(*conditions)[i].Reason = cond.Reason
				(*conditions)[i].Message = cond.Message
				(*conditions)[i].ObservedGeneration = cond.ObservedGeneration
			}
			return
		}
	}
	*conditions = append(*conditions, cond)
}

func conditionTrue(conditions []metav1.Condition, condType string) bool {
	for i := range conditions {
		if conditions[i].Type == condType {
			return conditions[i].Status == metav1.ConditionTrue
		}
	}
	return false
}

// conditionReason returns the reason of the named condition (empty if unset) — lets a derivePhase
// distinguish WHY a step is not-True (e.g. PetsPresent False NodesAbsent → Provisioning).
func conditionReason(conditions []metav1.Condition, condType string) string {
	for i := range conditions {
		if conditions[i].Type == condType {
			return conditions[i].Reason
		}
	}
	return ""
}

func toAny(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

func ptrBool(b bool) *bool { return &b }
