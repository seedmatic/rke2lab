package controller

import (
	"context"
	"fmt"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// PoolAdoptionReconciler adopts one node pool of a running RKE2-on-Incus cluster into Cluster API.
// It owns the pool CR-set — the RKE2ControlPlane (control-plane pool) + LXCMachineTemplate + the
// per-pet Machine/LXCMachine — and runs the per-pool adopt-first state machine: every reconcile it aligns
// the CR-set to the running reality (match each pet by providerID, skip bootstrap) before it ever
// provisions; only a genuinely-absent pet is a day-0 provision. Reachability is aggregated at the
// ClusterAdoption grain, not here. Symmetric with ClusterAdoptionReconciler.
type PoolAdoptionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=machines,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=controlplane.cluster.x-k8s.io,resources=rke2controlplanes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcmachines;lxcmachinetemplates,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create

// Reconcile drives one PoolAdoption. Every object is created-if-absent, so a re-reconcile (and a
// cold-start, which wipes etcd and re-creates the CR-set) safely re-adopts the SURVIVING instances
// by deterministic name + providerID. The wrapper ALWAYS persists status afterwards.
func (r *PoolAdoptionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var adoption adoptionv1alpha1.PoolAdoption
	if err := r.Get(ctx, req.NamespacedName, &adoption); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	result, reconcileErr := r.reconcileSteps(ctx, &adoption)

	adoption.Status.ObservedGeneration = adoption.Generation
	adoption.Status.LastReconcileTime = metav1.Now()
	adoption.Status.Phase = r.derivePhase(&adoption, reconcileErr)
	if statusErr := r.Status().Update(ctx, &adoption); statusErr != nil {
		log.FromContext(ctx).Error(statusErr, "failed to update PoolAdoption status")
		if reconcileErr == nil {
			reconcileErr = statusErr
		}
	}
	return result, reconcileErr
}

func (r *PoolAdoptionReconciler) reconcileSteps(
	ctx context.Context, a *adoptionv1alpha1.PoolAdoption,
) (ctrl.Result, error) {
	spec := a.Spec

	// Worker pools are graved in the design but out of the current coding scope (control-node only).
	// Refuse honestly rather than half-build a MachineDeployment/RKE2ConfigTemplate path.
	if spec.Role != adoptionv1alpha1.PoolRoleControlPlane {
		r.mark(a, adoptionv1alpha1.PoolConditionCRSetCreated, false, "WorkerPoolNotImplemented",
			"worker pool "+spec.Pool+" is out of the current coding scope (control-node only)")
		return ctrl.Result{RequeueAfter: time.Hour}, nil
	}

	// Guard: the BYO-CA Secrets are delivered by seed-master (branch, sops). CAPRKE2 adopts the LIVE
	// CA from <cluster>-{ca,cca,etcd,peer-etcd}; without them it would generate a fresh CA and the
	// adopted apiserver would reject the minted admin cert. Wait until present.
	missing, err := r.materialMissing(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if missing != "" {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "MaterialMissing",
			"waiting for seed-master Secret "+missing)
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, true, "MaterialReady", "BYO-CA Secrets present")

	a.Status.TotalPets = int32(len(spec.Nodes))

	// 1. The pool's control-plane skeleton: LXCMachineTemplate + RKE2ControlPlane, created paused (the
	//    RCP carries the paused annotation DIRECTLY — inert from birth, closing the init race before
	//    the owned Machines exist). The Cluster + LXCCluster are the ClusterAdoption's, not ours.
	for _, obj := range []*unstructured.Unstructured{
		r.lxcMachineTemplateObj(spec),
		r.rke2ControlPlaneObj(spec, true),
	} {
		// Own the pool CR-set for cascade GC: deleting the PoolAdoption (or, transitively, its parent
		// PoolIntention) tears down RCP + template; the RCP in turn owns the per-pet Machines.
		if err := controllerutil.SetControllerReference(a, obj, r.Scheme); err != nil {
			r.mark(a, adoptionv1alpha1.PoolConditionCRSetCreated, false, "Error",
				obj.GetKind()+" "+obj.GetName()+" ownerRef: "+err.Error())
			return ctrl.Result{}, err
		}
		if err := ensure(ctx, r.Client, obj); err != nil {
			r.mark(a, adoptionv1alpha1.PoolConditionCRSetCreated, false, "Error",
				obj.GetKind()+" "+obj.GetName()+": "+err.Error())
			return ctrl.Result{}, err
		}
	}
	r.mark(a, adoptionv1alpha1.PoolConditionCRSetCreated, true, "Created",
		"LXCMachineTemplate/RKE2ControlPlane ensured (paused)")

	// 2. Read the RKE2ControlPlane UID — the piece GitOps cannot pre-set. Each owned Machine's
	//    ownerRef must carry it, else CAPRKE2 refuses ("mixed management mode") and never adopts.
	rcpUID, err := r.rcpUID(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionControlPlaneObserved, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if rcpUID == "" {
		r.mark(a, adoptionv1alpha1.PoolConditionControlPlaneObserved, false, "AwaitingControlPlane",
			"RKE2ControlPlane UID not observable yet")
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	}
	a.Status.ControlPlaneUID = string(rcpUID)
	r.mark(a, adoptionv1alpha1.PoolConditionControlPlaneObserved, true, "Observed",
		"RKE2ControlPlane UID "+string(rcpUID))

	// 3. ADOPT, per pet: pre-create the owned Machine + concrete LXCMachine(providerID = lxc:///<pet>)
	//    + bootstrap sentinel, so CAPRKE2 counts it as one of ITS replicas (adoption, no re-bootstrap)
	//    and CAPN ADOPTS the running instance. We do NOT probe Incus ourselves — CAPN owns the Incus
	//    connection + cross-host reach; it reports present/absent on the LXCMachine status. Idempotent.
	//
	//    NOTE — day-0 provisioning is the follow-up (C4): an ABSENT pet leaves its owned LXCMachine in
	//    CAPN's "instance not found" state. Turning that into a provision (re-create at the same name
	//    with providerID EMPTY + a real bootstrap) is derived from CAPN's status.
	present, absent, pending := 0, 0, 0
	for _, pet := range spec.Nodes {
		for _, obj := range []*unstructured.Unstructured{
			r.bootstrapSecretObj(spec, pet.Name),
			r.lxcMachineObj(spec, pet.Name),
			r.machineObj(spec, pet.Name, rcpUID),
		} {
			if err := ensure(ctx, r.Client, obj); err != nil {
				r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error",
					obj.GetKind()+" "+obj.GetName()+": "+err.Error())
				return ctrl.Result{}, err
			}
		}
		isPresent, decided, perr := r.lxcMachinePresence(ctx, spec, pet.Name)
		if perr != nil {
			r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error", "presence "+pet.Name+": "+perr.Error())
			return ctrl.Result{}, perr
		}
		switch {
		case !decided:
			pending++
		case isPresent:
			present++
		default:
			absent++
		}
	}
	a.Status.Present, a.Status.Absent, a.Status.Pending = int32(present), int32(absent), int32(pending)

	// The safety invariant: any present pet means the cluster EXISTS — we adopt it and NEVER
	// greenfield a rival. PetsPresent is True ONLY when EVERY pet is present.
	allPresent := len(spec.Nodes) > 0 && present == len(spec.Nodes)
	switch {
	case allPresent:
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, true, "Observed",
			fmt.Sprintf("%d/%d present", present, len(spec.Nodes)))
	case absent > 0:
		// day-0 / disaster: a pet is genuinely absent (CAPN InstanceDeleted). NodesAbsent routes
		// derivePhase to Provisioning — present pets stay untouched (never re-provisioned).
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodesAbsent",
			fmt.Sprintf("%d/%d present, %d absent — provisioning, %d pending", present, len(spec.Nodes), absent, pending))
	default:
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "AwaitingInstances",
			fmt.Sprintf("%d/%d present, %d pending", present, len(spec.Nodes), pending))
	}

	// 4. Unpause the pool's RKE2ControlPlane (the owned Machines exist, so on unpause CAPRKE2 counts
	//    them — no re-init of an adopted replica). The Cluster's own paused flag is the
	//    ClusterAdoption reconciler's to clear (cluster grain), gated on existence.
	if err := r.unpauseRCP(ctx, spec); err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionUnpaused, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	r.mark(a, adoptionv1alpha1.PoolConditionUnpaused, true, "Unpaused", "RKE2ControlPlane un-paused")

	log.FromContext(ctx).Info("pool reconciled", "cluster", spec.ClusterName, "pool", spec.Pool,
		"present", present, "pets", len(spec.Nodes))
	if !allPresent {
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// derivePhase rolls the per-step conditions (+ the reconcile error) into the per-pool state machine phase.
// Adopting is the hub every reconcile enters; it rests at Adopted / Provisioning once the state machine
// resolves. present-sick (Degraded) is a CLUSTER-grain verdict (reachability) — the pool cannot
// observe it, so this state machine never emits Degraded; a step error surfaces as Failed.
func (r *PoolAdoptionReconciler) derivePhase(
	a *adoptionv1alpha1.PoolAdoption, reconcileErr error,
) adoptionv1alpha1.PoolAdoptionPhase {
	if reconcileErr != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "ReconcileError", reconcileErr.Error())
		return adoptionv1alpha1.PoolPhaseFailed
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.PoolConditionMaterialReady) {
		r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "Pending", "waiting for material")
		return adoptionv1alpha1.PoolPhasePending
	}
	for _, step := range []string{
		adoptionv1alpha1.PoolConditionCRSetCreated,
		adoptionv1alpha1.PoolConditionControlPlaneObserved,
	} {
		if !conditionTrue(a.Status.Conditions, step) {
			r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "Adopting", "aligning the CR-set")
			return adoptionv1alpha1.PoolPhaseAdopting
		}
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.PoolConditionPetsPresent) {
		if conditionReason(a.Status.Conditions, adoptionv1alpha1.PoolConditionPetsPresent) == "NodesAbsent" {
			r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "Provisioning",
				"one or more pets absent — CAPN provisioning the missing node(s)")
			return adoptionv1alpha1.PoolPhaseProvisioning
		}
		r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "Adopting", "awaiting instance presence")
		return adoptionv1alpha1.PoolPhaseAdopting
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.PoolConditionUnpaused) {
		r.mark(a, adoptionv1alpha1.PoolConditionReady, false, "Adopting", "un-pausing the control plane")
		return adoptionv1alpha1.PoolPhaseAdopting
	}
	r.mark(a, adoptionv1alpha1.PoolConditionReady, true, "Adopted", "all pets present, control plane un-paused")
	return adoptionv1alpha1.PoolPhaseAdopted
}

