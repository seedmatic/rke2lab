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
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// selfAdoptionFinalizer guards the ClusterAdoption of the cluster this controller RUNS ON. Deleting
// it would cascade (ownerRef) to the CAPI Cluster and suicide the management plane, so we keep this
// finalizer on a self-adoption and refuse to remove it — the deletion blocks by design.
const selfAdoptionFinalizer = "cluster.seedmatic.io/self-adoption-guard"

// ClusterAdoptionReconciler adopts the CLUSTER-LEVEL half of a running RKE2-on-Incus cluster into
// Cluster API: it owns the Cluster + LXCCluster and AGGREGATES the per-pool PoolAdoptions into
// cluster existence + reachability. The pool CR-set (RCP / templates / Machines) is the
// PoolAdoption reconciler's. Every object is created-if-absent, so a re-reconcile (and a cold-start,
// which wipes etcd and re-creates the CR-set) safely re-adopts the surviving cluster.
type ClusterAdoptionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// SelfCluster is the name of the cluster this controller runs IN (SELF_CLUSTER_NAME env). A
	// ClusterAdoption whose clusterName equals it is a self-adoption — its deletion is refused.
	SelfCluster string
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusteradoptions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusteradoptions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions,verbs=get;list;watch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=clusters,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcclusters,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch

// Reconcile drives one ClusterAdoption. It ALWAYS persists status afterwards — so a failure (or a
// wait) is visible on the CR itself, not only in the pod logs.
func (r *ClusterAdoptionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var adoption adoptionv1alpha1.ClusterAdoption
	if err := r.Get(ctx, req.NamespacedName, &adoption); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// Self-adoption guard: the ClusterAdoption of the cluster this controller RUNS ON must never be
	// torn down (its ownerRef'd Cluster would cascade-delete = suicide the management plane).
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

func (r *ClusterAdoptionReconciler) reconcileSteps(
	ctx context.Context, a *adoptionv1alpha1.ClusterAdoption,
) (ctrl.Result, error) {
	spec := a.Spec

	// Gate the cluster CR-set on the identity Secret the LXCCluster.secretRef names — without it CAPN
	// cannot connect to the remote. The BYO-CA Secrets are the control-plane POOL's gate (pool grain).
	var idSecret corev1.Secret
	err := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: spec.Remote.IdentitySecretName}, &idSecret)
	if apierrors.IsNotFound(err) {
		r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "MaterialMissing",
			"waiting for seed-master identity Secret "+spec.Remote.IdentitySecretName)
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	if err != nil {
		r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error", err.Error())
		return ctrl.Result{}, err
	}

	// 1. The cluster-scoped CR-set: Cluster (UNPAUSED) + LXCCluster, OWNED by this ClusterAdoption
	//    (adopting a cluster makes us its owner, so deleting the adoption cascades to the Cluster).
	//    The Cluster is NOT paused: a paused Cluster propagates to its LXCMachines, so CAPN's
	//    IsPaused would SKIP them → never report presence → existence stays false → the old
	//    unpause-on-existence never fired (deadlock). The rival-init guard is the RKE2ControlPlane's
	//    OWN paused annotation (inert from birth, un-paused by PoolAdoption once its owned Machines
	//    exist) — the Cluster does not need to be paused for that.
	cluster := r.clusterObj(spec, false)
	if err := controllerutil.SetControllerReference(a, cluster, r.Scheme); err != nil {
		r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error", "Cluster ownerRef: "+err.Error())
		return ctrl.Result{}, err
	}
	lxc := r.lxcClusterObj(spec)
	if err := controllerutil.SetControllerReference(a, lxc, r.Scheme); err != nil {
		r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error", "LXCCluster ownerRef: "+err.Error())
		return ctrl.Result{}, err
	}
	for _, obj := range []*unstructured.Unstructured{cluster, lxc} {
		if err := ensure(ctx, r.Client, obj); err != nil {
			r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, false, "Error",
				obj.GetKind()+" "+obj.GetName()+": "+err.Error())
			return ctrl.Result{}, err
		}
	}
	r.mark(a, adoptionv1alpha1.ConditionCRSetCreated, true, "Created", "Cluster(owned)/LXCCluster ensured (unpaused)")

	// 2. Aggregate the per-pool PoolAdoptions of this cluster.
	agg, err := r.aggregatePools(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.ConditionExistence, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	a.Status.Existence = agg.existence
	a.Status.PoolsTotal = int32(agg.total)
	a.Status.PoolsAdopted = int32(agg.adopted)

	// Existence = the anti-greenfield guard: any present pet (any pool) ⇒ the cluster EXISTS.
	if agg.existence {
		r.mark(a, adoptionv1alpha1.ConditionExistence, true, "Present",
			fmt.Sprintf("%d/%d pools report a present pet", agg.poolsWithPresence, agg.total))
	} else {
		r.mark(a, adoptionv1alpha1.ConditionExistence, false, "Absent",
			fmt.Sprintf("no pet present across %d pool(s)", agg.total))
	}

	// PoolsAdopted rolls up the pool state machines; its reason carries "Provisioning" so derivePhase can
	// route the aggregate phase there.
	switch {
	case agg.total > 0 && agg.adopted == agg.total:
		r.mark(a, adoptionv1alpha1.ConditionPoolsAdopted, true, "Adopted",
			fmt.Sprintf("%d/%d pools Adopted", agg.adopted, agg.total))
	case agg.anyProvisioning:
		r.mark(a, adoptionv1alpha1.ConditionPoolsAdopted, false, "Provisioning",
			fmt.Sprintf("%d/%d pools Adopted — a pool is provisioning absent pet(s)", agg.adopted, agg.total))
	default:
		r.mark(a, adoptionv1alpha1.ConditionPoolsAdopted, false, "Adopting",
			fmt.Sprintf("%d/%d pools Adopted", agg.adopted, agg.total))
	}

	// 3. Ensure the Cluster is un-paused — UNCONDITIONALLY, so a Cluster left paused by an earlier
	//    version self-heals (ensure is create-if-absent and would not patch it). CAPN needs the
	//    LXCMachines un-paused to adopt them and report presence; the rival-init guard is the RCP's
	//    own paused annotation, not the Cluster's.
	if err := r.unpauseCluster(ctx, spec); err != nil {
		return ctrl.Result{}, err
	}

	// 4. Reachability rollup (operator view): CAPI's RemoteConnectionProbe on the Cluster.
	accessible, _ := r.clusterAccessible(ctx, spec)
	a.Status.Reachable = accessible
	if accessible {
		r.mark(a, adoptionv1alpha1.ConditionAccessible, true, "Reachable", "apiserver reachable (RemoteConnectionProbe)")
	} else {
		r.mark(a, adoptionv1alpha1.ConditionAccessible, false, "Unreachable", "apiserver not reachable yet (RemoteConnectionProbe)")
	}

	log.FromContext(ctx).Info("cluster adoption reconciled", "cluster", spec.ClusterName,
		"existence", agg.existence, "poolsAdopted", agg.adopted, "poolsTotal", agg.total, "reachable", accessible)
	adopted := agg.total > 0 && agg.adopted == agg.total
	if !adopted || !accessible {
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// poolAggregate is the roll-up of a cluster's PoolAdoptions.
type poolAggregate struct {
	total             int
	adopted           int
	poolsWithPresence int
	existence         bool
	anyProvisioning   bool
}

// aggregatePools lists the cluster's PoolAdoptions (by LabelCluster) and rolls up existence +
// adoption + provisioning.
func (r *ClusterAdoptionReconciler) aggregatePools(
	ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec,
) (poolAggregate, error) {
	var pools adoptionv1alpha1.PoolAdoptionList
	if err := r.List(ctx, &pools,
		client.InNamespace(spec.Namespace),
		client.MatchingLabels{adoptionv1alpha1.LabelCluster: spec.ClusterName},
	); err != nil {
		return poolAggregate{}, err
	}
	var agg poolAggregate
	agg.total = len(pools.Items)
	for i := range pools.Items {
		st := pools.Items[i].Status
		if st.Present > 0 {
			agg.existence = true
			agg.poolsWithPresence++
		}
		switch st.Phase {
		case adoptionv1alpha1.PoolPhaseAdopted:
			agg.adopted++
		case adoptionv1alpha1.PoolPhaseProvisioning:
			agg.anyProvisioning = true
		}
	}
	return agg, nil
}

// derivePhase rolls the aggregate conditions (+ the reconcile error) into the coarse cluster phase.
func (r *ClusterAdoptionReconciler) derivePhase(
	a *adoptionv1alpha1.ClusterAdoption, reconcileErr error,
) adoptionv1alpha1.ClusterAdoptionPhase {
	if reconcileErr != nil {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "ReconcileError", reconcileErr.Error())
		return adoptionv1alpha1.PhaseFailed
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.ConditionCRSetCreated) {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "Pending", "aligning the cluster CR-set / waiting for material")
		return adoptionv1alpha1.PhasePending
	}
	if a.Status.PoolsTotal == 0 {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "Pending", "no pool has reported yet")
		return adoptionv1alpha1.PhasePending
	}
	if conditionReason(a.Status.Conditions, adoptionv1alpha1.ConditionPoolsAdopted) == "Provisioning" {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "Provisioning",
			"a pool is provisioning absent pet(s)")
		return adoptionv1alpha1.PhaseProvisioning
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.ConditionPoolsAdopted) {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "Adopting", "pools converging to Adopted")
		return adoptionv1alpha1.PhaseAdopting
	}
	// All pools Adopted. Reachability decides Adopted vs Degraded (present-sick).
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.ConditionAccessible) {
		r.mark(a, adoptionv1alpha1.ConditionReady, false, "Degraded",
			"all pools present but apiserver unreachable — retrying adopt, not re-provisioning")
		return adoptionv1alpha1.PhaseDegraded
	}
	r.mark(a, adoptionv1alpha1.ConditionReady, true, "Adopted", "all pools Adopted and apiserver reachable")
	return adoptionv1alpha1.PhaseAdopted
}

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

