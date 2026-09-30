package controller

import (
	"context"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// resolveRoster answers the triad's Q2 — WHO NAMES this pool's instances — and since form A it
// answers it from a DECLARED field instead of the presence of a reflection file. These cases pin the
// two properties that were only emergent before: a pet pool can never be greenfielded, and an unset
// nature is refused rather than guessed.
//
// Uses the hand-rolled nodeLister from pooladoption_selfroster_test.go — see the note there for why
// controller-runtime's fake client must not be used in this package.

func petPool(nodes ...string) adoptionv1alpha1.PoolAdoptionSpec {
	return poolSpec(adoptionv1alpha1.PoolNaturePet, nodes...)
}

func cattlePool(nodes ...string) adoptionv1alpha1.PoolAdoptionSpec {
	return poolSpec(adoptionv1alpha1.PoolNatureCattle, nodes...)
}

func poolSpec(nature adoptionv1alpha1.PoolNature, nodes ...string) adoptionv1alpha1.PoolAdoptionSpec {
	pets := make([]adoptionv1alpha1.PetSpec, 0, len(nodes))
	for _, n := range nodes {
		pets = append(pets, adoptionv1alpha1.PetSpec{Name: n})
	}
	return adoptionv1alpha1.PoolAdoptionSpec{
		ClusterName: "bioskop-mgmt",
		Namespace:   "rke2lab-bioskop-mgmt",
		Pool:        "control-node",
		Role:        adoptionv1alpha1.PoolRoleControlPlane,
		Nature:      nature,
		Replicas:    int32(len(nodes)),
		Nodes:       pets,
	}
}

func names(roster []adoptionv1alpha1.PetSpec) []string {
	out := make([]string, 0, len(roster))
	for _, p := range roster {
		out = append(out, p.Name)
	}
	return out
}

func TestPetRosterIsTheDeclarationAndNeverGreenfields(t *testing.T) {
	// Git is nil AND no nodes are listed: a pet pool must consult neither. Its instances were named by
	// a host grow before any controller ran, so "not found" means WAIT — provisioning would duplicate
	// a node that already has a name.
	r := &PoolAdoptionReconciler{Client: nodeLister{}, SelfCluster: "bioskop-mgmt"}

	roster, greenfield, err := r.resolveRoster(context.Background(), petPool("bioskop-mgmt-master"))
	if err != nil {
		t.Fatalf("pet roster must resolve without consulting anything: %v", err)
	}
	if greenfield {
		t.Fatal("a pet pool must NEVER be greenfielded — its instances are already named")
	}
	if got := names(roster); len(got) != 1 || got[0] != "bioskop-mgmt-master" {
		t.Fatalf("pet roster must be the declaration, got %v", got)
	}
}

func TestCattleSelfRosterIsObservedNotDeclared(t *testing.T) {
	// The defect form B fixed: a cattle SELF cluster is named by its provisioner, so the declaration
	// names an instance that will never exist.
	r := &PoolAdoptionReconciler{
		Client: nodeLister{nodes: []corev1.Node{
			node("nikopol-mgmt-control-plane-9f5kb", "lxc:///nikopol-mgmt-control-plane-9f5kb", true),
		}},
		SelfCluster: "bioskop-mgmt",
	}

	roster, greenfield, err := r.resolveRoster(context.Background(), cattlePool("bioskop-mgmt-master"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if greenfield {
		t.Fatal("a running SELF cluster must never be greenfielded")
	}
	if got := names(roster); len(got) != 1 || got[0] != "nikopol-mgmt-control-plane-9f5kb" {
		t.Fatalf("cattle SELF roster must be OBSERVED, got %v", got)
	}
}

func TestCattleSelfFallsBackToTheSeedWithoutGreenfielding(t *testing.T) {
	// Inconclusive is not empty: a cold start whose Nodes have not registered yet. Falling back keeps
	// the pool sized; greenfielding would add a second control node beside the one we stand on.
	r := &PoolAdoptionReconciler{Client: nodeLister{}, SelfCluster: "bioskop-mgmt"}

	roster, greenfield, err := r.resolveRoster(context.Background(), cattlePool("bioskop-mgmt-master"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if greenfield {
		t.Fatal("an inconclusive observation must NOT greenfield the self cluster")
	}
	if got := names(roster); len(got) != 1 || got[0] != "bioskop-mgmt-master" {
		t.Fatalf("expected the seed as fallback, got %v", got)
	}
}

func TestAnUnsetNatureIsRefusedAndNamesTheRemedy(t *testing.T) {
	// Three-valued: unset is a branch rendered before the field existed, not a pool without a nature.
	// Guessing is exactly what reading Q2 off a file's presence did.
	r := &PoolAdoptionReconciler{Client: nodeLister{}, SelfCluster: "bioskop-mgmt"}

	_, _, err := r.resolveRoster(context.Background(), poolSpec("", "bioskop-mgmt-master"))
	if err == nil {
		t.Fatal("an unset nature must be refused, not guessed")
	}
	if !strings.Contains(err.Error(), "re-render from the HOST once") {
		t.Fatalf("the refusal must name the remedy, got: %v", err)
	}
}
