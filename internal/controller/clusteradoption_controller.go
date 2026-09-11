package controller

import (
	"context"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/rke2-adoption-controller/api/v1alpha1"
)

const (
	pausedAnnotation  = "cluster.x-k8s.io/paused"
	clusterNameLabel  = "cluster.x-k8s.io/cluster-name"
	controlPlaneLabel = "cluster.x-k8s.io/control-plane"
	clusterSecretType = "cluster.x-k8s.io/secret"
)

// The GVKs of the CAPI/CAPN/CAPRKE2 objects the controller builds. Handled UNSTRUCTURED so the
// controller carries no typed CAPI/CAPRKE2/CAPN module dependency — it speaks their group/kind.
var (
	gvkCluster            = schema.GroupVersionKind{Group: "cluster.x-k8s.io", Version: "v1beta2", Kind: "Cluster"}
	gvkMachine            = schema.GroupVersionKind{Group: "cluster.x-k8s.io", Version: "v1beta2", Kind: "Machine"}
	gvkRKE2ControlPlane   = schema.GroupVersionKind{Group: "controlplane.cluster.x-k8s.io", Version: "v1beta2", Kind: "RKE2ControlPlane"}
	gvkLXCCluster         = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCCluster"}
	gvkLXCMachine         = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCMachine"}
	gvkLXCMachineTemplate = schema.GroupVersionKind{Group: "infrastructure.cluster.x-k8s.io", Version: "v1alpha2", Kind: "LXCMachineTemplate"}
)

// ClusterAdoptionReconciler adopts a running RKE2-on-Incus control plane into Cluster API.
type ClusterAdoptionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=adoption.seedmatic.io,resources=clusteradoptions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=adoption.seedmatic.io,resources=clusteradoptions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=clusters;machines,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=controlplane.cluster.x-k8s.io,resources=rke2controlplanes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcclusters;lxcmachines;lxcmachinetemplates,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create

// Reconcile drives one ClusterAdoption toward Adopted. Every object is created-if-absent, so a
// re-reconcile (and a management cold-start, which wipes etcd and re-creates the CR-set) safely
// re-adopts the SURVIVING instance by deterministic name + providerID.
func (r *ClusterAdoptionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	logger := log.FromContext(ctx)

	var adoption adoptionv1alpha1.ClusterAdoption
	if err := r.Get(ctx, req.NamespacedName, &adoption); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	spec := adoption.Spec

	// Guard: the BYO-CA + identity Secrets are delivered by seed-master (branch, sops). CAPRKE2
	// adopts the LIVE CA from <cluster>-{ca,cca,etcd,peer-etcd}; without them it would generate a
	// fresh CA and the adopted apiserver would reject the minted admin cert. Wait until present.
	if missing, err := r.materialMissing(ctx, spec); err != nil {
		return ctrl.Result{}, err
	} else if missing != "" {
		logger.Info("waiting for seed-master material", "missing", missing)
		return r.setPhase(ctx, &adoption, adoptionv1alpha1.PhasePending, "", "MaterialMissing", missing)
	}

	instance := spec.ClusterName + "-master"
	providerID := "lxc:///" + instance

	// 1. The infra + control-plane skeleton. The Cluster is created paused; the RKE2ControlPlane
	//    carries the paused annotation DIRECTLY (inert from birth — no wait for CAPI to propagate
	//    the Cluster's paused, closing the init race where the RCP would provision a random-named
	//    control plane before the owned Machine exists).
	if err := r.ensure(ctx, r.clusterObj(spec, true)); err != nil {
		return ctrl.Result{}, err
	}
	if err := r.ensure(ctx, r.lxcClusterObj(spec)); err != nil {
		return ctrl.Result{}, err
	}
	if err := r.ensure(ctx, r.lxcMachineTemplateObj(spec)); err != nil {
		return ctrl.Result{}, err
	}
	if err := r.ensure(ctx, r.rke2ControlPlaneObj(spec, true)); err != nil {
		return ctrl.Result{}, err
	}

	// 2. Read the RKE2ControlPlane UID — the piece GitOps cannot pre-set. The owned Machine's
	//    ownerRef must carry it, else CAPRKE2 refuses ("mixed management mode") and never adopts.
	rcpUID, err := r.rcpUID(ctx, spec)
	if err != nil {
		return ctrl.Result{}, err
	}
	if rcpUID == "" {
		logger.Info("RKE2ControlPlane not observable yet, requeuing")
		return r.setPhase(ctx, &adoption, adoptionv1alpha1.PhaseAdopting, instance, "AwaitingControlPlane",
			"RKE2ControlPlane UID not yet observable")
	}

	// 3. The adopted pair: the bootstrap sentinel (marks the Machine already-bootstrapped so CAPI
	//    never re-bootstraps the running node), the concrete LXCMachine (providerID → CAPN adopts
	//    the existing instance), and the OWNED control-plane Machine (ownerRef=RCP UID).
	if err := r.ensure(ctx, r.bootstrapSecretObj(spec)); err != nil {
		return ctrl.Result{}, err
	}
	if err := r.ensure(ctx, r.lxcMachineObj(spec, providerID)); err != nil {
		return ctrl.Result{}, err
	}
	if err := r.ensure(ctx, r.machineObj(spec, providerID, rcpUID)); err != nil {
		return ctrl.Result{}, err
	}

	// 4. Unpause: the owned Machine now exists, so on unpause the RCP counts it (numMachines ==
	//    replicas → no init) and CAPN adopts the instance (providerID + existing instance). Clear
	//    the RCP paused annotation and the Cluster's spec.paused.
	if err := r.unpause(ctx, spec); err != nil {
		return ctrl.Result{}, err
	}

	logger.Info("adoption reconciled", "cluster", spec.ClusterName, "instance", instance)
	return r.setPhase(ctx, &adoption, adoptionv1alpha1.PhaseAdopted, instance, "Adopted",
		"CR-set created; owned Machine bound to the running instance; unpaused")
}

