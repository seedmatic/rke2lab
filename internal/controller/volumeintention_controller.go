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

	// nodeRoleLabelPrefix + the intention's NodeRole is the label an eligible node must carry — the
	// predicate of BOTH halves: the election lists on it, and the PV's nodeAffinity requires it.
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
// eligible wins — for STABILITY, not for correctness. The old reading ("moving the election would
// strand the dataset on the previous node") was false: the dataset sits in the host's pool, which
// every node of the cluster shares, so adoptOwner can hand it over. Stickiness now only avoids
// churning the owner while the current one is healthy; when it disappears, the re-election below is
// the recovery path rather than a hazard.
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
	return r.adoptOwner(ctx, gvkZFSVolume, vi.Spec.Dataset, openebsNamespace, node)
}

// adoptOwner hands an existing ZFSVolume to the elected node. `ensure` leaves an existing object
// untouched, so a pre-existing CR naming ANOTHER node needs this second pass.
//
// It used to REFUSE, on the premise that "re-stamping would not move the data, it would only make the
// two halves lie". The premise was false: ownerNodeID records which node MOUNTED the dataset, not
// where the bytes are — spec.poolName, written right beside it, is host-scoped. Measured 2026-09-24
// after rolling a control plane: the elected node listed `<pool>/funnel-cert` (160K, intact) that the
// deleted node had created, and patching ownerNodeID alone brought the whole chain back — where the
// refusal's own prescription ("delete the ZFSVolume and the PV") would have destroyed the cert and
// left the funnel to be rebuilt from a backup it never needed.
//
// ownerNodeID stays a node NAME (it references openebs's ZFSNode object, one per node), which is why
// this half is re-stamped rather than widened to the role the way the PV's affinity is.
func (r *VolumeIntentionReconciler) adoptOwner(
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
	owner, ok := spec["ownerNodeID"].(string)
	if !ok || owner == node {
		return nil
	}
	log.FromContext(ctx).Info("re-stamping ZFSVolume onto the elected node",
		"zfsVolume", name, "was", owner, "now", node, "pool", spec["poolName"])
	patch := newObj(gvk, name, namespace)
	patch.Object["spec"] = map[string]any{"ownerNodeID": node}
	if err := r.Patch(ctx, patch, client.Merge); err != nil {
		return fmt.Errorf("re-stamping ZFSVolume %s from %q to %q: %w", name, owner, node, err)
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
			NodeAffinity: roleNodeAffinity(vi.Spec.NodeRole),
		},
	}
	// Create-if-absent, never patch: a PV's nodeAffinity is immutable once set. That used to make a
	// roll unrecoverable, because the affinity NAMED the elected node; requiring the ROLE instead
	// makes the desired affinity identical for every node of the cluster, so there is nothing left to
	// change and immutability costs nothing.
	err = r.Create(ctx, pv)
	if err != nil && !apierrors.IsAlreadyExists(err) {
		return fmt.Errorf("creating PersistentVolume %s: %w", vi.Spec.Dataset, err)
	}
	var existing corev1.PersistentVolume
	if err := r.Get(ctx, types.NamespacedName{Name: vi.Spec.Dataset}, &existing); err != nil {
		return err
	}
	// A PV predating the role-wide affinity still names one node, and immutability means we cannot
	// widen it. Surface it rather than leave the cluster silently unable to survive its next roll —
	// the PV is Retain, so deleting it keeps the dataset.
	if !equality.Semantic.DeepEqual(existing.Spec.NodeAffinity, pv.Spec.NodeAffinity) {
		return fmt.Errorf(
			"PersistentVolume %s carries affinity %v but %v is required — a PV's nodeAffinity is "+
				"immutable, so delete the PV to re-place it (Retain: the dataset survives)",
			vi.Spec.Dataset, existing.Spec.NodeAffinity, pv.Spec.NodeAffinity)
	}
	return nil
}

// roleNodeAffinity requires the node ROLE rather than one node's name — the SAME predicate the
// election lists on (client.HasLabels, i.e. Exists), so the two halves can no longer disagree.
//
// It is sound because every node of a cluster lives on ONE bare-metal and the dataset lives in THAT
// HOST's pool (spec.Pool is `tank/rke2lab/<role>/persist`, host-scoped), which any of its nodes can
// list and mount — measured 2026-09-24 from a freshly-rolled node, on a dataset created by the node
// it replaced. `Exists`, not `In []string{"true"}`: the election tests existence, and rke2 happens to
// value the role label "true" where kubeadm leaves it empty.
//
// The SUBSTRATE permits this outright: mounting one dataset from two nodes at once was measured by
// hand on 2026-09-24 — `findmnt /mnt` inside two different nodes showed the SAME
// `<pool>/funnel-cert`, rw, simultaneously. A cluster never spans bare-metals (a deliberate hard
// constraint; growth is by cluster mesh), so every node of the role always sees the dataset.
//
// ⚠️ What stays unmeasured is narrower: whether openebs's node PLUGIN will publish a ZFSVolume whose
// ownerNodeID names another node — the kernel allowing the mount is not the driver performing it.
// adoptOwner below re-stamps the owner on re-election, which converges; a pod landing elsewhere first
// would retry its mount meanwhile.
func roleNodeAffinity(role string) *corev1.VolumeNodeAffinity {
	return &corev1.VolumeNodeAffinity{
		Required: &corev1.NodeSelector{
			NodeSelectorTerms: []corev1.NodeSelectorTerm{{
				MatchExpressions: []corev1.NodeSelectorRequirement{{
					Key:      nodeRoleLabelPrefix + role,
					Operator: corev1.NodeSelectorOpExists,
				}},
			}},
		},
	}
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
