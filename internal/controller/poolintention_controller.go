package controller

import (
	"context"

	"k8s.io/apimachinery/pkg/api/equality"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// PoolIntentionReconciler reconciles a PoolIntention — the Flux-owned POOL-LEVEL intent — by OWNING
// a PoolAdoption (the pool mirror) derived from it. Symmetric with ClusterIntentionReconciler at the
// cluster grain: thin hand-off + phase mirror. It stamps LabelCluster/LabelPool onto the mirror so
// the ClusterAdoption reconciler can list + aggregate a cluster's pools.
type PoolIntentionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=poolintentions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=poolintentions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions,verbs=get;list;watch;create;update;patch

// Reconcile drives one PoolIntention. It persists status whenever the status CHANGED.
func (r *PoolIntentionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var intention adoptionv1alpha1.PoolIntention
	if err := r.Get(ctx, req.NamespacedName, &intention); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	before := intention.Status.DeepCopy()

	result, reconcileErr := r.reconcileSteps(ctx, &intention)

	intention.Status.ObservedGeneration = intention.Generation
	// Stamped INSIDE the guard — see ClusterIntentionReconciler.Reconcile. PoolIntention is the
	// `For` type of TWO controllers (this one and PoolReflectionReconciler), so an unconditional
	// write here woke both on every pass.
	if !equality.Semantic.DeepEqual(*before, intention.Status) {
		intention.Status.LastReconcileTime = metav1.Now()
		if statusErr := r.Status().Update(ctx, &intention); statusErr != nil {
			log.FromContext(ctx).Error(statusErr, "failed to update PoolIntention status")
			if reconcileErr == nil {
				reconcileErr = statusErr
			}
		}
	}
	return result, reconcileErr
}

func (r *PoolIntentionReconciler) reconcileSteps(
	ctx context.Context, pi *adoptionv1alpha1.PoolIntention,
) (ctrl.Result, error) {
	adoption := &adoptionv1alpha1.PoolAdoption{
		ObjectMeta: metav1.ObjectMeta{Name: pi.Name, Namespace: pi.Namespace},
	}
	op, err := controllerutil.CreateOrUpdate(ctx, r.Client, adoption, func() error {
		adoption.Spec = specFromPoolIntention(pi)
		labels := adoption.GetLabels()
		if labels == nil {
			labels = map[string]string{}
		}
		labels[adoptionv1alpha1.LabelCluster] = pi.Spec.ClusterRef
		labels[adoptionv1alpha1.LabelPool] = pi.Spec.Pool
		adoption.SetLabels(labels)
		return controllerutil.SetControllerReference(pi, adoption, r.Scheme)
	})
	if err != nil {
		r.mark(pi, adoptionv1alpha1.PoolIntentionConditionAdoptionCreated, false, "Error", err.Error())
		pi.Status.Phase = adoptionv1alpha1.PoolIntentionPhasePending
		return ctrl.Result{}, err
	}
	pi.Status.AdoptionRef = adoption.Name
	r.mark(pi, adoptionv1alpha1.PoolIntentionConditionAdoptionCreated, true, "Created",
		"PoolAdoption "+adoption.Name+" "+string(op))

	pi.Status.Phase = poolIntentionPhaseFor(adoption.Status.Phase)
	if pi.Status.Phase == adoptionv1alpha1.PoolIntentionPhaseAdopted {
		r.mark(pi, adoptionv1alpha1.PoolIntentionConditionReady, true, "Adopted",
			"the owned PoolAdoption reports Adopted")
	} else {
		r.mark(pi, adoptionv1alpha1.PoolIntentionConditionReady, false, string(pi.Status.Phase),
			"waiting for the owned PoolAdoption to reach Adopted")
	}
	return ctrl.Result{}, nil
}

// specFromPoolIntention projects the Flux intent onto the pool mirror.
func specFromPoolIntention(pi *adoptionv1alpha1.PoolIntention) adoptionv1alpha1.PoolAdoptionSpec {
	return adoptionv1alpha1.PoolAdoptionSpec{
		ClusterName:          pi.Spec.ClusterRef,
		Namespace:            pi.Spec.Namespace,
		Pool:                 pi.Spec.Pool,
		Role:                 pi.Spec.Role,
		Image:                pi.Spec.Image,
		RKE2Version:          pi.Spec.RKE2Version,
		KubeVIPVersion:       pi.Spec.KubeVIPVersion,
		ControlPlaneEndpoint: pi.Spec.ControlPlaneEndpoint,
		Nodes:                pi.Spec.Nodes,
	}
}

// poolIntentionPhaseFor maps the owned PoolAdoption's state machine phase onto the intent state machine phase.
func poolIntentionPhaseFor(a adoptionv1alpha1.PoolAdoptionPhase) adoptionv1alpha1.PoolIntentionPhase {
	switch a {
	case adoptionv1alpha1.PoolPhaseAdopted:
		return adoptionv1alpha1.PoolIntentionPhaseAdopted
	case adoptionv1alpha1.PoolPhaseAdopting:
		return adoptionv1alpha1.PoolIntentionPhaseAdopting
	case adoptionv1alpha1.PoolPhaseProvisioning:
		return adoptionv1alpha1.PoolIntentionPhaseProvisioning
	case adoptionv1alpha1.PoolPhaseDegraded, adoptionv1alpha1.PoolPhaseFailed:
		return adoptionv1alpha1.PoolIntentionPhaseDegraded
	default:
		return adoptionv1alpha1.PoolIntentionPhasePending
	}
}

func (r *PoolIntentionReconciler) mark(
	pi *adoptionv1alpha1.PoolIntention, condType string, ok bool, reason, message string,
) {
	status := metav1.ConditionFalse
	if ok {
		status = metav1.ConditionTrue
	}
	setCondition(&pi.Status.Conditions, metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: metav1.Now(),
		ObservedGeneration: pi.Generation,
	})
}

// SetupWithManager wires the reconciler to PoolIntention events + the owned PoolAdoption's.
func (r *PoolIntentionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.PoolIntention{}).
		Owns(&adoptionv1alpha1.PoolAdoption{}).
		Named("poolintention").
		Complete(r)
}