// materialMissing returns the name of the first required seed-master Secret that is absent, or ""
// when all are present.
func (r *ClusterAdoptionReconciler) materialMissing(ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec) (string, error) {
	names := []string{
		spec.ClusterName + "-ca",
		spec.ClusterName + "-cca",
		spec.ClusterName + "-etcd",
		spec.ClusterName + "-peer-etcd",
		spec.IdentitySecretName,
	}
	for _, name := range names {
		var s corev1.Secret
		err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: name}, &s)
		if apierrors.IsNotFound(err) {
			return name, nil
		}
		if err != nil {
			return "", err
		}
	}
	return "", nil
}

// ensure creates obj if it does not exist; an already-existing object is left untouched (the
// controller does not fight drift on the CAPI objects — CAPRKE2/CAPN own their evolution).
func (r *ClusterAdoptionReconciler) ensure(ctx context.Context, obj *unstructured.Unstructured) error {
	existing := &unstructured.Unstructured{}
	existing.SetGroupVersionKind(obj.GroupVersionKind())
	err := r.Get(ctx, types.NamespacedName{Namespace: obj.GetNamespace(), Name: obj.GetName()}, existing)
	if err == nil {
		return nil
	}
	if !apierrors.IsNotFound(err) {
		return err
	}
	return r.Create(ctx, obj)
}

// rcpUID fetches the RKE2ControlPlane and returns its UID (empty if not found yet).
func (r *ClusterAdoptionReconciler) rcpUID(ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec) (types.UID, error) {
	rcp := &unstructured.Unstructured{}
	rcp.SetGroupVersionKind(gvkRKE2ControlPlane)
	err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: spec.ClusterName + "-control-plane"}, rcp)
	if apierrors.IsNotFound(err) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return rcp.GetUID(), nil
}