func (r *ClusterAdoptionReconciler) clusterObj(spec adoptionv1alpha1.ClusterAdoptionSpec, paused bool) *unstructured.Unstructured {
	obj := newObj(gvkCluster, spec.ClusterName, spec.Namespace)
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
			"name":     controlPlaneName(spec.ClusterName),
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
	obj := newObj(gvkLXCCluster, spec.ClusterName, spec.Namespace)
	obj.Object["spec"] = map[string]any{
		"secretRef": map[string]any{"name": spec.Remote.IdentitySecretName},
		"controlPlaneEndpoint": map[string]any{
			"host": spec.ControlPlaneEndpoint.Host,
			"port": int64(spec.ControlPlaneEndpoint.Port),
		},
		"loadBalancer": map[string]any{"kubeVIP": map[string]any{}},
	}
	return obj
}

// unpauseCluster clears the Cluster's spec.paused=false, letting CAPI reconcile it against the
// pools' owned Machines/RCP.
func (r *ClusterAdoptionReconciler) unpauseCluster(ctx context.Context, spec adoptionv1alpha1.ClusterAdoptionSpec) error {
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
		return r.Update(ctx, cluster)
	}
	return nil
}

// clusterAccessible reads CAPI's RemoteConnectionProbe on the Cluster — the reachability signal.
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

// poolToCluster maps a PoolAdoption event to the ClusterAdoption(s) of its cluster, so a pool's
// presence/phase change re-runs the aggregate.
func (r *ClusterAdoptionReconciler) poolToCluster(ctx context.Context, obj client.Object) []reconcile.Request {
	cluster := obj.GetLabels()[adoptionv1alpha1.LabelCluster]
	if cluster == "" {
		return nil
	}
	var adoptions adoptionv1alpha1.ClusterAdoptionList
	if err := r.List(ctx, &adoptions, client.InNamespace(obj.GetNamespace())); err != nil {
		return nil
	}
	var reqs []reconcile.Request
	for i := range adoptions.Items {
		if adoptions.Items[i].Spec.ClusterName == cluster {
			reqs = append(reqs, reconcile.Request{NamespacedName: types.NamespacedName{
				Namespace: adoptions.Items[i].Namespace, Name: adoptions.Items[i].Name,
			}})
		}
	}
	return reqs
}

// SetupWithManager wires the reconciler to ClusterAdoption events, the owned Cluster/LXCCluster's
// (a Cluster delete — the recreate/rebirth gesture — re-runs ensure at once, resurrecting it from the
// surviving intention), and the cluster's PoolAdoptions (for the aggregate). The CAPI objects are
// watched UNSTRUCTURED, so the controller keeps no typed CAPI module dependency.
func (r *ClusterAdoptionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	cluster := &unstructured.Unstructured{}
	cluster.SetGroupVersionKind(gvkCluster)
	lxcCluster := &unstructured.Unstructured{}
	lxcCluster.SetGroupVersionKind(gvkLXCCluster)
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.ClusterAdoption{}).
		Owns(cluster).
		Owns(lxcCluster).
		Watches(&adoptionv1alpha1.PoolAdoption{}, handler.EnqueueRequestsFromMapFunc(r.poolToCluster)).
		Named("clusteradoption").
		Complete(r)
}
