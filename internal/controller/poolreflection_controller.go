package controller

import (
	"context"
	"encoding/json"
	"sort"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// PoolReflectionReconciler is the cluster→git REFLECTOR — the third role of the triad. For each
// managed pool (anchored on its PoolIntention) it OBSERVES the live Machines and NOTES the observed
// roster into a PoolReflection committed onto the managing branch (manifests/<SelfCluster>). It never
// creates/deletes Machines — CAPI/CAPN/CAPRKE2 own their lifecycle; the reflector only records what
// ran. The PoolReflection's PRESENCE on the branch is the adopt-vs-greenfield switch the PoolAdoption
// reconciler reads (directly from git, not etcd — see the spec's <<existence>>). The Flux-applied copy
// in etcd is a pure operator view.
type PoolReflectionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// SelfCluster is the cluster this controller runs IN; the managing branch is manifests/<SelfCluster>
	// (model B: a workload's CRs + its reflection live on the managing cluster's branch).
	SelfCluster string
	// Git carries the branch-write config (repo URL, token). Nil disables the git write (observe-only
	// dry-run) — the roster is still derived + surfaced on status.
	Git *ReflectorGit
}

// PoolReflection is a git-only YAML document (not a k8s resource), so no poolreflections RBAC.
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=poolintentions,verbs=get;list;watch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=machines,verbs=get;list;watch
// +kubebuilder:rbac:groups=source.toolkit.fluxcd.io,resources=gitrepositories,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch

// Reconcile observes the pool's live Machines and reflects the roster to git. Anchored on the
// PoolIntention (the stable per-pool object); a Machine change re-runs it via the watch.
func (r *PoolReflectionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var intention adoptionv1alpha1.PoolIntention
	if err := r.Get(ctx, req.NamespacedName, &intention); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// The STANDALONE boundary: the SELF cluster (the mgmt plane this controller runs on) is CANONICAL,
	// never reflected — it cannot reflect its own state during its own cold-start (the controller does
	// not exist yet at its bootstrap). Only IN_CLUSTER managed clusters are reflector-eligible.
	if r.SelfCluster != "" && intention.Spec.ClusterRef == r.SelfCluster {
		return ctrl.Result{}, nil
	}

	roster, err := r.observeRoster(ctx, intention.Spec.ClusterRef, intention.Spec.Namespace)
	if err != nil {
		return ctrl.Result{}, err
	}

	reflection := r.reflectionObj(&intention, roster)
	if r.Git != nil {
		if err := r.Git.WriteReflection(ctx, r.SelfCluster, reflection); err != nil {
			log.FromContext(ctx).Error(err, "failed to write PoolReflection to git",
				"cluster", intention.Spec.ClusterRef, "pool", intention.Spec.Pool)
			return ctrl.Result{RequeueAfter: 30 * time.Second}, err
		}
	}

	log.FromContext(ctx).Info("pool reflected", "cluster", intention.Spec.ClusterRef,
		"pool", intention.Spec.Pool, "observed", len(roster))
	// Re-observe on a slow cadence as a backstop to the Machine watch (rosters change rarely).
	return ctrl.Result{RequeueAfter: 5 * time.Minute}, nil
}

// observeRoster lists the cluster's live control-plane Machines and returns their names, sorted (a
// deterministic roster → no reflect churn). A Machine that is being deleted is NOT observed (it is
// leaving). Scope note: keyed by the CAPI cluster-name label; pool-grain splitting (workers) is a
// future refinement — control-node is the only pool in scope, so the cluster's CP Machines ARE the
// pool's roster.
func (r *PoolReflectionReconciler) observeRoster(ctx context.Context, clusterName, namespace string) ([]adoptionv1alpha1.PetSpec, error) {
	machines := &unstructured.UnstructuredList{}
	machines.SetGroupVersionKind(gvkMachine)
	if err := r.List(ctx, machines,
		client.InNamespace(namespace),
		client.MatchingLabels{clusterNameLabel: clusterName},
	); err != nil {
		return nil, err
	}
	names := make([]string, 0, len(machines.Items))
	for i := range machines.Items {
		m := &machines.Items[i]
		if !m.GetDeletionTimestamp().IsZero() {
			continue // leaving — not part of the observed roster
		}
		if _, isCP := m.GetLabels()[controlPlaneLabel]; !isCP {
			continue
		}
		names = append(names, m.GetName())
	}
	sort.Strings(names)
	roster := make([]adoptionv1alpha1.PetSpec, len(names))
	for i, n := range names {
		roster[i] = adoptionv1alpha1.PetSpec{Name: n}
	}
	return roster, nil
}

