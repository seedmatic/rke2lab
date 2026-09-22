package controller

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/equality"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// gvkZFSVolume is openebs-zfs's volume CR. Unstructured, like the CAPI objects — the controller
// speaks openebs's group/kind and carries no typed openebs dependency.
var gvkZFSVolume = schema.GroupVersionKind{Group: "zfs.openebs.io", Version: "v1", Kind: "ZFSVolume"}

const (
	// openebsNamespace is where the zfs-localpv chart runs, and so where its ZFSVolume CRs live.
	openebsNamespace = "openebs"

	// zfsCSIDriver is the openebs-zfs CSI driver name the static PV names.
	zfsCSIDriver = "zfs.csi.openebs.io"

	// openebsNodenameLabel is the label openebs puts on a node, valued at the node NAME — what the
	// PV's nodeAffinity matches on.
	openebsNodenameLabel = "openebs.io/nodename"

	// nodeRoleLabelPrefix + the intention's NodeRole is the label an eligible node must carry.
	nodeRoleLabelPrefix = "node-role.kubernetes.io/"

	// volumeRequeueInterval is how long to wait before re-electing when no node is eligible yet — a
	// fresh cluster's control plane is not Ready the instant this controller starts.
	volumeRequeueInterval = 15 * time.Second
)

// VolumeIntentionReconciler places ONE persist volume of the cluster it runs in: it elects a node and
// owns the resulting ZFSVolume (ownerNodeID) + static PersistentVolume (nodeAffinity) — the half
// GitOps cannot pre-set, because a managed node's name is random and unknowable at render time.
//
// It is the only reconciler here that does not touch Cluster API, and it is the reason this
// controller now runs in EVERY cluster rather than only in a management plane: a workload cluster has
// the identical need, and the management cluster only ever appeared to work because a hardcoded node
// name happened to match its single node.
type VolumeIntentionReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=volumeintentions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=volumeintentions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups="",resources=nodes,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=persistentvolumes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=zfs.openebs.io,resources=zfsvolumes,verbs=get;list;watch;create;update;patch

// Reconcile drives one VolumeIntention. Status is persisted only when it CHANGED, so a steady state
// does not wake the controller in a loop.
func (r *VolumeIntentionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var intention adoptionv1alpha1.VolumeIntention
	if err := r.Get(ctx, req.NamespacedName, &intention); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	before := intention.Status.DeepCopy()

	result, reconcileErr := r.reconcileSteps(ctx, &intention)

	intention.Status.ObservedGeneration = intention.Generation
	if !equality.Semantic.DeepEqual(*before, intention.Status) {
		intention.Status.LastReconcileTime = metav1.Now()
		if statusErr := r.Status().Update(ctx, &intention); statusErr != nil {
			log.FromContext(ctx).Error(statusErr, "failed to update VolumeIntention status")
			if reconcileErr == nil {
				reconcileErr = statusErr
			}
		}
	}
	return result, reconcileErr
}

func (r *VolumeIntentionReconciler) reconcileSteps(
	ctx context.Context, vi *adoptionv1alpha1.VolumeIntention,
) (ctrl.Result, error) {
	node, err := r.electNode(ctx, vi)
	if err != nil {
		r.mark(vi, adoptionv1alpha1.VolumeConditionNodeElected, false, "ElectionFailed", err.Error())
		vi.Status.Phase = adoptionv1alpha1.VolumePhaseFailed
		return ctrl.Result{}, err
	}
	if node == "" {
		r.mark(vi, adoptionv1alpha1.VolumeConditionNodeElected, false, "NoEligibleNode",
			"no Ready node carries "+nodeRoleLabelPrefix+vi.Spec.NodeRole)
		r.mark(vi, adoptionv1alpha1.VolumeConditionReady, false, "NoEligibleNode", "waiting for an eligible node")
		vi.Status.Phase = adoptionv1alpha1.VolumePhasePending
		return ctrl.Result{RequeueAfter: volumeRequeueInterval}, nil
	}
	vi.Status.Node = node
	r.mark(vi, adoptionv1alpha1.VolumeConditionNodeElected, true, "NodeElected", "elected "+node)

	if err := r.ensureZFSVolume(ctx, vi, node); err != nil {
		r.mark(vi, adoptionv1alpha1.VolumeConditionVolumePlaced, false, "ZFSVolumeFailed", err.Error())
		vi.Status.Phase = adoptionv1alpha1.VolumePhaseFailed
		return ctrl.Result{}, err
	}
	if err := r.ensurePersistentVolume(ctx, vi, node); err != nil {
		r.mark(vi, adoptionv1alpha1.VolumeConditionVolumePlaced, false, "PersistentVolumeFailed", err.Error())
		vi.Status.Phase = adoptionv1alpha1.VolumePhaseFailed
		return ctrl.Result{}, err
	}

	r.mark(vi, adoptionv1alpha1.VolumeConditionVolumePlaced, true, "Placed",
		"ZFSVolume + PV name "+node)
	r.mark(vi, adoptionv1alpha1.VolumeConditionReady, true, "Placed", "the claim can bind")
	vi.Status.Phase = adoptionv1alpha1.VolumePhasePlaced
	return ctrl.Result{}, nil
}