func (r *PoolAdoptionReconciler) mark(
	a *adoptionv1alpha1.PoolAdoption, condType string, ok bool, reason, message string,
) {
	status := metav1.ConditionFalse
	if ok {
		status = metav1.ConditionTrue
	}
	setCondition(&a.Status.Conditions, metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: metav1.Now(),
		ObservedGeneration: a.Generation,
	})
}

// controlPlaneName is the deterministic RKE2ControlPlane + control-plane LXCMachineTemplate name the
// Cluster.controlPlaneRef points at.
func controlPlaneName(clusterName string) string { return clusterName + "-control-plane" }

// materialMissing returns the name of the first required BYO-CA Secret that is absent, or "".
func (r *PoolAdoptionReconciler) materialMissing(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) (string, error) {
	names := []string{
		spec.ClusterName + "-ca",
		spec.ClusterName + "-cca",
		spec.ClusterName + "-etcd",
		spec.ClusterName + "-peer-etcd",
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

func (r *PoolAdoptionReconciler) rcpUID(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) (types.UID, error) {
	rcp := &unstructured.Unstructured{}
	rcp.SetGroupVersionKind(gvkRKE2ControlPlane)
	err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: controlPlaneName(spec.ClusterName)}, rcp)
	if apierrors.IsNotFound(err) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return rcp.GetUID(), nil
}

