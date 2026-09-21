package controller

import (
	"context"
	"fmt"
	"sort"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/equality"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/yaml"

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
	// SelfCluster is the cluster this controller runs IN; the reflection lives on manifests/<SelfCluster>.
	SelfCluster string
	// Git reads the pool's reflection (the adopt-vs-greenfield switch). Nil ⇒ the reflector is
	// disabled: adopt from the canonical seed roster (conditional-determinism fallback), never greenfield.
	Git *ReflectorGit
}

// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=pooladoptions/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=machines,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=controlplane.cluster.x-k8s.io,resources=rke2controlplanes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcmachines,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcmachinetemplates,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create;delete
// +kubebuilder:rbac:groups="",resources=configmaps,verbs=get;list;watch

// Reconcile drives one PoolAdoption. Every object is created-if-absent, so a re-reconcile (and a
// cold-start, which wipes etcd and re-creates the CR-set) safely re-adopts the SURVIVING instances
// by deterministic name + providerID. The wrapper ALWAYS persists status afterwards.
func (r *PoolAdoptionReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var adoption adoptionv1alpha1.PoolAdoption
	if err := r.Get(ctx, req.NamespacedName, &adoption); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	before := adoption.Status.DeepCopy()

	result, reconcileErr := r.reconcileSteps(ctx, &adoption)

	adoption.Status.ObservedGeneration = adoption.Generation
	adoption.Status.Phase = r.derivePhase(&adoption, reconcileErr)
	// Stamped INSIDE the guard — see ClusterIntentionReconciler.Reconcile for the full why.
	if !equality.Semantic.DeepEqual(*before, adoption.Status) {
		adoption.Status.LastReconcileTime = metav1.Now()
		if statusErr := r.Status().Update(ctx, &adoption); statusErr != nil {
			log.FromContext(ctx).Error(statusErr, "failed to update PoolAdoption status")
			if reconcileErr == nil {
				reconcileErr = statusErr
			}
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

	// The workload's RKE2 config.yaml.d, bootstrap-injected: seed-incluster reads the visible
	// ConfigMaps rke2lab renders per (cluster × pool) into this namespace and turns each into a
	// CAPRKE2 File on the RKE2ControlPlane — so a provisioned replica gets the same config a
	// standalone node git-fetches via install-rke2-config, with NO git fetch / read token on the
	// node. Gated like the BYO-CA: the config (dual-stack CIDRs, VIP tls-san) is essential, so wait
	// until Flux has applied it rather than provision a mis-configured node. Injected into the RCP
	// ONCE at creation (ensure is create-if-absent), so it must be ready before the RCP is stamped.
	configFiles, err := r.clusterConfigFiles(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if len(configFiles) == 0 {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "ConfigMissing",
			"waiting for Flux to apply the RKE2 config ConfigMaps in "+spec.Namespace)
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, true, "MaterialReady",
		"BYO-CA Secrets + RKE2 config present")

	// Resolve the roster + mode from the reflection (the reflector's git-only record) — the
	// adopt-vs-greenfield switch, read DIRECTLY from git (immune to any Flux timing):
	//   - reflector disabled (Git nil) → ADOPT the canonical seed roster (conditional-determinism
	//     fallback; never greenfield).
	//   - reflection PRESENT → ADOPT the observed roster (pre-create the named Machines by name).
	//   - reflection ABSENT → GREENFIELD (CAPRKE2 provisions replicas; we create NO Machines).
	roster, greenfield, err := r.resolveRoster(ctx, spec)
	if err != nil {
		// Git unreadable ⇒ HOLD, never greenfield on an unconfirmed absence.
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error", "resolve roster: "+err.Error())
		return ctrl.Result{RequeueAfter: 30 * time.Second}, err
	}
	a.Status.TotalPets = int32(len(roster))

	// 1. The pool's control-plane skeleton: LXCMachineTemplate + RKE2ControlPlane (replicas = the
	//    roster size), created paused (the RCP carries the paused annotation DIRECTLY — inert from
	//    birth). The Cluster + LXCCluster are the ClusterAdoption's, not ours.
	for _, obj := range []*unstructured.Unstructured{
		r.lxcMachineTemplateObj(spec),
		r.rke2ControlPlaneObj(spec, true, len(roster), configFiles),
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

	// 3. Materialise per the mode.
	present, allPresent := 0, false
	if greenfield {
		// GREENFIELD: create NO Machines — CAPRKE2 provisions its own `replicas` control-nodes; the
		// reflector then observes the emergent roster and writes the first reflection, so the NEXT
		// reconcile reads it and ADOPTS. NodesAbsent routes derivePhase to Provisioning.
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodesAbsent",
			fmt.Sprintf("greenfield: no reflection — CAPRKE2 provisioning %d control-node(s)", len(roster)))
	} else {
		// ADOPT, per pet in the ROSTER: pre-create the owned Machine + concrete LXCMachine(providerID)
		// + bootstrap sentinel, so CAPRKE2 counts it as its replica (no re-bootstrap) and CAPN ADOPTS
		// the running instance. Idempotent: a post-greenfield steady-state finds CAPRKE2's own Machines
		// already present (no-op); a cold-start re-creates them → CAPN adopts the survivors by
		// providerID. We never probe Incus ourselves — CAPN reports present/absent on the LXCMachine.
		//
		// Presence is probed BEFORE anything is ensured: a reflection whose pets are ALL gone is void
		// (below), and ensuring its CR-set first would re-create the very Machines that keep it alive.
		absent, pending := 0, 0
		for _, pet := range roster {
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

		if len(roster) > 0 && absent == len(roster) {
			// VOID reflection: every reflected pet's instance is gone, so there is no survivor for a
			// greenfield to race — the guard below has nothing left to protect. Holding here instead is a
			// DEADLOCK, and a self-sustaining one: the reflection names the pets, the adopt branch
			// re-creates their Machines, and the reflector observes those Machines and re-writes the same
			// reflection. Not one term of that cycle is anchored in the instances. So drop the pets we no
			// longer believe in and let CAPRKE2 provision; the reflector then overwrites the reflection
			// from the emergent roster. (Dropping the Machines is what un-satisfies CAPRKE2's replica
			// count — leaving them would keep it counting phantoms and provisioning nothing.)
			for _, pet := range roster {
				if err := r.deletePetCRSet(ctx, spec, pet.Name, rcpUID); err != nil {
					r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error",
						"voiding "+pet.Name+": "+err.Error())
					return ctrl.Result{}, err
				}
			}
			r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodesAbsent",
				fmt.Sprintf("reflection void: all %d reflected pet(s) gone — dropped, CAPRKE2 provisioning", absent))
		} else {
			for _, pet := range roster {
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
			}

			allPresent = len(roster) > 0 && present == len(roster)
			switch {
			case allPresent:
				r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, true, "Observed",
					fmt.Sprintf("%d/%d present", present, len(roster)))
			case absent > 0:
				// A PARTIAL loss (some pets absent, others present or pending) — HOLD, never greenfield
				// beside a survivor; that mismatch is CAPI/CAPN's to reconcile. A TOTAL loss is the void
				// case above. NodeMissing → derivePhase Adopting.
				r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodeMissing",
					fmt.Sprintf("%d/%d present, %d absent — holding (adopt, not greenfielding)", present, len(roster), absent))
			default:
				r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "AwaitingInstances",
					fmt.Sprintf("%d/%d present, %d pending", present, len(roster), pending))
			}
		}
	}

	// 4. Unpause the RCP. Adopt: the owned Machines exist → CAPRKE2 counts them (no re-init).
	//    Greenfield: CAPRKE2 provisions its own replicas. The Cluster's paused flag is ClusterAdoption's.
	if err := r.unpauseRCP(ctx, spec); err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionUnpaused, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	r.mark(a, adoptionv1alpha1.PoolConditionUnpaused, true, "Unpaused", "RKE2ControlPlane un-paused")

	log.FromContext(ctx).Info("pool reconciled", "cluster", spec.ClusterName, "pool", spec.Pool,
		"greenfield", greenfield, "present", present, "roster", len(roster))
	if greenfield || !allPresent {
		return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// resolveRoster reads the reflection (the reflector's git-only record) to decide the mode + roster:
//   - Git nil (reflector disabled)  → (seed roster, greenfield=false): adopt the canonical seed.
//   - reflection PRESENT             → (observed roster, greenfield=false): adopt by observed name.
//   - reflection ABSENT              → (seed roster, greenfield=true): CAPRKE2 provisions; seed sizes the RCP.
//
// A git read error propagates (caller HOLDS) — greenfield is never armed on an unconfirmed absence.
func (r *PoolAdoptionReconciler) resolveRoster(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) (roster []adoptionv1alpha1.PetSpec, greenfield bool, err error) {
	// The SELF cluster (the mgmt plane this controller runs on) is CANONICAL — it is never reflected
	// (the reflector skips it: it cannot reflect its own cold-start), so its roster is ALWAYS the
	// seed and it is ADOPTED, never greenfielded. Reading a (never-written) reflection would find it
	// absent → wrongly greenfield the management plane.
	if r.SelfCluster != "" && spec.ClusterName == r.SelfCluster {
		return spec.Nodes, false, nil
	}
	if r.Git == nil {
		return spec.Nodes, false, nil
	}
	reflection, present, err := r.Git.ReadReflection(ctx, r.SelfCluster, spec.ClusterName, spec.Pool)
	if err != nil {
		return nil, false, err
	}
	if !present {
		return spec.Nodes, true, nil // greenfield — the seed sizes the RCP replicas
	}
	return reflection.Spec.Nodes, false, nil
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

// clusterConfigFiles reads the workload's visible RKE2 config ConfigMaps (rendered per (cluster×pool)
// by rke2lab into namespace rke2lab-<cluster>, annotated rke2ConfigAnnotation) and reconstructs each
// into a CAPRKE2 File that writes /etc/rancher/rke2/config.yaml.d/<name>. This is the bootstrap-inject
// delivery: a CAPRKE2-provisioned node gets the same config.yaml.d a standalone node git-fetches via
// install-rke2-config — without any git fetch or read token on the workload node. Sorted by name for
// a deterministic RKE2ControlPlane spec (no reconcile churn).
func (r *PoolAdoptionReconciler) clusterConfigFiles(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec,
) ([]any, error) {
	var cms corev1.ConfigMapList
	if err := r.List(ctx, &cms, client.InNamespace(spec.Namespace)); err != nil {
		return nil, err
	}
	byName := map[string]corev1.ConfigMap{}
	names := make([]string, 0, len(cms.Items))
	for _, cm := range cms.Items {
		if cm.Annotations[rke2ConfigAnnotation] != "true" {
			continue
		}
		byName[cm.Name] = cm
		names = append(names, cm.Name)
	}
	sort.Strings(names)
	files := make([]any, 0, len(names))
	for _, name := range names {
		content, err := reconstructConfigFragment(byName[name].Data)
		if err != nil {
			return nil, fmt.Errorf("config fragment %s: %w", name, err)
		}
		files = append(files, map[string]any{
			"path":        "/etc/rancher/rke2/config.yaml.d/" + name,
			"owner":       "root:root",
			"permissions": "0644",
			"content":     content,
		})
	}
	return files, nil
}

// reconstructConfigFragment turns a ConfigMap's data (key -> a YAML-encoded scalar/list/map) into the
// config.yaml.d file body {key: parsedValue}, mirroring install-rke2-config's
// `(.data) | with_entries(.value |= from_yaml)`. Keys are sorted by the JSON-backed marshaller, so
// the content is deterministic across reconciles.
func reconstructConfigFragment(data map[string]string) (string, error) {
	doc := make(map[string]any, len(data))
	for key, encoded := range data {
		var parsed any
		if err := yaml.Unmarshal([]byte(encoded), &parsed); err != nil {
			return "", fmt.Errorf("key %s: %w", key, err)
		}
		doc[key] = parsed
	}
	out, err := yaml.Marshal(doc)
	if err != nil {
		return "", err
	}
	return string(out), nil
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

// deletePetCRSet drops the CR-set this reconciler pre-created for a pet, Machine FIRST (it is the one
// CAPRKE2 counts as a replica, so it must go for the provisioning to be re-armed). Already-absent is
// success — the set is torn down piecemeal by ownerRef GC in the ordinary case.
func (r *PoolAdoptionReconciler) deletePetCRSet(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string, rcpUID types.UID,
) error {
	for _, obj := range []*unstructured.Unstructured{
		r.machineObj(spec, nodeName, rcpUID),
		r.lxcMachineObj(spec, nodeName),
		r.bootstrapSecretObj(spec, nodeName),
	} {
		if err := r.Delete(ctx, obj); err != nil && !apierrors.IsNotFound(err) {
			return fmt.Errorf("delete %s %s: %w", obj.GetKind(), obj.GetName(), err)
		}
	}
	return nil
}

func (r *PoolAdoptionReconciler) lxcMachineTemplateObj(spec adoptionv1alpha1.PoolAdoptionSpec) *unstructured.Unstructured {
	obj := newObj(gvkLXCMachineTemplate, controlPlaneName(spec.ClusterName), spec.Namespace)
	obj.Object["spec"] = map[string]any{
		"template": map[string]any{"spec": lxcMachineSpec(spec.ClusterName, spec.Image.Fingerprint)},
	}
	return obj
}

func (r *PoolAdoptionReconciler) lxcMachineObj(spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string) *unstructured.Unstructured {
	obj := newObj(gvkLXCMachine, nodeName, spec.Namespace)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	body := lxcMachineSpec(spec.ClusterName, spec.Image.Fingerprint)
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

func (r *PoolAdoptionReconciler) rke2ControlPlaneObj(spec adoptionv1alpha1.PoolAdoptionSpec, paused bool, replicas int, configFiles []any) *unstructured.Unstructured {
	obj := newObj(gvkRKE2ControlPlane, controlPlaneName(spec.ClusterName), spec.Namespace)
	if paused {
		// Inert from birth — no wait for CAPI to propagate the Cluster's paused (closes the race
		// where the RCP would initialize a random-named control plane before the owned Machine).
		obj.SetAnnotations(map[string]string{pausedAnnotation: "true"})
	}
	// The kube-vip RBAC file + the bootstrap-injected config.yaml.d fragments (clusterConfigFiles):
	// CAPRKE2 write_files these before rke2 starts, so a provisioned replica boots with the same
	// per-cluster config (dual-stack CIDRs, VIP tls-san, …) a standalone node installs from the branch.
	files := append([]any{kubeVIPRBACFile()}, configFiles...)
	obj.Object["spec"] = map[string]any{
		"replicas":     int64(replicas),
		"version":      spec.RKE2Version,
		"agentConfig":  map[string]any{"airGapped": true},
		"serverConfig": map[string]any{},
		// kube-vip fronts the control-plane endpoint: the replicas register on the VIP.
		"registrationMethod":  "address",
		"registrationAddress": spec.ControlPlaneEndpoint.Host,
		"preRKE2Commands":     []any{kubeVIPBootstrapCommand(spec.ControlPlaneEndpoint.Host, spec.KubeVIPVersion)},
		"files":               files,
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
// node-base by fingerprint.
//
// DEVICES ride the incus profiles (created at grow by rke2lab's InstanceGrow):
//   - "node-base"      — root disk + kmsg/zfs unix-char + lan0 (the cluster-invariant LAN NIC).
//   - "node-<cluster>" — the per-cluster vmnet0 (dynamic MAC/IP; the vmnet bridge's ipv4.dhcp.ranges
//     hands out an IP — avahi/mDNS is IP-agnostic, no reservation). Order [node-<cluster>, node-base]
//     — incus applies profiles left-to-right, node-base LAST = precedence.
//
// CONFIG is set INLINE here (not via the profiles): CAPN applies one of its embedded instance
// profiles (kind/kubeadm/…, internal/static/embed/*.yaml) at the INSTANCE-config level, which
// overrides our profiles — and every one of them sets linux.kernel_modules WITH the legacy iptables
// trio (ip_tables/ip6_tables/iptable_raw) that FATAL-modprobes on the nftables-only kernel-6.18
// substrate. LXCMachine.spec.config wins over that embedded default, so we re-assert the
// kernel-6.18-safe set (+ the privileged raw.lxc/security our node-base needs) HERE.
func lxcMachineSpec(clusterName, fingerprint string) map[string]any {
	return map[string]any{
		"instanceType": "container",
		"profiles":     []any{nodeProfileName(clusterName), "node-base"},
		"image":        map[string]any{"fingerprint": fingerprint},
		"config": map[string]any{
			"raw.lxc":                                 "lxc.mount.auto = proc:rw sys:rw cgroup:rw\nlxc.apparmor.profile = unconfined\nlxc.cap.drop =",
			"security.privileged":                     "true",
			"security.nesting":                        "true",
			"security.syscalls.intercept.bpf":         "true",
			"security.syscalls.intercept.bpf.devices": "true",
			// The CAPN embedded set MINUS the legacy iptables trio (ip_tables/ip6_tables/iptable_raw)
			// the nftables-only kernel-6.18 substrate dropped — else incus FATAL-modprobes at start.
			"linux.kernel_modules": "ip_vs,ip_vs_rr,ip_vs_wrr,ip_vs_sh,netlink_diag,nf_nat,overlay,br_netfilter,xt_socket",
		},
	}
}

// nodeProfileName is the per-cluster incus profile carrying the node's NICs — the shared naming
// contract with rke2lab's InstanceGrow (which CREATES it): "node-<cluster>".
func nodeProfileName(clusterName string) string {
	return "node-" + clusterName
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