// unpause clears the RKE2ControlPlane paused annotation and sets the Cluster's spec.paused=false,
// letting the RCP adopt the owned Machine and CAPN adopt the instance.
func (r *ClusterAdoptionReconciler) unpause(ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec) error {
	rcp := &unstructured.Unstructured{}
	rcp.SetGroupVersionKind(gvkRKE2ControlPlane)
	if err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: spec.ClusterName + "-control-plane"}, rcp); err != nil {
		return err
	}
	annotations := rcp.GetAnnotations()
	if _, present := annotations[pausedAnnotation]; present {
		delete(annotations, pausedAnnotation)
		rcp.SetAnnotations(annotations)
		if err := r.Update(ctx, rcp); err != nil {
			return err
		}
	}

	cluster := &unstructured.Unstructured{}
	cluster.SetGroupVersionKind(gvkCluster)
	if err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: spec.ClusterName}, cluster); err != nil {
		return err
	}
	paused, _, _ := unstructured.NestedBool(cluster.Object, "spec", "paused")
	if paused {
		if err := unstructured.SetNestedField(cluster.Object, false, "spec", "paused"); err != nil {
			return err
		}
		if err := r.Update(ctx, cluster); err != nil {
			return err
		}
	}
	return nil
}

func (r *ClusterAdoptionReconciler) setPhase(
	ctx context.Context,
	adoption *adoptionv1alpha1.ClusterAdoption,
	phase adoptionv1alpha1.ClusterAdoptionPhase,
	instance, reason, message string,
) (ctrl.Result, error) {
	adoption.Status.Phase = phase
	adoption.Status.AdoptedInstance = instance
	meta := metav1.Condition{
		Type:               "Adopted",
		Status:             metav1.ConditionFalse,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: metav1.Now(),
		ObservedGeneration: adoption.Generation,
	}
	if phase == adoptionv1alpha1.PhaseAdopted {
		meta.Status = metav1.ConditionTrue
	}
	setCondition(&adoption.Status.Conditions, meta)
	if err := r.Status().Update(ctx, adoption); err != nil {
		return ctrl.Result{}, err
	}
	if phase == adoptionv1alpha1.PhaseAdopted {
		return ctrl.Result{}, nil
	}
	return ctrl.Result{Requeue: true}, nil
}

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

// SetupWithManager wires the reconciler to ClusterAdoption events.
func (r *ClusterAdoptionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.ClusterAdoption{}).
		Named("clusteradoption").
		Complete(r)
}

func (r *ClusterAdoptionReconciler) newObj(gvk schema.GroupVersionKind, name string, spec adoptionv1alpha1.ClusterAdoptionSpec) *unstructured.Unstructured {
	obj := &unstructured.Unstructured{}
	obj.SetGroupVersionKind(gvk)
	obj.SetName(name)
	obj.SetNamespace(spec.Namespace)
	return obj
}

func (r *ClusterAdoptionReconciler) clusterObj(spec adoptionv1alpha1.ClusterAdoptionSpec, paused bool) *unstructured.Unstructured {
	obj := r.newObj(gvkCluster, spec.ClusterName, spec)
	serviceDomain := spec.ClusterNetwork.ServiceDomain
	if serviceDomain == "" {
		serviceDomain = "cluster.local"
	}
	obj.Object["spec"] = map[string]any{
		"paused": paused,
		"clusterNetwork": map[string]any{
			"pods":          map[string]any{"cidrBlocks": toAny(spec.ClusterNetwork.PodCIDRs)},
			"services":      map[string]any{"cidrBlocks": toAny(spec.ClusterNetwork.ServiceCIDRs)},
			"serviceDomain": serviceDomain,
		},
		"controlPlaneEndpoint": map[string]any{
			"host": spec.ControlPlaneEndpoint.Host,
			"port": int64(spec.ControlPlaneEndpoint.Port),
		},
		"controlPlaneRef": map[string]any{
			"apiGroup": gvkRKE2ControlPlane.Group,
			"kind":     gvkRKE2ControlPlane.Kind,
			"name":     spec.ClusterName + "-control-plane",
		},
		"infrastructureRef": map[string]any{
			"apiGroup": gvkLXCCluster.Group,
			"kind":     gvkLXCCluster.Kind,
			"name":     spec.ClusterName,
		},
	}
	return obj
}