// unpauseRCP clears the RKE2ControlPlane paused annotation, letting it adopt the owned Machines.
func (r *PoolAdoptionReconciler) unpauseRCP(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) error {
	rcp := &unstructured.Unstructured{}
	rcp.SetGroupVersionKind(gvkRKE2ControlPlane)
	if err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: controlPlaneName(spec.ClusterName)}, rcp); err != nil {
		return err
	}
	annotations := rcp.GetAnnotations()
	if _, present := annotations[pausedAnnotation]; present {
		delete(annotations, pausedAnnotation)
		rcp.SetAnnotations(annotations)
		return r.Update(ctx, rcp)
	}
	return nil
}

// lxcMachinePresence reads CAPN's verdict on the pet's instance from the LXCMachine we created.
// decided is false while CAPN has not yet set the InstanceProvisioned condition (caller requeues).
func (r *PoolAdoptionReconciler) lxcMachinePresence(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string,
) (present, decided bool, err error) {
	lm := &unstructured.Unstructured{}
	lm.SetGroupVersionKind(gvkLXCMachine)
	if getErr := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: nodeName}, lm); getErr != nil {
		if apierrors.IsNotFound(getErr) {
			return false, false, nil
		}
		return false, false, getErr
	}
	conditions, _, _ := unstructured.NestedSlice(lm.Object, "status", "conditions")
	for _, raw := range conditions {
		cond, ok := raw.(map[string]any)
		if !ok || cond["type"] != capnInstanceProvisionedCondition {
			continue
		}
		status, _ := cond["status"].(string)
		reason, _ := cond["reason"].(string)
		switch {
		case status == "True":
			return true, true, nil
		case status == "False" && reason == capnInstanceDeletedReason:
			return false, true, nil
		}
	}
	return false, false, nil
}