// annotationOwnerRef is the UID-free, cold-start-durable owner reference the reflector stamps on the
// PoolReflection it commits to git (git cannot carry an etcd UID). The annotation is the SOURCE OF
// TRUTH of the relation; the native etcd ownerRef is a derived projection re-stamped each reconcile
// (Velero remap-on-restore) — see the spec's <<reflector-translation>>.
const annotationOwnerRef = "cluster.seedmatic.io/owner-ref"

// ownerRefAnnotation renders a PoolIntention as the name-based owner-ref JSON value.
func ownerRefAnnotation(owner *adoptionv1alpha1.PoolIntention) string {
	b, _ := json.Marshal(map[string]string{
		"apiVersion": adoptionv1alpha1.GroupVersion.String(),
		"kind":       "PoolIntention",
		"name":       owner.Name,
		"namespace":  owner.Namespace,
	})
	return string(b)
}

// reflectionObj builds the PoolReflection for a pool from the observed roster. name-based owner
// annotation → the reflection's owner is its PoolIntention (UID-free, cold-start-durable).
func (r *PoolReflectionReconciler) reflectionObj(intention *adoptionv1alpha1.PoolIntention, roster []adoptionv1alpha1.PetSpec) *adoptionv1alpha1.PoolReflection {
	return &adoptionv1alpha1.PoolReflection{
		TypeMeta: metav1.TypeMeta{
			APIVersion: adoptionv1alpha1.GroupVersion.String(),
			Kind:       "PoolReflection",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      intention.Name,
			Namespace: intention.Spec.Namespace,
			Labels: map[string]string{
				adoptionv1alpha1.LabelCluster: intention.Spec.ClusterRef,
				adoptionv1alpha1.LabelPool:    intention.Spec.Pool,
			},
			Annotations: map[string]string{
				annotationOwnerRef: ownerRefAnnotation(intention),
			},
		},
		Spec: adoptionv1alpha1.PoolReflectionSpec{
			ClusterRef: intention.Spec.ClusterRef,
			Namespace:  intention.Spec.Namespace,
			Pool:       intention.Spec.Pool,
			Nodes:      roster,
		},
	}
}

// machineToIntention maps a Machine event to the PoolIntention(s) of its cluster, so a presence change
// re-runs the reflect. (Control-node scope: cluster → its control-node PoolIntention.)
func (r *PoolReflectionReconciler) machineToIntention(ctx context.Context, obj client.Object) []reconcile.Request {
	clusterName := obj.GetLabels()[clusterNameLabel]
	if clusterName == "" {
		return nil
	}
	var intentions adoptionv1alpha1.PoolIntentionList
	if err := r.List(ctx, &intentions,
		client.InNamespace(obj.GetNamespace()),
		client.MatchingLabels{adoptionv1alpha1.LabelCluster: clusterName},
	); err != nil {
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(intentions.Items))
	for i := range intentions.Items {
		reqs = append(reqs, reconcile.Request{NamespacedName: types.NamespacedName{
			Namespace: intentions.Items[i].Namespace, Name: intentions.Items[i].Name,
		}})
	}
	return reqs
}

// SetupWithManager anchors on the PoolIntention and watches the per-pet Machines (CAPN/CAPRKE2's
// presence changes re-run the reflect). Machines are watched UNSTRUCTURED — no typed CAPI dependency.
func (r *PoolReflectionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	machine := &unstructured.Unstructured{}
	machine.SetGroupVersionKind(gvkMachine)
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.PoolIntention{}).
		Watches(machine, handler.EnqueueRequestsFromMapFunc(r.machineToIntention)).
		Named("poolreflection").
		Complete(r)
}
