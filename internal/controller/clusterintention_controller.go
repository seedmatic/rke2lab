package controller

import (
	"context"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// ClusterIntentionReconciler reconciles a ClusterIntention — the Flux-owned CLUSTER-LEVEL intent —
// by OWNING a ClusterAdoption (the cluster mirror) derived from it. The ownership split the design
// mandates: Flux owns the intent (git-backed, survives a cold-start), the controller owns the mirror
// (re-derived from reality by the ClusterAdoption reconciler). This reconciler is THIN: it hands off
// the intent and mirrors the resulting aggregate phase back onto the ClusterIntention — so
// `kubectl get clusterintention` is the single durable pane even after a cold-start wipes the
// ClusterAdoption. Symmetric with PoolIntentionReconciler at the pool grain.
type ClusterIntentionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusterintentions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusterintentions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusteradoptions,verbs=get;list;watch;create;update;patch

// Reconcile drives one ClusterIntention. It ALWAYS persists status afterwards, so a failure/wait is
// visible on the CR itself.
func (r *ClusterIntentionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var intention adoptionv1alpha1.ClusterIntention
	if err := r.Get(ctx, req.NamespacedName, &intention); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	result, reconcileErr := r.reconcileSteps(ctx, &intention)

	intention.Status.ObservedGeneration = intention.Generation
	intention.Status.LastReconcileTime = metav1.Now()
	if statusErr := r.Status().Update(ctx, &intention); statusErr != nil {
		log.FromContext(ctx).Error(statusErr, "failed to update ClusterIntention status")
		if reconcileErr == nil {
			reconcileErr = statusErr
		}
	}
	return result, reconcileErr
}

func (r *ClusterIntentionReconciler) reconcileSteps(
	ctx context.Context, ci *adoptionv1alpha1.ClusterIntention,
) (ctrl.Result, error) {
	// Materialise the owned ClusterAdoption (the mirror). CreateOrUpdate so a spec change flows
	// through, and a cold-start (etcd wiped -> the mirror gone) re-creates it from the surviving,
	// Flux-re-rendered ClusterIntention. SetControllerReference makes it a cascade-GC child AND (via
	// Owns below) requeues this reconcile on its events.
	adoption := &adoptionv1alpha1.ClusterAdoption{
		ObjectMeta: metav1.ObjectMeta{Name: ci.Name, Namespace: ci.Namespace},
	}
	op, err := controllerutil.CreateOrUpdate(ctx, r.Client, adoption, func() error {
		adoption.Spec = specFromClusterIntention(ci)
		return controllerutil.SetControllerReference(ci, adoption, r.Scheme)
	})
	if err != nil {
		r.mark(ci, adoptionv1alpha1.IntentionConditionAdoptionCreated, false, "Error", err.Error())
		ci.Status.Phase = adoptionv1alpha1.IntentionPhasePending
		return ctrl.Result{}, err
	}
	ci.Status.AdoptionRef = adoption.Name
	r.mark(ci, adoptionv1alpha1.IntentionConditionAdoptionCreated, true, "Created",
		"ClusterAdoption "+adoption.Name+" "+string(op))

	// Mirror the owned mirror's aggregate phase. The adopt-first decision lives in the ClusterAdoption
	// reconciler; here we only surface WHERE it got to.
	ci.Status.Phase = clusterIntentionPhaseFor(adoption.Status.Phase)
	if ci.Status.Phase == adoptionv1alpha1.IntentionPhaseAdopted {
		r.mark(ci, adoptionv1alpha1.IntentionConditionReady, true, "Adopted",
			"the owned ClusterAdoption reports Adopted")
	} else {
		r.mark(ci, adoptionv1alpha1.IntentionConditionReady, false, string(ci.Status.Phase),
			"waiting for the owned ClusterAdoption to reach Adopted")
	}
	return ctrl.Result{}, nil
}

// specFromClusterIntention projects the Flux intent onto the cluster mirror — cluster-level facts
// only (the per-pool roster lives in the PoolIntention children).
func specFromClusterIntention(ci *adoptionv1alpha1.ClusterIntention) adoptionv1alpha1.ClusterAdoptionSpec {
	return adoptionv1alpha1.ClusterAdoptionSpec{
		ClusterName:          ci.Spec.ClusterName,
		Namespace:            ci.Spec.Namespace,
		ControlPlaneEndpoint: ci.Spec.ControlPlaneEndpoint,
		ClusterNetwork:       ci.Spec.ClusterNetwork,
		Remote:               ci.Spec.Remote,
	}
}

// clusterIntentionPhaseFor maps the owned ClusterAdoption's aggregate phase onto the intent funnel
// phase — 1:1. A reconcile error (PhaseFailed) surfaces as Degraded.
func clusterIntentionPhaseFor(a adoptionv1alpha1.ClusterAdoptionPhase) adoptionv1alpha1.ClusterIntentionPhase {
	switch a {
	case adoptionv1alpha1.PhaseAdopted:
		return adoptionv1alpha1.IntentionPhaseAdopted
	case adoptionv1alpha1.PhaseAdopting:
		return adoptionv1alpha1.IntentionPhaseAdopting
	case adoptionv1alpha1.PhaseProvisioning:
		return adoptionv1alpha1.IntentionPhaseProvisioning
	case adoptionv1alpha1.PhaseDegraded, adoptionv1alpha1.PhaseFailed:
		return adoptionv1alpha1.IntentionPhaseDegraded
	default:
		return adoptionv1alpha1.IntentionPhasePending
	}
}

func (r *ClusterIntentionReconciler) mark(
	ci *adoptionv1alpha1.ClusterIntention, condType string, ok bool, reason, message string,
) {
	status := metav1.ConditionFalse
	if ok {
		status = metav1.ConditionTrue
	}
	setCondition(&ci.Status.Conditions, metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: metav1.Now(),
		ObservedGeneration: ci.Generation,
	})
}

// SetupWithManager wires the reconciler to ClusterIntention events + the owned ClusterAdoption's.
func (r *ClusterIntentionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.ClusterIntention{}).
		Owns(&adoptionv1alpha1.ClusterAdoption{}).
		Named("clusterintention").
		Complete(r)
}