func (r *PoolAdoptionReconciler) lxcMachineTemplateObj(spec adoptionv1alpha1.PoolAdoptionSpec) *unstructured.Unstructured {
	obj := newObj(gvkLXCMachineTemplate, controlPlaneName(spec.ClusterName), spec.Namespace)
	obj.Object["spec"] = map[string]any{
		"template": map[string]any{"spec": lxcMachineSpec(spec.Image.Fingerprint)},
	}
	return obj
}

func (r *PoolAdoptionReconciler) lxcMachineObj(spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string) *unstructured.Unstructured {
	obj := newObj(gvkLXCMachine, nodeName, spec.Namespace)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	body := lxcMachineSpec(spec.Image.Fingerprint)
	// providerID = lxc:///<name> → CAPN adopts the existing instance (this pet is present).
	body["providerID"] = "lxc:///" + nodeName
	obj.Object["spec"] = body
	return obj
}

func (r *PoolAdoptionReconciler) machineObj(spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string, rcpUID types.UID) *unstructured.Unstructured {
	obj := newObj(gvkMachine, nodeName, spec.Namespace)
	obj.SetLabels(map[string]string{
		clusterNameLabel:  spec.ClusterName,
		controlPlaneLabel: "",
	})
	obj.SetOwnerReferences([]metav1.OwnerReference{{
		APIVersion:         gvkRKE2ControlPlane.GroupVersion().String(),
		Kind:               gvkRKE2ControlPlane.Kind,
		Name:               controlPlaneName(spec.ClusterName),
		UID:                rcpUID,
		Controller:         ptrBool(true),
		BlockOwnerDeletion: ptrBool(true),
	}})
	obj.Object["spec"] = map[string]any{
		"clusterName": spec.ClusterName,
		"providerID":  "lxc:///" + nodeName,
		"version":     spec.RKE2Version,
		"bootstrap": map[string]any{
			// dataSecretName WITHOUT configRef → CAPI marks bootstrap provided and never
			// re-bootstraps the already-running node.
			"dataSecretName": nodeName + "-adopted-bootstrap",
		},
		"infrastructureRef": map[string]any{
			"apiGroup": gvkLXCMachine.Group,
			"kind":     gvkLXCMachine.Kind,
			"name":     nodeName,
		},
	}
	return obj
}

func (r *PoolAdoptionReconciler) bootstrapSecretObj(spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string) *unstructured.Unstructured {
	obj := newObj(gvkSecret, nodeName+"-adopted-bootstrap", spec.Namespace)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	obj.Object["type"] = clusterSecretType
	// Content is never consumed for an already-running node; a sentinel is enough.
	obj.Object["stringData"] = map[string]any{"value": "", "format": "cloud-config"}
	return obj
}

func (r *PoolAdoptionReconciler) rke2ControlPlaneObj(spec adoptionv1alpha1.PoolAdoptionSpec, paused bool) *unstructured.Unstructured {
	obj := newObj(gvkRKE2ControlPlane, controlPlaneName(spec.ClusterName), spec.Namespace)
	if paused {
		// Inert from birth — no wait for CAPI to propagate the Cluster's paused (closes the race
		// where the RCP would initialize a random-named control plane before the owned Machine).
		obj.SetAnnotations(map[string]string{pausedAnnotation: "true"})
	}
	obj.Object["spec"] = map[string]any{
		"replicas":     int64(len(spec.Nodes)),
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
					"name":     controlPlaneName(spec.ClusterName),
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

// SetupWithManager wires the reconciler to PoolAdoption events + the owned pool CR-set's.
func (r *PoolAdoptionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.PoolAdoption{}).
		Named("pooladoption").
		Complete(r)
}
