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

// ClusterProvisionReconciler reconciles a ClusterProvision — the Flux-owned INTENT — by OWNING a
// ClusterAdoption derived from it. This is the ownership split the design mandates: Flux owns the
// intent (git-backed, survives a cold-start), the controller owns the adoption (the mirror the
// ClusterAdoption reconciler drives). The adopt-first logic (probe an instance, adopt vs provision)
// lives in the ClusterAdoption reconciler; THIS reconciler hands off the intent and mirrors the
// resulting funnel phase back onto the ClusterProvision — so `kubectl get clusterprovision` is the
// single durable pane even after a cold-start wipes the ClusterAdoption.
type ClusterProvisionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusterprovisions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=clusterprovisions/status,verbs=get;update;patch

// Reconcile drives one ClusterProvision. Like the ClusterAdoption wrapper it ALWAYS persists status
// afterwards, so a failure/wait is visible on the CR itself.
func (r *ClusterProvisionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var provision adoptionv1alpha1.ClusterProvision
	if err := r.Get(ctx, req.NamespacedName, &provision); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	result, reconcileErr := r.reconcileSteps(ctx, &provision)

	provision.Status.ObservedGeneration = provision.Generation
	provision.Status.LastReconcileTime = metav1.Now()
	if statusErr := r.Status().Update(ctx, &provision); statusErr != nil {
		log.FromContext(ctx).Error(statusErr, "failed to update ClusterProvision status")
		if reconcileErr == nil {
			reconcileErr = statusErr
		}
	}
	return result, reconcileErr
}

func (r *ClusterProvisionReconciler) reconcileSteps(
	ctx context.Context, cp *adoptionv1alpha1.ClusterProvision,
) (ctrl.Result, error) {
	// Materialise the owned ClusterAdoption (the mirror). CreateOrUpdate so a spec change on the
	// intent flows through, and a cold-start (etcd wiped -> the ClusterAdoption gone) re-creates it
	// from the surviving, Flux-re-rendered ClusterProvision. SetControllerReference makes the
	// adoption a cascade-GC child AND (via Owns below) requeues this reconcile on its events.
	adoption := &adoptionv1alpha1.ClusterAdoption{
		ObjectMeta: metav1.ObjectMeta{Name: cp.Name, Namespace: cp.Namespace},
	}
	op, err := controllerutil.CreateOrUpdate(ctx, r.Client, adoption, func() error {
		adoption.Spec = specFromProvision(cp)
		return controllerutil.SetControllerReference(cp, adoption, r.Scheme)
	})
	if err != nil {
		r.markProvision(cp, adoptionv1alpha1.ProvisionConditionAdoptionCreated, false, "Error", err.Error())
		cp.Status.Phase = adoptionv1alpha1.ProvisionPhasePending
		return ctrl.Result{}, err
	}
	cp.Status.AdoptionRef = adoption.Name
	r.markProvision(cp, adoptionv1alpha1.ProvisionConditionAdoptionCreated, true, "Created",
		"ClusterAdoption "+adoption.Name+" "+string(op))

	// Mirror the owned ClusterAdoption's phase into the funnel phase. The adopt-first decision
	// (probe -> adopt or provision) is the ClusterAdoption reconciler's; here we only surface WHERE
	// it got to, so `kubectl get clusterprovision` shows PHASE without a second probe.
	cp.Status.Phase = provisionPhaseFor(adoption.Status.Phase)
	if cp.Status.Phase == adoptionv1alpha1.ProvisionPhaseAdopted {
		r.markProvision(cp, adoptionv1alpha1.ProvisionConditionReady, true, "Adopted",
			"the owned ClusterAdoption reports Adopted")
	} else {
		r.markProvision(cp, adoptionv1alpha1.ProvisionConditionReady, false, string(cp.Status.Phase),
			"waiting for the owned ClusterAdoption to reach Adopted")
	}
	return ctrl.Result{}, nil
}

// specFromProvision projects the Flux intent onto the ClusterAdoption the controller owns. The
// controller templates, it does not compute addressing — every value is seed-master's (the intent).
func specFromProvision(cp *adoptionv1alpha1.ClusterProvision) adoptionv1alpha1.ClusterAdoptionSpec {
	return adoptionv1alpha1.ClusterAdoptionSpec{
		ClusterName:          cp.Spec.ClusterName,
		Namespace:            cp.Spec.Namespace,
		ControlPlaneReplicas: controlPlaneReplicas(cp.Spec.Nodes),
		ControlPlaneEndpoint: cp.Spec.ControlPlaneEndpoint,
		ClusterNetwork:       cp.Spec.ClusterNetwork,
		Image:                cp.Spec.Image,
		IdentitySecretName:   cp.Spec.Remote.IdentitySecretName,
		RKE2Version:          cp.Spec.RKE2Version,
		KubeVIPVersion:       cp.Spec.KubeVIPVersion,
		Nodes:                cp.Spec.Nodes,
		RemoteEndpoint:       cp.Spec.Remote.Endpoint,
	}
}

// controlPlaneReplicas counts the control-plane pets — the RKE2ControlPlane replica count. A bare
// intent with no explicit nodes falls back to a single control plane (the management-cluster shape).
func controlPlaneReplicas(nodes []adoptionv1alpha1.NodeSpec) int32 {
	var n int32
	for _, node := range nodes {
		if node.Role == adoptionv1alpha1.NodeRoleControlPlane {
			n++
		}
	}
	if n == 0 {
		return 1
	}
	return n
}

// provisionPhaseFor maps the owned ClusterAdoption's phase onto the ClusterProvision funnel phase.
func provisionPhaseFor(a adoptionv1alpha1.ClusterAdoptionPhase) adoptionv1alpha1.ClusterProvisionPhase {
	switch a {
	case adoptionv1alpha1.PhaseAdopted:
		return adoptionv1alpha1.ProvisionPhaseAdopted
	case adoptionv1alpha1.PhaseAdopting:
		return adoptionv1alpha1.ProvisionPhaseAdopting
	case adoptionv1alpha1.PhaseFailed:
		return adoptionv1alpha1.ProvisionPhaseDegraded
	default:
		return adoptionv1alpha1.ProvisionPhasePending
	}
}

// markProvision sets one condition on the ClusterProvision (shares setCondition with the adoption
// controller — same package).
func (r *ClusterProvisionReconciler) markProvision(
	cp *adoptionv1alpha1.ClusterProvision, condType string, ok bool, reason, message string,
) {
	status := metav1.ConditionFalse
	if ok {
		status = metav1.ConditionTrue
	}
	setCondition(&cp.Status.Conditions, metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: metav1.Now(),
		ObservedGeneration: cp.Generation,
	})
}

// SetupWithManager wires the reconciler to ClusterProvision events + the owned ClusterAdoption's.
func (r *ClusterProvisionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.ClusterProvision{}).
		Owns(&adoptionv1alpha1.ClusterAdoption{}).
		Named("clusterprovision").
		Complete(r)
}
