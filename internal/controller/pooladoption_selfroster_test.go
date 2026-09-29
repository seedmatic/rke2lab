package controller

import (
	"context"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// observedSelfRoster is THREE-valued, and the middle value carries the whole contract: an
// inconclusive reading must not be mistaken for an empty pool. Believing "empty" during a
// cold-start would hand the adopt branch a roster of zero pets, which reads every declared pet
// as absent and tears down its CR-set — the pool would be re-provisioned instead of re-adopted.
// So these cases pin absent / undecidable / present apart, the distinction this codebase has
// repeatedly collapsed.
func node(name, providerID string, controlPlane bool) *corev1.Node {
	n := &corev1.Node{ObjectMeta: metav1.ObjectMeta{Name: name}}
	if controlPlane {
		n.Labels = map[string]string{labelControlPlane: "true"}
	}
	n.Spec.ProviderID = providerID
	return n
}

func TestObservedSelfRoster(t *testing.T) {
	scheme := runtime.NewScheme()
	if err := corev1.AddToScheme(scheme); err != nil {
		t.Fatalf("scheme: %v", err)
	}

	cpSpec := adoptionv1alpha1.PoolAdoptionSpec{Role: adoptionv1alpha1.PoolRoleControlPlane}

	cases := []struct {
		name        string
		spec        adoptionv1alpha1.PoolAdoptionSpec
		nodes       []*corev1.Node
		wantDecided bool
		wantRoster  []string
		why         string
	}{{
		name:        "one control-plane node names the roster",
		spec:        cpSpec,
		nodes:       []*corev1.Node{node("nikopol-mgmt-control-plane-p6xqq", "lxc:///nikopol-mgmt-control-plane-p6xqq", true)},
		wantDecided: true,
		wantRoster:  []string{"nikopol-mgmt-control-plane-p6xqq"},
		why:         "the live shape of a parent-born mgmt plane — the case the seed could never name",
	}, {
		name:        "the name comes from providerID, not from the node name",
		spec:        cpSpec,
		nodes:       []*corev1.Node{node("some-node-object-name", "lxc:///the-instance-name", true)},
		wantDecided: true,
		wantRoster:  []string{"the-instance-name"},
		why:         "providerID is what CAPN matches against; the equality with the node name is measured, not assumed",
	}, {
		name:        "roster is sorted, so it does not churn between reconciles",
		spec:        cpSpec,
		nodes: []*corev1.Node{
			node("c", "lxc:///peer2", true),
			node("a", "lxc:///master", true),
			node("b", "lxc:///peer1", true),
		},
		wantDecided: true,
		wantRoster:  []string{"master", "peer1", "peer2"},
		why:         "an unstable order would rewrite the CR-set on every pass",
	}, {
		name:        "non control-plane nodes are not members of a control-plane pool",
		spec:        cpSpec,
		nodes: []*corev1.Node{
			node("cp", "lxc:///cp", true),
			node("worker", "lxc:///worker", false),
		},
		wantDecided: true,
		wantRoster:  []string{"cp"},
	}, {
		name:        "no nodes yet is INCONCLUSIVE, not an empty pool",
		spec:        cpSpec,
		nodes:       nil,
		wantDecided: false,
		why:         "a cold-start whose node has not registered; the caller must fall back to the seed",
	}, {
		name:        "a control-plane node with no usable providerID voids the WHOLE reading",
		spec:        cpSpec,
		nodes: []*corev1.Node{
			node("named", "lxc:///named", true),
			node("unnamed", "", true),
		},
		wantDecided: false,
		why:         "present-but-undecodable: a PARTIAL roster is worse than none, the missing pet would read as absent",
	}, {
		name:        "a foreign providerID scheme is undecodable too",
		spec:        cpSpec,
		nodes:       []*corev1.Node{node("cp", "incus:///cp", true)},
		wantDecided: false,
		why:         "only lxc:/// yields an instance name we can adopt by",
	}, {
		name:        "a worker pool is not observable from node labels",
		spec:        adoptionv1alpha1.PoolAdoptionSpec{Role: adoptionv1alpha1.PoolRoleWorker},
		nodes:       []*corev1.Node{node("w", "lxc:///w", false)},
		wantDecided: false,
		why:         "two worker pools cannot be told apart by labels — claim only what is observable",
	}}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			objs := make([]runtime.Object, 0, len(tc.nodes))
			for _, n := range tc.nodes {
				objs = append(objs, n)
			}
			r := &PoolAdoptionReconciler{
				Client: fake.NewClientBuilder().WithScheme(scheme).WithRuntimeObjects(objs...).Build(),
			}

			roster, decided, err := r.observedSelfRoster(context.Background(), tc.spec)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if decided != tc.wantDecided {
				t.Fatalf("decided = %v, want %v (%s)", decided, tc.wantDecided, tc.why)
			}
			if !decided {
				if roster != nil {
					t.Errorf("an undecided reading must carry no roster, got %v", roster)
				}
				return
			}
			got := make([]string, 0, len(roster))
			for _, p := range roster {
				got = append(got, p.Name)
			}
			if len(got) != len(tc.wantRoster) {
				t.Fatalf("roster = %v, want %v", got, tc.wantRoster)
			}
			for i := range got {
				if got[i] != tc.wantRoster[i] {
					t.Fatalf("roster = %v, want %v", got, tc.wantRoster)
				}
			}
		})
	}
}
