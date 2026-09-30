package controller

import (
	"context"
	"fmt"
	"slices"
	"sort"
	"strconv"
	"strings"
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
// +kubebuilder:rbac:groups=cluster.seedmatic.io,resources=nodeimages,verbs=get;list;watch
// +kubebuilder:rbac:groups=cluster.x-k8s.io,resources=machines,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=controlplane.cluster.x-k8s.io,resources=rke2controlplanes,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcmachines,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=infrastructure.cluster.x-k8s.io,resources=lxcmachinetemplates,verbs=get;list;watch;create;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch;create;delete
// +kubebuilder:rbac:groups="",resources=configmaps,verbs=get;list;watch
// Nodes: read-only, and ONLY for the SELF cluster's own roster (observedSelfRoster). A managed
// cluster's nodes are never read from here — that is the reflector's git record, by design.
// +kubebuilder:rbac:groups="",resources=nodes,verbs=get;list;watch

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

	// Guard: the material a provisioned node cannot do without. The BYO-CA Secrets come from
	// seed-master (branch, sops); the bootstrap bundle from the render. CAPRKE2 adopts the LIVE
	// CA from <cluster>-{ca,cca,etcd,peer-etcd}; without them it would generate a fresh CA and the
	// adopted apiserver would reject the minted admin cert. Wait until present.
	missing, err := r.materialMissing(ctx, spec)
	if err != nil {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "Error", err.Error())
		return ctrl.Result{}, err
	}
	if missing != "" {
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "MaterialMissing",
			"waiting for Secret "+missing+" in "+spec.Namespace)
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
	// The node image this pool boots on — the realised artifact seed-master describes as a NodeImage:
	// its fingerprint AND the runtime contract an instance must carry to run it (kernel modules,
	// raw.lxc, security.*). Gated like the config above, and for a sharper reason: the contract used to
	// be RESTATED here as a literal, which let it drift from rke2lab's host-side `node-base` profile
	// and lose xfrm_user, nft_compat and the four xt_* extensions. Reading it from the one object that
	// owns the image makes that divergence impossible rather than merely corrected, so a missing
	// NodeImage must WAIT — never fall back to a guess.
	var image adoptionv1alpha1.NodeImage
	if err := r.Get(ctx,
		types.NamespacedName{Namespace: spec.Namespace, Name: spec.Image.Name}, &image); err != nil {
		if apierrors.IsNotFound(err) {
			r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "ImageMissing",
				"waiting for Flux to apply NodeImage "+spec.Image.Name+" in "+spec.Namespace)
			return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
		}
		r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, false, "Error", err.Error())
		return ctrl.Result{}, err
	}

	r.mark(a, adoptionv1alpha1.PoolConditionMaterialReady, true, "MaterialReady",
		"BYO-CA Secrets + bootstrap bundle + RKE2 config + NodeImage present")

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

	// The DECLARED size, never len(roster). The roster is OBSERVED, so sizing the control plane from
	// it closed a loop on itself: a pool that had run at N was re-declared N for ever, and a
	// one-node PoolReflection survives a cold start in git — which is why bioskop-wrkld declared
	// three pets and stood at one across two cold starts. The count is intent; the roster answers
	// only WHICH NAMES.
	if spec.Replicas < 1 {
		r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error",
			"spec.replicas is unset — re-render from the HOST once to seed it")
		return ctrl.Result{RequeueAfter: 30 * time.Second}, fmt.Errorf(
			"PoolIntention %s/%s declares no replicas — re-render from the HOST once to seed it",
			spec.Namespace, spec.Pool)
	}

	// 1. The pool's control-plane skeleton: LXCMachineTemplate + RKE2ControlPlane (replicas = the
	//    DECLARED count), created paused (the RCP carries the paused annotation DIRECTLY — inert from
	//    birth). The Cluster + LXCCluster are the ClusterAdoption's, not ours.
	for _, obj := range []*unstructured.Unstructured{
		r.lxcMachineTemplateObj(spec, image),
		r.rke2ControlPlaneObj(spec, true, int(spec.Replicas), configFiles),
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
			fmt.Sprintf("greenfield: no reflection — CAPRKE2 provisioning %d control-node(s)", spec.Replicas))
	} else {
		// ADOPT, per pet in the ROSTER: for a pet CAPRKE2 does NOT already own, pre-create the owned
		// Machine + concrete LXCMachine(providerID) + bootstrap sentinel, so CAPRKE2 counts it as its
		// replica (no re-bootstrap) and CAPN ADOPTS the running instance — that is how a cold-start
		// re-adopts the survivors by providerID. A pet CAPRKE2 already owns is left strictly alone; we
		// only read CAPN's verdict off the LXCMachine its Machine points at. We never probe Incus.
		//
		// Presence is probed BEFORE anything is ensured: a reflection whose pets are ALL gone is void
		// (below), and ensuring its CR-set first would re-create the very Machines that keep it alive.
		type petState struct {
			name      string
			infraName string
			adopted   bool
		}
		states := make([]petState, 0, len(roster))
		absent, pending := 0, 0
		for _, pet := range roster {
			infraName, adopted, gerr := r.petInfraName(ctx, spec, pet.Name)
			if gerr != nil {
				r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error", "infra ref "+pet.Name+": "+gerr.Error())
				return ctrl.Result{}, gerr
			}
			states = append(states, petState{name: pet.Name, infraName: infraName, adopted: adopted})
			isPresent, decided, perr := r.lxcMachinePresence(ctx, spec, infraName)
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

		// A roster already voided stays void until reality moves: the reflector rewrites the reflection
		// (roster differs) or a pet turns up present. Both clear the memory.
		rosterNames := make([]string, 0, len(states))
		for _, st := range states {
			rosterNames = append(rosterNames, st.name)
		}
		sort.Strings(rosterNames)
		// len>0 guards the degenerate empty roster, where slices.Equal(nil, []) would read as "void".
		stillVoid := len(rosterNames) > 0 && present == 0 && slices.Equal(a.Status.VoidedPets, rosterNames)
		if !stillVoid {
			a.Status.VoidedPets = nil
		}

		switch {
		case stillVoid:
			// The CR-set is already gone, so the pets now read UNDECIDED rather than absent — the same
			// reading as a cold-start. Without this memory the adopt branch below would faithfully
			// re-mint the phantoms we just dropped, the reflector would observe BOTH them and CAPRKE2's
			// own Machine, and the reflected roster would grow to two pets — turning the total loss into
			// a PARTIAL one, which the void case deliberately refuses to act on. Deadlock again, worse.
			r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodesAbsent",
				fmt.Sprintf("reflection void (%d pet(s) dropped) — awaiting CAPRKE2 + the reflector's rewrite", len(rosterNames)))

		case len(roster) > 0 && absent == len(roster):
			// VOID reflection: every reflected pet's instance is gone, so there is no survivor for a
			// greenfield to race — the guard below has nothing left to protect. Holding here instead is a
			// DEADLOCK, and a self-sustaining one: the reflection names the pets, the adopt branch
			// re-creates their Machines, and the reflector observes those Machines and re-writes the same
			// reflection. Not one term of that cycle is anchored in the instances. So drop the pets we no
			// longer believe in and let CAPRKE2 provision; the reflector then overwrites the reflection
			// from the emergent roster. (Dropping the Machines is what un-satisfies CAPRKE2's replica
			// count — leaving them would keep it counting phantoms and provisioning nothing.)
			for _, st := range states {
				if err := r.deletePetCRSet(ctx, spec, st.name, st.infraName, rcpUID); err != nil {
					r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "Error",
						"voiding "+st.name+": "+err.Error())
					return ctrl.Result{}, err
				}
			}
			a.Status.VoidedPets = rosterNames
			r.mark(a, adoptionv1alpha1.PoolConditionPetsPresent, false, "NodesAbsent",
				fmt.Sprintf("reflection void: all %d reflected pet(s) gone — dropped, CAPRKE2 provisioning", absent))

		default:
			for _, st := range states {
				if st.adopted {
					// CAPRKE2 already owns this pet: its Machine exists and points at an LXCMachine it named
					// itself. Ensuring ours would mint a RIVAL LXCMachine under the pet's name that no Machine
					// references — CAPN skips an un-owned one, so it never reports presence and the pool waits
					// on it forever while the real node runs.
					continue
				}
				for _, obj := range []*unstructured.Unstructured{
					r.bootstrapSecretObj(spec, st.name),
					r.lxcMachineObj(spec, st.name, image),
					r.machineObj(spec, st.name, rcpUID),
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

// resolveRoster decides the mode + roster. The roster of NAMES is always OBSERVED; only the source
// differs, and each source is available exactly where the other is not:
//   - SELF cluster                   → (observed from the LOCAL apiserver, greenfield=false), seed as
//     fallback while the observation is inconclusive. Never greenfielded.
//   - Git nil (reflector disabled)   → (seed roster, greenfield=false): adopt the canonical seed.
//   - reflection PRESENT             → (observed roster, greenfield=false): adopt by observed name.
//   - reflection ABSENT              → (seed roster, greenfield=true): CAPRKE2 provisions; seed sizes the RCP.
//
// A read error propagates (caller HOLDS) — greenfield is never armed on an unconfirmed absence.
func (r *PoolAdoptionReconciler) resolveRoster(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) (roster []adoptionv1alpha1.PetSpec, greenfield bool, err error) {
	// The SELF cluster is never reflected — the reflector cannot reflect its own cold-start — so it is
	// ADOPTED, never greenfielded. But "never greenfield" does NOT imply "the seed names my nodes":
	// that only holds for a plane born of a HOST GROW, where the declaration is what named the
	// instance. A `kind: management` SUB-PLANE is born of its PARENT, greenfield, so CAPRKE2 named it
	// — and hunting the declared name then waits for an instance that will never exist. Measured
	// 2026-09-29: nikopol-mgmt sat at Adopting / present=0 / totalPets=1 in its own view while its
	// parent reported it Adopted, because the seed says `nikopol-mgmt-master` and the node is
	// `nikopol-mgmt-control-plane-p6xqq`.
	//
	// So ask the cluster instead of the declaration. For SELF that is free and authoritative: the
	// controller runs INSIDE the cluster this pool belongs to, so the local apiserver's Nodes ARE the
	// roster — the counterpart of the git reflection used for children.
	// ★ WHO NAMES is now DECLARED, not inferred. It used to be read off the PRESENCE of a
	// PoolReflection file — a runtime accident, where the same pool answered "the declaration" before
	// its first reflection existed and "the provisioner" after, with nothing stating the difference.
	switch spec.Nature {
	case adoptionv1alpha1.PoolNaturePet:
		// A host `grow` posed these instances, so the DECLARATION is the roster and no observation is
		// needed. Decisively, a pet pool can never be greenfielded: its instances were named before
		// any controller ran, so "not found" means WAIT, never "provision another".
		return spec.Nodes, false, nil

	case adoptionv1alpha1.PoolNatureCattle:
		// The provisioner named them, so the roster must be OBSERVED. For the SELF cluster that is free
		// and authoritative — the controller runs INSIDE the cluster this pool belongs to, so the local
		// apiserver's Nodes ARE the roster, the counterpart of the git reflection used for children.
		if r.SelfCluster != "" && spec.ClusterName == r.SelfCluster {
			observed, decided, oerr := r.observedSelfRoster(ctx, spec)
			if oerr != nil {
				return nil, false, oerr
			}
			if decided {
				return observed, false, nil
			}
			// Inconclusive, NOT empty — a cold-start whose Nodes have not registered yet. Fall back to
			// the seed and NEVER greenfield: a running SELF cluster plainly exists, and provisioning
			// here would add a second control node beside the one we are standing on.
			return spec.Nodes, false, nil
		}
		if r.Git == nil {
			return spec.Nodes, false, nil
		}
		reflection, present, err := r.Git.ReadReflection(ctx, r.SelfCluster, spec.ClusterName, spec.Pool)
		if err != nil {
			// Git unreadable ⇒ HOLD. Never greenfield on an UNCONFIRMED absence: the difference between
			// "no reflection" and "could not read" is the difference between provisioning and
			// duplicating.
			return nil, false, err
		}
		if !present {
			return spec.Nodes, true, nil // greenfield — CAPRKE2 provisions spec.Replicas and names them
		}
		return reflection.Spec.Nodes, false, nil

	default:
		// Three-valued, and this is the third: an unset nature is a branch rendered before the field
		// existed, not a pool without one. Guessing is what the presence-of-a-file reading did.
		return nil, false, fmt.Errorf(
			"PoolIntention %s/%s declares no nature (pet|cattle) — re-render from the HOST once to seed it",
			spec.Namespace, spec.Pool)
	}
}

// labelControlPlane marks a control-plane node (rke2 sets it, value "true"). It is what makes a
// control-plane pool's members identifiable from inside the cluster without consulting CAPI.
const labelControlPlane = "node-role.kubernetes.io/control-plane"

// observedSelfRoster reads the SELF cluster's roster from the LOCAL apiserver — the controller runs
// inside the very cluster this pool describes, so its Nodes are the pool's members, observed rather
// than declared.
//
// THREE-VALUED on purpose, and the middle value is the whole point: `decided=false` means the
// observation is INCONCLUSIVE (nothing to read yet), which is NOT "the pool is empty". Believing an
// empty read would wipe a roster during a cold-start; the caller falls back to the seed instead. An
// error is the third value and makes the caller HOLD. Absent, undecidable and broken are three
// different answers — collapsing them is the defect family this code keeps paying for.
//
// The name comes from `spec.providerID` (`lxc:///<instance>`), not from the node's own name: the
// providerID is what CAPN matches an instance against, so it is the authoritative spelling. They
// coincide today (measured 2026-09-29: node `nikopol-mgmt-control-plane-p6xqq` →
// `lxc:///nikopol-mgmt-control-plane-p6xqq`, agreed by the cluster AND by its parent's LXCMachine),
// and reading the field rather than assuming the equality is what keeps that a fact instead of a hope.
func (r *PoolAdoptionReconciler) observedSelfRoster(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec,
) (roster []adoptionv1alpha1.PetSpec, decided bool, err error) {
	// Only a control-plane pool is identifiable from node labels alone. A worker pool would be "every
	// node without the control-plane label", which cannot tell TWO worker pools apart — so rather than
	// guess, report inconclusive and let the seed/reflection answer. Claim only what is observable.
	if spec.Role != adoptionv1alpha1.PoolRoleControlPlane {
		return nil, false, nil
	}

	var nodes corev1.NodeList
	if err := r.List(ctx, &nodes); err != nil {
		return nil, false, fmt.Errorf("list self nodes: %w", err)
	}

	names := make([]string, 0, len(nodes.Items))
	for i := range nodes.Items {
		node := &nodes.Items[i]
		if _, isControlPlane := node.Labels[labelControlPlane]; !isControlPlane {
			continue
		}
		instance, ok := strings.CutPrefix(node.Spec.ProviderID, "lxc:///")
		if !ok || instance == "" {
			// A control-plane node we cannot name: present but undecodable. Refusing the whole reading
			// is deliberate — a PARTIAL roster is worse than none, because the adopt branch would treat
			// the missing pets as absent and tear down their CR-set.
			return nil, false, nil
		}
		names = append(names, instance)
	}
	if len(names) == 0 {
		return nil, false, nil // nothing registered yet — inconclusive, not empty
	}

	sort.Strings(names) // deterministic order, so the roster does not churn between reconciles
	roster = make([]adoptionv1alpha1.PetSpec, 0, len(names))
	for _, n := range names {
		roster = append(roster, adoptionv1alpha1.PetSpec{Name: n})
	}
	return roster, true, nil
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

// The node-side bootstrap bundle: the multi-doc rke2lab-bootstrap.yaml the exploder carves OUT of the
// rendered branch by the NODE_BOOTSTRAP marker (cilium's HelmChartConfig, the Flux operator/instance/
// root, the Secrets Flux needs to pull and decrypt). A host-grown node gets it over devlxd from the
// cellar; a node CAPRKE2 provisions has no grow to pose it, so it arrives as a Secret here and rides a
// File into RKE2's auto-deploy directory. Referenced, never inlined — contentFrom keeps the App key
// and the cluster age identity out of a spec that is itself rendered onto the manager's branch. See
// docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc#second-poser.
const (
	serverManifestsSecretKey = "rke2lab-bootstrap.yaml"
	serverManifestsPath      = "/var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml"
)

func serverManifestsSecretName(clusterName string) string { return clusterName + "-server-manifests" }

// bootstrapFiles is the RKE2ControlPlane's write_files set: the config.yaml.d fragments, preceded by
// the bootstrap bundle WHEN CAPRKE2 is the poser. The SELF cluster is excluded because its node is
// grown by the host, which poses the bundle over devlxd from the cellar — referencing a Secret that
// by design does not exist would be a trap the day that pool scaled. The gate in materialMissing
// carries the same condition, so the reference here is never stamped before its Secret exists.
func (r *PoolAdoptionReconciler) bootstrapFiles(
	spec adoptionv1alpha1.PoolAdoptionSpec, configFiles []any,
) []any {
	if r.SelfCluster != "" && spec.ClusterName == r.SelfCluster {
		return configFiles
	}
	bundle := map[string]any{
		"path":        serverManifestsPath,
		"owner":       "root:root",
		"permissions": "0600",
		"contentFrom": map[string]any{"secret": map[string]any{
			"name": serverManifestsSecretName(spec.ClusterName),
			"key":  serverManifestsSecretKey,
		}},
	}
	return append([]any{bundle}, configFiles...)
}

// materialMissing returns the name of the first required Secret that is absent, or "".
func (r *PoolAdoptionReconciler) materialMissing(ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec) (string, error) {
	names := []string{
		spec.ClusterName + "-ca",
		spec.ClusterName + "-cca",
		spec.ClusterName + "-etcd",
		spec.ClusterName + "-peer-etcd",
	}
	// The bootstrap bundle is required only where CAPRKE2 is the poser. The SELF cluster's node is
	// grown by the HOST, which poses the bundle over devlxd from the cellar — there is no Secret for
	// it and demanding one would hang the management pool forever. Same structural split as
	// resolveRoster's: self is the host's, a managed cluster is CAPRKE2's.
	//
	// For a managed cluster it IS gated, like the BYO-CA and for the same reason — better a pool that
	// honestly waits than one that provisions a node which cannot work. Without the bundle a
	// greenfielded node takes RKE2's default cilium chart, which dials the apiserver at the in-cluster
	// ClusterIP before any CNI exists: it never reaches Ready, and nothing runs there, Flux included.
	if r.SelfCluster == "" || spec.ClusterName != r.SelfCluster {
		names = append(names, serverManifestsSecretName(spec.ClusterName))
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

// petInfraName resolves the pet's infrastructure object THROUGH its Machine's infrastructureRef, and
// reports whether that Machine exists at all. The indirection is the point: only when WE mint the pair
// do the pet, its Machine, its LXCMachine and the instance all share one name. After a greenfield CAPI
// names the Machine and its LXCMachine independently (…-mgqbm owning …-qs5dg) and the reflector
// reflects the MACHINE name, so the pet name does NOT name the LXCMachine that carries CAPN's verdict.
func (r *PoolAdoptionReconciler) petInfraName(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec, petName string,
) (infraName string, machineExists bool, err error) {
	m := &unstructured.Unstructured{}
	m.SetGroupVersionKind(gvkMachine)
	if getErr := r.Get(ctx, types.NamespacedName{Namespace: spec.Namespace, Name: petName}, m); getErr != nil {
		if apierrors.IsNotFound(getErr) {
			return petName, false, nil
		}
		return "", false, getErr
	}
	if name, _, _ := unstructured.NestedString(m.Object, "spec", "infrastructureRef", "name"); name != "" {
		return name, true, nil
	}
	return petName, true, nil
}

// deletePetCRSet drops the CR-set behind a pet, Machine FIRST (it is the one CAPRKE2 counts as a
// replica, so it must go for the provisioning to be re-armed). Both LXCMachine names are covered: the
// one the Machine points at, and the pet-named one an earlier pass may have minted. Already-absent is
// success — the set is torn down piecemeal by ownerRef GC in the ordinary case.
func (r *PoolAdoptionReconciler) deletePetCRSet(
	ctx context.Context, spec adoptionv1alpha1.PoolAdoptionSpec, nodeName, infraName string, rcpUID types.UID,
) error {
	objs := []*unstructured.Unstructured{
		r.machineObj(spec, nodeName, rcpUID),
		lxcMachineRef(spec, infraName),
	}
	if infraName != nodeName {
		objs = append(objs, lxcMachineRef(spec, nodeName))
	}
	objs = append(objs, r.bootstrapSecretObj(spec, nodeName))
	for _, obj := range objs {
		if err := r.Delete(ctx, obj); err != nil && !apierrors.IsNotFound(err) {
			return fmt.Errorf("delete %s %s: %w", obj.GetKind(), obj.GetName(), err)
		}
	}
	return nil
}

func (r *PoolAdoptionReconciler) lxcMachineTemplateObj(
	spec adoptionv1alpha1.PoolAdoptionSpec, image adoptionv1alpha1.NodeImage,
) *unstructured.Unstructured {
	obj := newObj(gvkLXCMachineTemplate, controlPlaneName(spec.ClusterName), spec.Namespace)
	obj.Object["spec"] = map[string]any{
		"template": map[string]any{"spec": lxcMachineSpec(image, spec.Devices, spec.Target)},
	}
	return obj
}

func (r *PoolAdoptionReconciler) lxcMachineObj(
	spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string, image adoptionv1alpha1.NodeImage,
) *unstructured.Unstructured {
	obj := lxcMachineRef(spec, nodeName)
	obj.SetLabels(map[string]string{clusterNameLabel: spec.ClusterName})
	body := lxcMachineSpec(image, spec.Devices, spec.Target)
	// providerID = lxc:///<name> → CAPN adopts the existing instance (this pet is present).
	body["providerID"] = "lxc:///" + nodeName
	obj.Object["spec"] = body
	return obj
}

// lxcMachineRef is an LXCMachine addressed by identity alone — the GVK, name and namespace a Delete
// needs. Distinct from lxcMachineObj because the teardown path has no business resolving a NodeImage
// just to build a spec the API server discards.
func lxcMachineRef(spec adoptionv1alpha1.PoolAdoptionSpec, nodeName string) *unstructured.Unstructured {
	return newObj(gvkLXCMachine, nodeName, spec.Namespace)
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
	agentConfig := map[string]any{"airGapped": true}
	// The pool's kubelet node labels, through CAPRKE2's OWN field for them. A CAPN-provisioned node
	// cannot get them the way a host-grown one does: the nixos rke2lab-node-labels oneshot is gated on
	// /var/lib/rke2lab/node.env, which only a host-grown node carries. Measured 2026-09-23 on
	// bioskop-wrkld, where the one missing label was flox.seedmatic.io/enabled — the flox-controller
	// DaemonSet's nodeSelector matched nothing (DESIRED 0), so no flox environment was ever GC-rooted
	// under /nix/var/nix/gcroots/flox-runtime/env, and the NRI plugin then refused EVERY flox-carrier
	// container on the node. headscale, kdns and seed-incluster all stalled on it, 347 restarts deep.
	//
	// Rendered as []any, not []string: the spec goes into an Unstructured, whose DeepCopyJSON accepts
	// only JSON-shaped values and panics on a typed slice.
	if len(spec.NodeLabels) > 0 {
		labels := make([]any, 0, len(spec.NodeLabels))
		for _, label := range spec.NodeLabels {
			labels = append(labels, label)
		}
		agentConfig["nodeLabels"] = labels
	}
	obj.Object["spec"] = map[string]any{
		"replicas":     int64(replicas),
		"version":      spec.RKE2Version,
		"agentConfig":  agentConfig,
		"serverConfig": map[string]any{},
		// kube-vip fronts the control-plane endpoint: the replicas register on the VIP. We do NOT
		// install kube-vip from here — rke2lab's high-availability/kube-vip unit renders the DaemonSet
		// and Flux applies it from the cluster's own branch, which is how the management cluster gets
		// it. So a pool larger than one replica needs that branch reconciling before it scales: the
		// first node initialises rather than registers, a second would have no VIP to register against.
		"registrationMethod":  "address",
		"registrationAddress": spec.ControlPlaneEndpoint.Host,
		// Everything CAPRKE2 write_files before rke2 starts: the config.yaml.d fragments, so a
		// provisioned replica boots with the same per-cluster config (dual-stack CIDRs, VIP tls-san, …)
		// a standalone node installs from the branch — and, where CAPRKE2 is the poser, the node-side
		// bootstrap bundle the host would otherwise pose over devlxd.
		"files": r.bootstrapFiles(spec, configFiles),
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
// DEVICES are posed INLINE, from the list the render publishes on the pool — there are no incus
// profiles any more.  They used to ride "node-base" + "node-<cluster>", two Pulumi resources the HOST
// grow created for clusters it does not otherwise know about, under a profile-name convention derived
// on BOTH sides of the Java/Go boundary.  Inlining them deletes the resources, the convention and the
// provider defect behind them (that resource could CREATE a profile's devices and never CORRECT
// them), and it needs no new authority over Incus: CAPN already creates the instances.
//
// The list is PUBLISHED rather than derived here: the bridge name is a blueprint fact
// (`vmnet-<role>`), and deriving it again in Go would re-create exactly the cross-language
// convention this removes.
//
// CONFIG is set INLINE here (not via the profiles), and it comes from the NodeImage: CAPN applies one
// of its embedded instance profiles (kind/kubeadm/…, internal/static/embed/*.yaml) at the
// INSTANCE-config level, which overrides our profiles — and every one of them sets
// linux.kernel_modules WITH the legacy iptables trio (ip_tables/ip6_tables/iptable_raw) that
// FATAL-modprobes on the nftables-only kernel-6.18 substrate. LXCMachine.spec.config wins over that
// embedded default, so the contract asserted HERE is the one that decides.
//
// ★ Which is exactly why it must be READ, not restated. This body used to carry the module list as a
// literal, and it had drifted from rke2lab's host-side `node-base` profile: missing xfrm_user (without
// which cilium's route reconciler dies on "protocol not supported") and nft_compat + the four xt_*
// extensions (without which 10 of its 34 iptables rules land, host-originated traffic reads as
// world-ipv4, and any pod carrying a policy denies the kubelet's health probes). One object owns the
// image and its contract; both sides now derive from it.
//
// target is the Incus cluster member to create on; empty leaves the key out, which hands placement
// back to Incus — it then picks the member with the fewest instances and breaks ties AT RANDOM. Set
// it. The key is omitted rather than sent empty because CAPN treats "" as "no target given", and an
// explicit empty string in the CR would read like a decision when it is the absence of one.
func lxcMachineSpec(
	image adoptionv1alpha1.NodeImage, devices []string, target string,
) map[string]any {
	spec := map[string]any{
		"instanceType": "container",
		"image":        map[string]any{"fingerprint": image.Spec.Fingerprint},
		"config":       incusConfig(image.Spec.Runtime),
	}
	if len(devices) > 0 {
		spec["devices"] = toAnySlice(devices)
	}
	if target != "" {
		spec["target"] = target
	}
	return spec
}

// toAnySlice widens a string list for the unstructured body, which takes []any.
func toAnySlice(values []string) []any {
	widened := make([]any, 0, len(values))
	for _, value := range values {
		widened = append(widened, value)
	}
	return widened
}

// incusConfig flattens a NodeRuntime onto the Incus instance-config keys it models. Every security
// flag is emitted even when false: instance config is what OVERRIDES CAPN's embedded profile, so a
// declared false has to be SENT to win over that profile's true — an omitted key would silently
// inherit it.
func incusConfig(runtime adoptionv1alpha1.NodeRuntime) map[string]any {
	config := map[string]any{
		"linux.kernel_modules":                    strings.Join(runtime.KernelModules, ","),
		"security.privileged":                     strconv.FormatBool(runtime.Security.Privileged),
		"security.nesting":                        strconv.FormatBool(runtime.Security.Nesting),
		"security.syscalls.intercept.bpf":         strconv.FormatBool(runtime.Security.InterceptBPF),
		"security.syscalls.intercept.bpf.devices": strconv.FormatBool(runtime.Security.InterceptBPFDevices),
	}
	if runtime.RawLXC != "" {
		config["raw.lxc"] = runtime.RawLXC
	}
	return config
}


// SetupWithManager wires the reconciler to PoolAdoption events + the owned pool CR-set's.
func (r *PoolAdoptionReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&adoptionv1alpha1.PoolAdoption{}).
		Named("pooladoption").
		Complete(r)
}