// electNode picks the node that will serve the volume. STICKY: an already-elected node that is still
// eligible wins, because moving the election would strand the dataset on the previous node — the
// data does not follow the choice.
//
// Among candidates the oldest by creationTimestamp wins: deterministic, monotonic, and owned by the
// apiserver rather than by us. When the pet label lands (a Machine label CAPI propagates to the Node)
// the election should prefer it — the pet is the role that SURVIVES a remediation, so the choice
// would follow the role instead of designating the oldest survivor.
func (r *VolumeIntentionReconciler) electNode(
	ctx context.Context, vi *adoptionv1alpha1.VolumeIntention,
) (string, error) {
	var nodes corev1.NodeList
	if err := r.List(ctx, &nodes, client.HasLabels{nodeRoleLabelPrefix + vi.Spec.NodeRole}); err != nil {
		return "", fmt.Errorf("listing %s nodes: %w", vi.Spec.NodeRole, err)
	}
	eligible := make([]corev1.Node, 0, len(nodes.Items))
	for i := range nodes.Items {
		if nodeReady(&nodes.Items[i]) {
			eligible = append(eligible, nodes.Items[i])
		}
	}
	if len(eligible) == 0 {
		return "", nil
	}
	for i := range eligible {
		if eligible[i].Name == vi.Status.Node {
			return vi.Status.Node, nil
		}
	}
	sort.Slice(eligible, func(a, b int) bool {
		at, bt := eligible[a].CreationTimestamp, eligible[b].CreationTimestamp
		if at.Equal(&bt) {
			return eligible[a].Name < eligible[b].Name
		}
		return at.Before(&bt)
	})
	return eligible[0].Name, nil
}

func nodeReady(node *corev1.Node) bool {
	for i := range node.Status.Conditions {
		if node.Status.Conditions[i].Type == corev1.NodeReady {
			return node.Status.Conditions[i].Status == corev1.ConditionTrue
		}
	}
	return false
}

// ensureZFSVolume creates the CR that makes openebs ADOPT the pre-created dataset. ownerNodeID must
// equal the ZFSNode object's name, which openebs names after the node — a node LABEL cannot serve
// this half, which is why the election must yield a NAME.
func (r *VolumeIntentionReconciler) ensureZFSVolume(
	ctx context.Context, vi *adoptionv1alpha1.VolumeIntention, node string,
) error {
	bytes, err := capacityBytes(vi.Spec.Capacity)
	if err != nil {
		return err
	}
	obj := newObj(gvkZFSVolume, vi.Spec.Dataset, openebsNamespace)
	obj.Object["spec"] = map[string]any{
		"ownerNodeID": node,
		"poolName":    vi.Spec.Pool,
		"capacity":    bytes,
		"volumeType":  "DATASET",
		"fsType":      "zfs",
	}
	if err := ensure(ctx, r.Client, obj); err != nil {
		return fmt.Errorf("ensuring ZFSVolume %s: %w", vi.Spec.Dataset, err)
	}
	return r.assertOwner(ctx, gvkZFSVolume, vi.Spec.Dataset, openebsNamespace, node)
}

// assertOwner refuses to silently disagree with an existing ZFSVolume. `ensure` leaves an existing
// object untouched, so a pre-existing CR naming ANOTHER node means the volume is already placed
// elsewhere — re-stamping would not move the data, it would only make the two halves lie. Surface it
// instead; the resolution is an operator gesture.
func (r *VolumeIntentionReconciler) assertOwner(
	ctx context.Context, gvk schema.GroupVersionKind, name, namespace, node string,
) error {
	existing := newObj(gvk, name, namespace)
	if err := r.Get(ctx, types.NamespacedName{Namespace: namespace, Name: name}, existing); err != nil {
		return err
	}
	spec, ok := existing.Object["spec"].(map[string]any)
	if !ok {
		return nil
	}
	if owner, ok := spec["ownerNodeID"].(string); ok && owner != node {
		return fmt.Errorf(
			"ZFSVolume %s already names node %q but %q was elected — the dataset lives on %q; "+
				"delete the ZFSVolume and the PV to re-place it", name, owner, node, owner)
	}
	return nil
}

