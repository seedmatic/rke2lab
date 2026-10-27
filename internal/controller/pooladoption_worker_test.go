package controller

import (
	"context"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"sigs.k8s.io/controller-runtime/pkg/client"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

func workerSpec() adoptionv1alpha1.PoolAdoptionSpec {
	return adoptionv1alpha1.PoolAdoptionSpec{
		ClusterName: "nikopol-wrkld",
		Namespace:   "rke2lab-nikopol-wrkld",
		Pool:        "ccr",
		Role:        adoptionv1alpha1.PoolRoleWorker,
		Image:       adoptionv1alpha1.ImageRef{Name: "nikopol-wrkld-node-base"},
		Replicas:    1,
		RKE2Version: "v1.33.1+rke2r1",
		NodeLabels:  []string{"project=ccr"},
		Target:      "nikopol",
	}
}

func workerImage() adoptionv1alpha1.NodeImage {
	var image adoptionv1alpha1.NodeImage
	image.Spec.Fingerprint = "f00d"
	return image
}

// A worker pool is a MachineDeployment over an RKE2ConfigTemplate and an LXCMachineTemplate, all named
// for the POOL — not the cluster's one control plane, which a worker pool must never touch or rename.
func TestAWorkerPoolIsAMachineDeploymentNamedForItsPool(t *testing.T) {
	r := &PoolAdoptionReconciler{SelfCluster: "nikopol-mgmt"}
	spec := workerSpec()

	objs := r.workerPoolObjs(spec, workerImage(), nil)

	want := map[string]string{
		gvkMachineDeployment.Kind:  "nikopol-wrkld-ccr",
		gvkRKE2ConfigTemplate.Kind: "nikopol-wrkld-ccr",
		gvkLXCMachineTemplate.Kind: "nikopol-wrkld-ccr",
	}
	got := map[string]string{}
	for _, obj := range objs {
		got[obj.GetKind()] = obj.GetName()
		if obj.GetNamespace() != spec.Namespace {
			t.Errorf("%s in namespace %q, want %q", obj.GetKind(), obj.GetNamespace(), spec.Namespace)
		}
	}
	for kind, name := range want {
		if got[kind] != name {
			t.Errorf("%s named %q, want %q (objects: %v)", kind, got[kind], name, got)
		}
	}
	if _, present := got[gvkRKE2ControlPlane.Kind]; present {
		t.Errorf("a worker pool emitted an RKE2ControlPlane: it must leave the cluster's control plane alone")
	}

	md := findKind(t, objs, gvkMachineDeployment.Kind)
	replicas, _, _ := unstructured.NestedInt64(md.Object, "spec", "replicas")
	if replicas != 1 {
		t.Errorf("MachineDeployment replicas = %d, want the DECLARED 1", replicas)
	}
	clusterName, _, _ := unstructured.NestedString(md.Object, "spec", "clusterName")
	if clusterName != spec.ClusterName {
		t.Errorf("MachineDeployment clusterName = %q, want %q", clusterName, spec.ClusterName)
	}
	for _, path := range [][]string{
		{"spec", "template", "spec", "bootstrap", "configRef", "name"},
		{"spec", "template", "spec", "infrastructureRef", "name"},
	} {
		if ref, _, _ := unstructured.NestedString(md.Object, path...); ref != "nikopol-wrkld-ccr" {
			t.Errorf("MachineDeployment %v = %q, want the pool's templates", path, ref)
		}
	}
}

// The pool's labels reach the kubelet through CAPRKE2's own field, as on the control plane; and the
// LXCMachineTemplate is pinned to the pool's NodeImage and its member, never left to Incus's random pick.
func TestAWorkerPoolCarriesItsLabelsImageAndTarget(t *testing.T) {
	r := &PoolAdoptionReconciler{SelfCluster: "nikopol-mgmt"}
	objs := r.workerPoolObjs(workerSpec(), workerImage(), nil)

	tpl := findKind(t, objs, gvkRKE2ConfigTemplate.Kind)
	labels, _, _ := unstructured.NestedStringSlice(tpl.Object, "spec", "template", "spec", "agentConfig", "nodeLabels")
	if len(labels) != 1 || labels[0] != "project=ccr" {
		t.Errorf("agentConfig.nodeLabels = %v, want [project=ccr]", labels)
	}

	lxc := findKind(t, objs, gvkLXCMachineTemplate.Kind)
	fp, _, _ := unstructured.NestedString(lxc.Object, "spec", "template", "spec", "image", "fingerprint")
	if fp != "f00d" {
		t.Errorf("image fingerprint = %q, want the NodeImage's f00d", fp)
	}
	target, _, _ := unstructured.NestedString(lxc.Object, "spec", "template", "spec", "target")
	if target != "nikopol" {
		t.Errorf("target = %q, want the pool's member nikopol", target)
	}
}

func findKind(t *testing.T, objs []*unstructured.Unstructured, kind string) *unstructured.Unstructured {
	t.Helper()
	for _, obj := range objs {
		if obj.GetKind() == kind {
			return obj
		}
	}
	t.Fatalf("no %s among the pool's objects", kind)
	return nil
}

// poolStore is the smallest client reconcileSteps needs: Get answers from a map keyed by kind/name
// (Secrets, the NodeImage, ensured CAPI objects), List serves the RKE2 config ConfigMaps, and Create
// records what was ensured. Hand-rolled for the same reason as nodeLister: controller-runtime's fake
// client would change go.mod and the flake's vendorHash.
type poolStore struct {
	client.Client // never called beyond Get, List and Create
	objects       map[string]client.Object
	configMaps    []corev1.ConfigMap
	created       []*unstructured.Unstructured
}

func storeKey(kind, name string) string { return kind + "/" + name }

func (s *poolStore) Get(_ context.Context, key client.ObjectKey, obj client.Object, _ ...client.GetOption) error {
	kind := obj.GetObjectKind().GroupVersionKind().Kind
	switch obj.(type) {
	case *corev1.Secret:
		kind = "Secret"
	case *adoptionv1alpha1.NodeImage:
		kind = "NodeImage"
	}
	found, ok := s.objects[storeKey(kind, key.Name)]
	if !ok {
		return apierrors.NewNotFound(schema.GroupResource{Resource: kind}, key.Name)
	}
	switch target := obj.(type) {
	case *corev1.Secret:
		*target = *found.(*corev1.Secret)
	case *adoptionv1alpha1.NodeImage:
		*target = *found.(*adoptionv1alpha1.NodeImage)
	case *unstructured.Unstructured:
		found.(*unstructured.Unstructured).DeepCopyInto(target)
	}
	return nil
}

func (s *poolStore) List(_ context.Context, list client.ObjectList, _ ...client.ListOption) error {
	if cms, ok := list.(*corev1.ConfigMapList); ok {
		cms.Items = s.configMaps
	}
	return nil
}

func (s *poolStore) Create(_ context.Context, obj client.Object, _ ...client.CreateOption) error {
	u := obj.(*unstructured.Unstructured)
	s.created = append(s.created, u)
	s.objects[storeKey(u.GetKind(), u.GetName())] = u
	return nil
}

func adoptionScheme(t *testing.T) *runtime.Scheme {
	t.Helper()
	scheme := runtime.NewScheme()
	if err := adoptionv1alpha1.AddToScheme(scheme); err != nil {
		t.Fatalf("scheme: %v", err)
	}
	return scheme
}

func readyWorkerStore(spec adoptionv1alpha1.PoolAdoptionSpec) *poolStore {
	objects := map[string]client.Object{}
	for name, secret := range readyMaterial(spec.ClusterName) {
		s := secret
		objects[storeKey("Secret", name)] = &s
	}
	image := workerImage()
	objects[storeKey("NodeImage", spec.Image.Name)] = &image
	cm := corev1.ConfigMap{Data: map[string]string{"cluster-cidr": "10.133.0.0/16"}}
	cm.Name = "cidrs.yaml"
	cm.Annotations = map[string]string{rke2ConfigAnnotation: "true"}
	return &poolStore{objects: objects, configMaps: []corev1.ConfigMap{cm}}
}

// The refusal the worker path replaces: a worker pool used to be marked WorkerPoolNotImplemented and
// requeued hourly. Once its material is ready it now ensures its CR-set and reports it created.
func TestAWorkerPoolIsNoLongerRefused(t *testing.T) {
	spec := workerSpec()
	store := readyWorkerStore(spec)
	r := &PoolAdoptionReconciler{Client: store, Scheme: adoptionScheme(t), SelfCluster: "nikopol-mgmt"}
	a := &adoptionv1alpha1.PoolAdoption{Spec: spec}

	if _, err := r.reconcileSteps(context.Background(), a); err != nil {
		t.Fatalf("reconcileSteps: %v", err)
	}
	if reason := conditionReason(a.Status.Conditions, adoptionv1alpha1.PoolConditionCRSetCreated); reason == "WorkerPoolNotImplemented" {
		t.Fatalf("a worker pool is still refused as WorkerPoolNotImplemented")
	}
	if !conditionTrue(a.Status.Conditions, adoptionv1alpha1.PoolConditionCRSetCreated) {
		t.Errorf("CRSetCreated is not true: %v", a.Status.Conditions)
	}
	kinds := map[string]bool{}
	for _, obj := range store.created {
		kinds[obj.GetKind()] = true
	}
	for _, want := range []string{gvkMachineDeployment.Kind, gvkRKE2ConfigTemplate.Kind, gvkLXCMachineTemplate.Kind} {
		if !kinds[want] {
			t.Errorf("%s was not ensured (created: %v)", want, kinds)
		}
	}
	if kinds[gvkRKE2ControlPlane.Kind] {
		t.Errorf("a worker pool ensured an RKE2ControlPlane")
	}
}

// A worker pool waits on its material like a control-plane pool does: it must never stamp a pool whose
// NodeImage Flux has not applied yet.
func TestAWorkerPoolWaitsForItsNodeImage(t *testing.T) {
	spec := workerSpec()
	store := readyWorkerStore(spec)
	delete(store.objects, storeKey("NodeImage", spec.Image.Name))
	r := &PoolAdoptionReconciler{Client: store, Scheme: adoptionScheme(t), SelfCluster: "nikopol-mgmt"}
	a := &adoptionv1alpha1.PoolAdoption{Spec: spec}

	if _, err := r.reconcileSteps(context.Background(), a); err != nil {
		t.Fatalf("reconcileSteps: %v", err)
	}
	if reason := conditionReason(a.Status.Conditions, adoptionv1alpha1.PoolConditionMaterialReady); reason != "ImageMissing" {
		t.Errorf("MaterialReady reason = %q, want ImageMissing", reason)
	}
	if len(store.created) != 0 {
		t.Errorf("ensured %d objects before the NodeImage existed", len(store.created))
	}
}

// A worker node joins the cluster as an AGENT and runs none of the server-side bootstrap: the bundle
// (Flux, the App key, the cluster age identity) is a control plane's, posed by bootstrapFiles on the
// RKE2ControlPlane only. A worker's RKE2ConfigTemplate carries the config fragments and nothing else, so
// no worker node ever gets <cluster>-server-manifests on its disk.
func TestAWorkerPoolCarriesNoBootstrapBundle(t *testing.T) {
	r := &PoolAdoptionReconciler{SelfCluster: "nikopol-mgmt"}
	fragment := map[string]any{"path": "/etc/rancher/rke2/config.yaml.d/cidrs.yaml", "content": "x"}
	objs := r.workerPoolObjs(workerSpec(), workerImage(), []any{fragment})

	tpl := findKind(t, objs, gvkRKE2ConfigTemplate.Kind)
	files, _, _ := unstructured.NestedSlice(tpl.Object, "spec", "template", "spec", "files")
	for _, raw := range files {
		file, _ := raw.(map[string]any)
		if file["path"] == serverManifestsPath {
			t.Fatalf("a worker's RKE2ConfigTemplate carries the bootstrap bundle %s", serverManifestsPath)
		}
		// Nor the cluster's age identity, the other fleet secret a node's disk can receive.
		if path, _ := file["path"].(string); strings.HasSuffix(path, "sops-age.key") {
			t.Fatalf("a worker's RKE2ConfigTemplate carries the cluster age identity %s", path)
		}
		if from, ok := file["contentFrom"].(map[string]any); ok {
			t.Fatalf("a worker's file comes from a Secret: %v", from)
		}
	}
	if len(files) != 1 {
		t.Errorf("files = %v, want the one config fragment", files)
	}
}

// A tenant's pool keeps the fleet off its nodes: its taints reach the kubelet through CAPRKE2's own
// agentConfig.nodeTaints, so only what tolerates them (the tenant's vcluster) lands there.
func TestAWorkerPoolCarriesItsTaints(t *testing.T) {
	r := &PoolAdoptionReconciler{SelfCluster: "nikopol-mgmt"}
	spec := workerSpec()
	spec.NodeTaints = []string{"project=ccr:NoSchedule"}

	tpl := findKind(t, r.workerPoolObjs(spec, workerImage(), nil), gvkRKE2ConfigTemplate.Kind)
	taints, _, _ := unstructured.NestedStringSlice(tpl.Object, "spec", "template", "spec", "agentConfig", "nodeTaints")
	if len(taints) != 1 || taints[0] != "project=ccr:NoSchedule" {
		t.Errorf("agentConfig.nodeTaints = %v, want [project=ccr:NoSchedule]", taints)
	}
}

// The intent carries the taints down to the pool mirror, like the labels beside them.
func TestThePoolIntentionProjectsItsTaints(t *testing.T) {
	pi := &adoptionv1alpha1.PoolIntention{}
	pi.Spec.Role = adoptionv1alpha1.PoolRoleWorker
	pi.Spec.NodeTaints = []string{"project=ccr:NoSchedule"}

	got := specFromPoolIntention(pi).NodeTaints
	if len(got) != 1 || got[0] != "project=ccr:NoSchedule" {
		t.Errorf("PoolAdoption nodeTaints = %v, want the intention's", got)
	}
}

// A control-plane pool without its endpoint or roster (admitted before the CRD's rule) is refused,
// never dereferenced.
func TestAControlPlanePoolWithoutEndpointIsRefused(t *testing.T) {
	spec := workerSpec()
	spec.Role = adoptionv1alpha1.PoolRoleControlPlane
	spec.Nature = adoptionv1alpha1.PoolNatureCattle
	store := readyWorkerStore(spec)
	r := &PoolAdoptionReconciler{Client: store, Scheme: adoptionScheme(t), SelfCluster: "nikopol-mgmt"}
	a := &adoptionv1alpha1.PoolAdoption{Spec: spec}

	_, err := r.reconcileSteps(context.Background(), a)
	if err == nil || !strings.Contains(err.Error(), "no controlPlaneEndpoint") {
		t.Fatalf("reconcileSteps = %v, want the refusal of a pool without endpoint nor roster", err)
	}
	if len(store.created) != 0 {
		t.Errorf("ensured %d objects for a control-plane pool without endpoint", len(store.created))
	}
}