func (r *ClusterAdoptionReconciler) lxcClusterObj(spec adoptionv1alpha1.ClusterAdoptionSpec) *unstructured.Unstructured {
	obj := r.newObj(gvkLXCCluster, spec.ClusterName, spec)
	obj.Object["spec"] = map[string]any{
		"secretRef": map[string]any{"name": spec.IdentitySecretName},
		"controlPlaneEndpoint": map[string]any{
			"host": spec.ControlPlaneEndpoint.Host,
			"port": int64(spec.ControlPlaneEndpoint.Port),
		},
		"loadBalancer": map[string]any{"kubeVIP": map[string]any{}},
	}
	return obj
}

func (r *ClusterAdoptionReconciler) lxcMachineTemplateObj(spec adoptionv1alpha1.ClusterAdoptionSpec) *unstructured.Unstructured {
	obj := r.newObj(gvkLXCMachineTemplate, spec.ClusterName+"-control-plane", spec)
	obj.Object["spec"] = map[string]any{
		"template": map[string]any{"spec": lxcMachineSpec(spec.Image.Fingerprint)},
	}
	return obj
}

func (r *ClusterAdoptionReconciler) lxcMachineObj(spec adoptionv1alpha1.ClusterAdoptionSpec, providerID string) *unstructured.Unstructured {
	obj := r.newObj(gvkLXCMachine, spec.ClusterName+"-master", spec)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	body := lxcMachineSpec(spec.Image.Fingerprint)
	body["providerID"] = providerID
	obj.Object["spec"] = body
	return obj
}

func (r *ClusterAdoptionReconciler) machineObj(spec adoptionv1alpha1.ClusterAdoptionSpec, providerID string, rcpUID types.UID) *unstructured.Unstructured {
	obj := r.newObj(gvkMachine, spec.ClusterName+"-master", spec)
	obj.SetLabels(map[string]string{
		clusterNameLabel:  spec.ClusterName,
		controlPlaneLabel: "",
	})
	obj.SetOwnerReferences([]metav1.OwnerReference{{
		APIVersion:         gvkRKE2ControlPlane.GroupVersion().String(),
		Kind:               gvkRKE2ControlPlane.Kind,
		Name:               spec.ClusterName + "-control-plane",
		UID:                rcpUID,
		Controller:         ptrBool(true),
		BlockOwnerDeletion: ptrBool(true),
	}})
	obj.Object["spec"] = map[string]any{
		"clusterName": spec.ClusterName,
		"providerID":  providerID,
		"version":     spec.RKE2Version,
		"bootstrap": map[string]any{
			// dataSecretName WITHOUT configRef → CAPI marks bootstrap provided and never
			// re-bootstraps the already-running node (verified against the core Machine controller).
			"dataSecretName": spec.ClusterName + "-adopted-bootstrap",
		},
		"infrastructureRef": map[string]any{
			"apiVersion": gvkLXCMachine.GroupVersion().String(),
			"kind":       gvkLXCMachine.Kind,
			"name":       spec.ClusterName + "-master",
		},
	}
	return obj
}

func (r *ClusterAdoptionReconciler) bootstrapSecretObj(spec adoptionv1alpha1.ClusterAdoptionSpec) *unstructured.Unstructured {
	obj := &unstructured.Unstructured{}
	obj.SetGroupVersionKind(schema.GroupVersionKind{Version: "v1", Kind: "Secret"})
	obj.SetName(spec.ClusterName + "-adopted-bootstrap")
	obj.SetNamespace(spec.Namespace)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	obj.Object["type"] = clusterSecretType
	// Content is never consumed for an already-running node; a sentinel is enough.
	obj.Object["stringData"] = map[string]any{"value": "", "format": "cloud-config"}
	return obj
}

