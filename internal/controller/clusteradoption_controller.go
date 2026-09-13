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
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

const (
	pausedAnnotation  = "cluster.x-k8s.io/paused"
	clusterNameLabel  = "cluster.x-k8s.io/cluster-name"
	controlPlaneLabel = "cluster.x-k8s.io/control-plane"
	clusterSecretType = "cluster.x-k8s.io/secret"

	// CAPN's LXCMachine instance-presence signal (cluster-api-provider-incus
	// api/v1alpha2/condition_consts.go) — read from the LXCMachine WE create, instead of probing
	// Incus ourselves. InstanceProvisioned=True (reason InstanceProvisioned) = instance present
	// (adopted); =False reason InstanceDeleted = absent ("does not exist anymore").
	capnInstanceProvisionedCondition = "InstanceProvisioned"
	capnInstanceDeletedReason        = "InstanceDeleted"

	// CAPI's Cluster accessibility signal — "the apiserver is reachable" (the blocker that flipped
	// True at the mgmt self-adoption WIN). The operator-view rollup: presence (InstanceProvisioned)
	// answers "does the cluster exist?", this answers "is its access available?".
	capiRemoteConnectionProbeCondition = "RemoteConnectionProbe"
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

// selfAdoptionFinalizer guards the ClusterAdoption of the cluster this controller RUNS ON. Deleting
// it would cascade (ownerRef) to the CAPI Cluster and suicide the management plane, so we keep this
// finalizer on a self-adoption and refuse to remove it — the deletion blocks by design.
const selfAdoptionFinalizer = "cluster.seedmatic.io/self-adoption-guard"

// ClusterAdoptionReconciler adopts a running RKE2-on-Incus control plane into Cluster API.
type ClusterAdoptionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// SelfCluster is the name of the cluster this controller runs IN (SELF_CLUSTER_NAME env, set by
	// seed-master from the blueprint). A ClusterAdoption whose clusterName equals it is a
	// self-adoption — its deletion is refused (would tear down the management plane).
	SelfCluster string
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusteradoptions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusteradoptions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=clusters;machines,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=controlplane.cluster.x-k8s.io,resources=rke2controlplanes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcclusters;lxcmachines;lxcmachinetemplates,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create

// Reconcile drives one ClusterAdoption toward Adopted. Every object is created-if-absent, so a
// re-reconcile (and a management cold-start, which wipes etcd and re-creates the CR-set) safely
// re-adopts the SURVIVING instance by deterministic name + providerID. The step logic lives in
// reconcileSteps; this wrapper ALWAYS persists the status afterwards — so a failure (or a wait) is
// visible on the CR itself (`kubectl describe clusteradoption`), not only in the pod logs.
func (r *ClusterAdoptionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var adoption adoptionv1alpha1.ClusterAdoption
	if err := r.Get(ctx, req.NamespacedName, &adoption); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// Self-adoption guard: the ClusterAdoption of the cluster this controller RUNS ON must never be
	// torn down (its ownerRef'd Cluster would cascade-delete = suicide the management plane). We
	// hold a finalizer on it and refuse to remove it, so an accidental delete/prune blocks by design.
	self := r.SelfCluster != "" && adoption.Spec.ClusterName == r.SelfCluster
	if !adoption.DeletionTimestamp.IsZero() {
		if self && controllerutil.ContainsFinalizer(&adoption, selfAdoptionFinalizer) {
			log.FromContext(ctx).Info(
				"refusing to delete the self-adoption — would suicide the management cluster; keeping finalizer",
				"cluster", adoption.Spec.ClusterName)
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, nil // non-self: nothing held → GC + the ownerRef cascade proceed
	}
	if self && controllerutil.AddFinalizer(&adoption, selfAdoptionFinalizer) {
		if err := r.Update(ctx, &adoption); err != nil {
			return ctrl.Result{}, err
		}
	}

	result, reconcileErr := r.reconcileSteps(ctx, &adoption)

	adoption.Status.ObservedGeneration = adoption.Generation
	adoption.Status.LastReconcileTime = metav1.Now()
	adoption.Status.Phase = r.derivePhase(&adoption, reconcileErr)
	if statusErr := r.Status().Update(ctx, &adoption); statusErr != nil {
		log.FromContext(ctx).Error(statusErr, "failed to update ClusterAdoption status")
		if reconcileErr == nil {
			reconcileErr = statusErr
		}
	}
	return result, reconcileErr
}

// reconcileSteps runs the adoption flow, marking a per-step condition on the ClusterAdoption as it
// goes (True on success, False + the error/reason on the step that stops). It mutates only status
// conditions on `a`; the wrapper persists them.
func (r *ClusterAdoptionReconciler) reconcileSteps(
	ctx context.Context, a *adoptionv1alpha1.ClusterAdoption,
) (ctrl.Result, error) {
	spec := a.Spec

	// Guard: the BYO-CA + identity Secrets are delivered by seed-master (branch, sops). CAPRKE2
	// adopts the LIVE CA from <cluster>-{ca,cca,etcd,peer-etcd}; without them it would generate a
	// fresh CA and the adopted apiserver would reject the minted admin cert. Wait until present.
	missing, err := r.materialMissing(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.ConditionMaterialReady, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if missing != "" {
		r.mark(a, adoptionv1alpha1.ConditionMaterialReady, false, "MaterialMissing",
			"waiting for seed-master Secret "+missing)
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	r.mark(a, adoptionv1alpha1.ConditionMaterialReady, true, "MaterialReady",
		"BYO-CA + identity Secrets present")

	pets := petsOf(spec)

	// 1. The infra + control-plane skeleton. The Cluster is OWNED by this ClusterAdoption
	//    (SetControllerReference) — adopting a cluster makes us its owner, so deleting the adoption
	//    (or, transitively, its parent ClusterProvision) cascades to the Cluster and CAPI tears the
	//    fleet down. Created paused; the RCP carries the paused annotation DIRECTLY (inert from
	//    birth, closing the init race before the owned Machines exist).
	cluster := r.clusterObj(spec, true)
	if err := controllerutil.SetControllerReference(a, cluster, r.Scheme); err != nil {
		r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error", "Cluster ownerRef: "+err.Error())
		return ctrl.Result{}, err
	}
	for _, obj := range []*unstructured.Unstructured{
		cluster,
		r.lxcClusterObj(spec),
		r.lxcMachineTemplateObj(spec),
		r.rke2ControlPlaneObj(spec, true),
	} {
		if err := r.ensure(ctx, obj); err != nil {
			r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error",
				obj.GetKind()+" "+obj.GetName()+": "+err.Error())
			return ctrl.Result{}, err
		}
	}
	r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, true, "Created",
		"Cluster(owned)/LXCCluster/LXCMachineTemplate/RKE2ControlPlane ensured (paused)")

	// 2. Read the RKE2ControlPlane UID — the piece GitOps cannot pre-set. Each owned Machine's
	//    ownerRef must carry it, else CAPRKE2 refuses ("mixed management mode") and never adopts.
	rcpUID, err := r.rcpUID(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.ConditionControlPlaneObserved, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if rcpUID == "" {
		r.mark(a, adoptionv1alpha1.ConditionControlPlaneObserved, false, "AwaitingControlPlane",
			"RKE2ControlPlane UID not observable yet")
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	}
	a.Status.ControlPlaneUID = string(rcpUID)
	r.mark(a, adoptionv1alpha1.ConditionControlPlaneObserved, true, "Observed",
		"RKE2ControlPlane UID "+string(rcpUID))

	// 3. ADOPT, per control-plane PET: pre-create the owned Machine + concrete
	//    LXCMachine(providerID = lxc:///<pet>) + bootstrap sentinel, so CAPRKE2 counts it as one of
	//    ITS replicas (adoption, no re-bootstrap) and CAPN ADOPTS the running instance (providerID
	//    set + instance present). We do NOT probe Incus ourselves — CAPN owns the Incus connection
	//    (from the identity Secret) + the cross-host reach; it reports present/absent on the
	//    LXCMachine status. Idempotent (ensure = create-if-absent), so a cold-start re-affirms the
	//    owned pair and CAPN re-adopts the survivor.
	//
	//    NOTE — day-0 provisioning is the follow-up: an ABSENT pet leaves its owned LXCMachine in
	//    CAPN's "instance not found" state (providerID set + no instance). Turning that into a
	//    provision (re-create the LXCMachine at the SAME pet name with providerID EMPTY -> CAPN
	//    launches it deterministically) is derived from CAPN's own status — see the design's
	//    adopt-first funnel. Handled next; today this loop is adopt-all (the mgmt/cold-start case).
	//    (Workers are also a follow-up — petsOf keeps them out of this control-plane loop.)
	cpPets, present, absent, pending := 0, 0, 0, 0
	for _, pet := range pets {
		if pet.Role != adoptionv1alpha1.NodeRoleControlPlane {
			continue
		}
		cpPets++
		for _, obj := range []*unstructured.Unstructured{
			r.bootstrapSecretObj(spec, pet.Name),
			r.lxcMachineObj(spec, pet.Name),
			r.machineObj(spec, pet.Name, rcpUID),
		} {
			if err := r.ensure(ctx, obj); err != nil {
				r.mark(a, adoptionv1alpha1.ConditionMachineCreated, false, "Error",
					obj.GetKind()+" "+obj.GetName()+": "+err.Error())
				return ctrl.Result{}, err
			}
		}
		// STATUS-DRIVEN presence: read CAPN's verdict on the LXCMachine WE created — no direct Incus
		// probe (CAPN owns the connection + the cross-host reach). InstanceProvisioned=True -> the
		// instance is present (adopted); =False/InstanceDeleted -> absent (day-0 provision follow-up:
		// re-create at the pet name with providerID EMPTY + a real bootstrap); unset -> CAPN has not
		// decided yet (requeue).
		isPresent, decided, perr := r.lxcMachinePresence(ctx, spec, pet.Name)
		if perr != nil {
			r.mark(a, adoptionv1alpha1.ConditionMachineCreated, false, "Error", "presence "+pet.Name+": "+perr.Error())
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
	// The safety invariant: any present control-plane pet means the cluster EXISTS — we adopt it and
	// NEVER greenfield a rival. Only a cluster with ZERO present pets is a true day-0 bootstrap.
	// MachineCreated is True ONLY when EVERY control-plane pet is present: the CR-set + owned Machines
	// existing is NOT adoption — CAPN must confirm each instance. Absent/pending pets keep this False
	// (reason AwaitingInstances), so derivePhase stays Adopting rather than lying "Adopted".
	allPresent := cpPets > 0 && present == cpPets
	if allPresent {
		r.mark(a, adoptionv1alpha1.ConditionMachineCreated, true, "Observed",
			fmt.Sprintf("%d/%d present", present, cpPets))
	} else {
		r.mark(a, adoptionv1alpha1.ConditionMachineCreated, false, "AwaitingInstances",
			fmt.Sprintf("%d/%d present, %d absent (provision follow-up), %d pending", present, cpPets, absent, pending))
	}

	// 4. Unpause: the owned Machines exist, so on unpause CAPRKE2 counts them (no re-init of an
	//    adopted replica); CAPN adopts the present instances. Clear the RCP paused annotation + the
	//    Cluster's paused.
	if err := r.unpause(ctx, spec); err != nil {
		r.mark(a, adoptionv1alpha1.ConditionUnpaused, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	r.mark(a, adoptionv1alpha1.ConditionUnpaused, true, "Unpaused",
		"RKE2ControlPlane + Cluster un-paused")

	// 5. Accessibility rollup (operator view): CAPI's RemoteConnectionProbe on the Cluster = the
	//    apiserver is reachable. Surface it (distinct from instance presence) so
	//    `kubectl get clusteradoption` — and, mirrored, `clusterprovision` — tell whether cluster
	//    ACCESS is available. Requeue until the fleet is fully present AND reachable, so the phase
	//    converges on a live cluster (and a still-absent pet keeps being re-observed for the day-0
	//    provision follow-up).
	accessible, _ := r.clusterAccessible(ctx, spec)
	access := "unreachable"
	if accessible {
		access = "reachable"
	}
	if accessible {
		r.mark(a, adoptionv1alpha1.ConditionAccessible, true, "Reachable",
			"apiserver reachable (RemoteConnectionProbe)")
	} else {
		r.mark(a, adoptionv1alpha1.ConditionAccessible, false, "Unreachable",
			"apiserver not reachable yet (RemoteConnectionProbe)")
	}
	a.Status.AdoptedInstance = fmt.Sprintf("%d/%d present (%d absent, %d pending) · apiserver %s",
		present, cpPets, absent, pending, access)

	log.FromContext(ctx).Info("adoption reconciled", "cluster", spec.ClusterName,
		"present", present, "pets", cpPets, "accessible", accessible)
	if !allPresent || !accessible {
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// mark sets one step condition on the ClusterAdoption (True on success, False otherwise).
func (r *ClusterAdoptionReconciler) mark(
	a *adoptionv1alpha1.ClusterAdoption, condType string, ok bool, reason, message string,
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

// derivePhase rolls the per-step conditions (+ the reconcile error) into the coarse phase and the
// summary Ready condition.
func (r *ClusterAdoptionReconciler) derivePhase(
	a *adoptionv1alpha1.ClusterAdoption, reconcileErr error,
) adoptionv1alpha1.ClusterAdoptionPhase {
	if reconcileErr != nil {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "ReconcileError", reconcileErr.Error())
		return adoptionv1alpha1.PhaseFailed
	}
	steps := []string{
		adoptionv1alpha1.ConditionMaterialReady,
		adoptionv1alpha1.ConditionCRSetCreated,
		adoptionv1alpha1.ConditionControlPlaneObserved,
		adoptionv1alpha1.ConditionMachineCreated,
		adoptionv1alpha1.ConditionUnpaused,
		adoptionv1alpha1.ConditionAccessible,
	}
	for _, step := range steps {
		if !conditionTrue(a.Status.Conditions, step) {
			if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.ConditionMaterialReady) {
				r.mark(a, adoptionv1alpha1.ConditionReady, false, "Pending", "waiting for material")
				return adoptionv1alpha1.PhasePending
			}
			r.mark(a, adoptionv1alpha1.ConditionReady, false, "Adopting", "adoption in progress")
			return adoptionv1alpha1.PhaseAdopting
		}
	}
	r.mark(a, adoptionv1alpha1.ConditionReady, true, "Adopted",
		"all control-plane pets present and apiserver reachable")
	return adoptionv1alpha1.PhaseAdopted
}

func conditionTrue(conditions []metav1.Condition, condType string) bool {
	for i := range conditions {
		if conditions[i].Type == condType {
			return conditions[i].Status == metav1.ConditionTrue
		}
	}
	return false
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

func (r *ClusterAdoptionReconciler) lxcMachineObj(spec adoptionv1alpha1.ClusterAdoptionSpec, nodeName string) *unstructured.Unstructured {
	obj := r.newObj(gvkLXCMachine, nodeName, spec)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	body := lxcMachineSpec(spec.Image.Fingerprint)
	// providerID = lxc:///<name> → CAPN adopts the existing instance (this pet is present).
	body["providerID"] = "lxc:///" + nodeName
	obj.Object["spec"] = body
	return obj
}

func (r *ClusterAdoptionReconciler) machineObj(spec adoptionv1alpha1.ClusterAdoptionSpec, nodeName string, rcpUID types.UID) *unstructured.Unstructured {
	obj := r.newObj(gvkMachine, nodeName, spec)
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
		"providerID":  "lxc:///" + nodeName,
		"version":     spec.RKE2Version,
		"bootstrap": map[string]any{
			// dataSecretName WITHOUT configRef → CAPI marks bootstrap provided and never
			// re-bootstraps the already-running node (verified against the core Machine controller).
			"dataSecretName": nodeName + "-adopted-bootstrap",
		},
		// CAPI v1beta2 contract ref: {apiGroup, kind, name} — NOT apiVersion (the v1beta1 shape,
		// which the Machine webhook rejects as "spec.infrastructureRef.apiGroup: Required value").
		"infrastructureRef": map[string]any{
			"apiGroup": gvkLXCMachine.Group,
			"kind":     gvkLXCMachine.Kind,
			"name":     nodeName,
		},
	}
	return obj
}

func (r *ClusterAdoptionReconciler) bootstrapSecretObj(spec adoptionv1alpha1.ClusterAdoptionSpec, nodeName string) *unstructured.Unstructured {
	obj := &unstructured.Unstructured{}
	obj.SetGroupVersionKind(schema.GroupVersionKind{Version: "v1", Kind: "Secret"})
	obj.SetName(nodeName + "-adopted-bootstrap")
	obj.SetNamespace(spec.Namespace)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	obj.Object["type"] = clusterSecretType
	// Content is never consumed for an already-running node; a sentinel is enough.
	obj.Object["stringData"] = map[string]any{"value": "", "format": "cloud-config"}
	return obj
}

// petsOf is the explicit pet list, or the legacy single control-plane master when Nodes is empty
// (the management cluster's shape).
func petsOf(spec adoptionv1alpha1.ClusterAdoptionSpec) []adoptionv1alpha1.NodeSpec {
	if len(spec.Nodes) > 0 {
		return spec.Nodes
	}
	return []adoptionv1alpha1.NodeSpec{
		{Name: spec.ClusterName + "-master", Role: adoptionv1alpha1.NodeRoleControlPlane},
	}
}

// lxcMachinePresence reads CAPN's verdict on the pet's instance from the LXCMachine we created — the
// status-driven presence check (no direct Incus probe; CAPN owns the connection + reach). decided
// is false while CAPN has not yet set the InstanceProvisioned condition (the caller requeues).
func (r *ClusterAdoptionReconciler) lxcMachinePresence(
	ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec, nodeName string,
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

// clusterAccessible reads CAPI's RemoteConnectionProbe on the Cluster — the accessibility signal
// that rolls ClusterAdoption -> ClusterProvision (distinct from instance presence). known=false
// while the condition is unset.
func (r *ClusterAdoptionReconciler) clusterAccessible(
	ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec,
) (accessible, known bool) {
	cl := &unstructured.Unstructured{}
	cl.SetGroupVersionKind(gvkCluster)
	if err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: spec.ClusterName}, cl); err != nil {
		return false, false
	}
	conditions, _, _ := unstructured.NestedSlice(cl.Object, "status", "conditions")
	for _, raw := range conditions {
		cond, ok := raw.(map[string]any)
		if !ok || cond["type"] != capiRemoteConnectionProbeCondition {
			continue
		}
		status, _ := cond["status"].(string)
		return status == "True", true
	}
	return false, false
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