// ensurePersistentVolume creates the static PV bound to the ZFSVolume by volumeHandle. Retain, and
// pre-bound to the claim so nothing else can take it.
func (r *VolumeIntentionReconciler) ensurePersistentVolume(
	ctx context.Context, vi *adoptionv1alpha1.VolumeIntention, node string,
) error {
	capacity, err := resource.ParseQuantity(vi.Spec.Capacity)
	if err != nil {
		return fmt.Errorf("parsing capacity %q: %w", vi.Spec.Capacity, err)
	}
	volumeMode := corev1.PersistentVolumeFilesystem
	pv := &corev1.PersistentVolume{
		ObjectMeta: metav1.ObjectMeta{Name: vi.Spec.Dataset},
		Spec: corev1.PersistentVolumeSpec{
			Capacity:                      corev1.ResourceList{corev1.ResourceStorage: capacity},
			AccessModes:                   []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce},
			PersistentVolumeReclaimPolicy: corev1.PersistentVolumeReclaimRetain,
			StorageClassName:              vi.Spec.StorageClassName,
			VolumeMode:                    &volumeMode,
			ClaimRef: &corev1.ObjectReference{
				Namespace: vi.Spec.ClaimRef.Namespace,
				Name:      vi.Spec.ClaimRef.Name,
			},
			PersistentVolumeSource: corev1.PersistentVolumeSource{
				CSI: &corev1.CSIPersistentVolumeSource{
					Driver:           zfsCSIDriver,
					FSType:           "zfs",
					VolumeHandle:     vi.Spec.Dataset,
					VolumeAttributes: map[string]string{"openebs.io/poolname": vi.Spec.Pool},
				},
			},
			NodeAffinity: &corev1.VolumeNodeAffinity{
				Required: &corev1.NodeSelector{
					NodeSelectorTerms: []corev1.NodeSelectorTerm{{
						MatchExpressions: []corev1.NodeSelectorRequirement{{
							Key:      openebsNodenameLabel,
							Operator: corev1.NodeSelectorOpIn,
							Values:   []string{node},
						}},
					}},
				},
			},
		},
	}
	// Create-if-absent, never patch: a PV's nodeAffinity is immutable once set, so an existing PV is
	// either already correct or a disagreement to surface (below) — not something to fight.
	err = r.Create(ctx, pv)
	if err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("creating PersistentVolume %s: %w", vi.Spec.Dataset, err)
	}
	var existing corev1.PersistentVolume
	if err := r.Get(ctx, types.NamespacedName{Name: vi.Spec.Dataset}, &existing); err != nil {
		return err
	}
	if placed := pvNode(&existing); placed != "" && placed != node {
		return fmt.Errorf(
			"PersistentVolume %s is already pinned to node %q but %q was elected — "+
				"delete the PV and the ZFSVolume to re-place it", vi.Spec.Dataset, placed, node)
	}
	return nil
}

// pvNode reads back the single node a static PV is pinned to (empty when unpinned).
func pvNode(pv *corev1.PersistentVolume) string {
	affinity := pv.Spec.NodeAffinity
	if affinity == nil || affinity.Required == nil {
		return ""
	}
	for _, term := range affinity.Required.NodeSelectorTerms {
		for _, expr := range term.MatchExpressions {
			if expr.Key == openebsNodenameLabel && len(expr.Values) > 0 {
				return expr.Values[0]
			}
		}
	}
	return ""
}

// capacityBytes renders a Kubernetes quantity as the byte count openebs's ZFSVolume expects (a
// string). Derived from the SAME spec field the PV uses, so the two halves cannot disagree about
// size the way the hand-rendered pair did.
func capacityBytes(capacity string) (string, error) {
	quantity, err := resource.ParseQuantity(capacity)
	if err != nil {
		return "", fmt.Errorf("parsing capacity %q: %w", capacity, err)
	}
	return strconv.FormatInt(quantity.Value(), 10), nil
}

func (r *VolumeIntentionReconciler) mark(
	vi *adoptionv1alpha1.VolumeIntention, condType string, ok bool, reason, message string,
) {
	status := metav1.ConditionFalse
	if ok {
		status = metav1.ConditionTrue
	}
	setCondition(&vi.Status.Conditions, metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		ObservedGeneration: vi.Generation,
		LastTransitionTime: metav1.Now(),
	})
}

// SetupWithManager watches the intents only. A Pending election is unblocked by the requeue rather
// than by a node watch: the wait is bounded by volumeRequeueInterval, and a watch on Node would wake
// every intention on every kubelet heartbeat for a one-off placement.
func (r *VolumeIntentionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.VolumeIntention{}).
		Named("volumeintention").
		Complete(r)
}