func (r *ClusterAdoptionReconciler) rke2ControlPlaneObj(spec adoptionv1alpha1.ClusterAdoptionSpec, paused bool) *unstructured.Unstructured {
	obj := r.newObj(gvkRKE2ControlPlane, spec.ClusterName+"-control-plane", spec)
	if paused {
		// Inert from birth — no wait for CAPI to propagate the Cluster's paused (closes the race
		// where the RCP would initialize a random-named control plane before the owned Machine).
		obj.SetAnnotations(map[string]string{pausedAnnotation: "true"})
	}
	obj.Object["spec"] = map[string]any{
		"replicas":     int64(spec.ControlPlaneReplicas),
		"version":      spec.RKE2Version,
		"agentConfig":  map[string]any{"airGapped": true},
		"serverConfig": map[string]any{},
		// kube-vip fronts the control-plane endpoint: the replicas register on the VIP.
		"registrationMethod":  "address",
		"registrationAddress": spec.ControlPlaneEndpoint.Host,
		"preRKE2Commands":     []any{kubeVIPBootstrapCommand(spec.ControlPlaneEndpoint.Host, spec.KubeVIPVersion)},
		"files":               []any{kubeVIPRBACFile()},
		"machineTemplate": map[string]any{
			"spec": map[string]any{
				"infrastructureRef": map[string]any{
					"apiGroup": gvkLXCMachineTemplate.Group,
					"kind":     gvkLXCMachineTemplate.Kind,
					"name":     spec.ClusterName + "-control-plane",
				},
			},
		},
		"rolloutStrategy": map[string]any{
			"type":          "RollingUpdate",
			"rollingUpdate": map[string]any{"maxSurge": int64(1)},
		},
	}
	return obj
}

func toAny(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

func ptrBool(b bool) *bool { return &b }

// lxcMachineSpec is the privileged-container LXCMachine/template body, pinned to our nix-built
// node-base by fingerprint — mirrors rke2lab's InstanceGrow / ClusterApiCrRenderer.
func lxcMachineSpec(fingerprint string) map[string]any {
	return map[string]any{
		"instanceType": "container",
		"profiles":     []any{"rke2lab"},
		"image":        map[string]any{"fingerprint": fingerprint},
		"config": map[string]any{
			"raw.lxc":                                 "lxc.mount.auto = proc:rw sys:rw cgroup:rw\nlxc.apparmor.profile = unconfined\nlxc.cap.drop =",
			"security.privileged":                     "true",
			"security.nesting":                        "true",
			"security.syscalls.intercept.bpf":         "true",
			"security.syscalls.intercept.bpf.devices": "true",
			// The CAPN default kernel-module set MINUS the legacy iptables trio the nftables-only
			// kernel-6.18 substrate dropped (ip_tables/ip6_tables/iptable_raw FATAL modprobe).
			"linux.kernel_modules": "ip_vs,ip_vs_rr,ip_vs_wrr,ip_vs_sh,netlink_diag,nf_nat,overlay,br_netfilter,xt_socket",
		},
	}
}

func kubeVIPBootstrapCommand(vip, version string) string {
	image := "ghcr.io/kube-vip/kube-vip:" + version
	return fmt.Sprintf(
		"mkdir -p /var/lib/rancher/rke2/server/manifests/ && ctr images pull %s && ctr run --rm --net-host %s vip /kube-vip manifest daemonset --arp --interface $(ip -4 -j route list default | jq -r .[0].dev) --address %s --controlplane --leaderElection --taint --services --inCluster | tee /var/lib/rancher/rke2/server/manifests/kube-vip.yaml",
		image, image, vip)
}

func kubeVIPRBACFile() map[string]any {
	content := `apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-vip
  namespace: kube-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  annotations:
    rbac.authorization.kubernetes.io/autoupdate: "true"
  name: system:kube-vip-role
rules:
  - apiGroups: [""]
    resources: ["services", "services/status", "nodes", "endpoints"]
    verbs: ["list","get","watch", "update"]
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["list", "get", "watch", "update", "create"]
---
kind: ClusterRoleBinding
apiVersion: rbac.authorization.k8s.io/v1
metadata:
  name: system:kube-vip-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:kube-vip-role
subjects:
- kind: ServiceAccount
  name: kube-vip
  namespace: kube-system
`
	return map[string]any{
		"path":    "/var/lib/rancher/rke2/server/manifests/kube-vip-rbac.yaml",
		"owner":   "root:root",
		"content": content,
	}
}
