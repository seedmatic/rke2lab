package controller

import (
	"context"
	"errors"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"sigs.k8s.io/controller-runtime/pkg/client"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// secretGetter answers exactly one call — Get of a Secret — for the same reason nodeLister is
// hand-rolled: the fake client would bump go.mod and break the flake's vendorHash.
type secretGetter struct {
	client.Client // never called; only Get is implemented
	secrets       map[string]corev1.Secret
	err           error
}

func (c secretGetter) Get(_ context.Context, key client.ObjectKey, obj client.Object, _ ...client.GetOption) error {
	if c.err != nil {
		return c.err
	}
	s, ok := c.secrets[key.Name]
	if !ok {
		return apierrors.NewNotFound(schema.GroupResource{Resource: "secrets"}, key.Name)
	}
	*obj.(*corev1.Secret) = s
	return nil
}

func secretWith(data map[string]string) corev1.Secret {
	s := corev1.Secret{Data: map[string][]byte{}}
	for k, v := range data {
		s.Data[k] = []byte(v)
	}
	return s
}

func readyMaterial(cluster string) map[string]corev1.Secret {
	ca := map[string]string{corev1.TLSCertKey: "cert", corev1.TLSPrivateKeyKey: "secret-value"}
	return map[string]corev1.Secret{
		cluster + "-ca":               secretWith(ca),
		cluster + "-cca":              secretWith(ca),
		cluster + "-etcd":             secretWith(ca),
		cluster + "-peer-etcd":        secretWith(ca),
		cluster + "-server-manifests": secretWith(map[string]string{serverManifestsSecretKey: "bundle"}),
	}
}

// The gate used to ask only whether each Secret EXISTS. A render that cannot read its cellar back
// emits the material Secrets with empty values, and an existence check waved them through to
// CAPRKE2 — which then mints a fresh CA the adopted apiserver rejects. Present-but-empty must wait,
// exactly like absent, and the reason names the key, never its value.
func TestMaterialMissing(t *testing.T) {
	const cluster = "bioskop-wrkld"
	spec := adoptionv1alpha1.PoolAdoptionSpec{ClusterName: cluster, Namespace: "rke2lab-" + cluster}

	with := func(edit func(map[string]corev1.Secret)) map[string]corev1.Secret {
		m := readyMaterial(cluster)
		edit(m)
		return m
	}

	cases := []struct {
		name    string
		self    string
		secrets map[string]corev1.Secret
		err     error
		want    string
		wantErr bool
	}{
		{name: "every Secret present and populated is ready", secrets: readyMaterial(cluster), want: ""},
		{
			name:    "an absent CA Secret is named",
			secrets: with(func(m map[string]corev1.Secret) { delete(m, cluster+"-etcd") }),
			want:    cluster + "-etcd",
		},
		{
			name: "an EMPTY-valued key is not ready, and its key is named",
			secrets: with(func(m map[string]corev1.Secret) {
				m[cluster+"-ca"] = secretWith(map[string]string{corev1.TLSCertKey: "cert", corev1.TLSPrivateKeyKey: ""})
			}),
			want: cluster + "-ca (key tls.key missing or empty)",
		},
		{
			name: "a Secret with no data at all is not ready",
			secrets: with(func(m map[string]corev1.Secret) {
				m[cluster+"-peer-etcd"] = corev1.Secret{}
			}),
			want: cluster + "-peer-etcd (key tls.crt missing or empty)",
		},
		{
			name: "a managed cluster's empty bootstrap bundle is not ready",
			secrets: with(func(m map[string]corev1.Secret) {
				m[cluster+"-server-manifests"] = secretWith(map[string]string{serverManifestsSecretKey: ""})
			}),
			want: cluster + "-server-manifests (key rke2lab-bootstrap.yaml missing or empty)",
		},
		{
			name: "the SELF cluster needs no bundle Secret — the operator's grow poses it",
			self: cluster,
			secrets: with(func(m map[string]corev1.Secret) {
				delete(m, cluster+"-server-manifests")
			}),
			want: "",
		},
		{name: "a failed read is an error, not a verdict", err: errors.New("apiserver down"), wantErr: true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := &PoolAdoptionReconciler{
				Client:      secretGetter{secrets: tc.secrets, err: tc.err},
				SelfCluster: tc.self,
			}
			got, err := r.materialMissing(context.Background(), spec)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("want an error, got %q", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tc.want {
				t.Fatalf("got %q, want %q", got, tc.want)
			}
			if strings.Contains(got, "secret-value") {
				t.Fatalf("the reason quotes a value: %q", got)
			}
		})
	}
}

// The identity gate (ClusterAdoption) and the CA gate share secretGap: CAPN needs every key
// rke2lab renders into the identity Secret, and a blank one must hold the CR-set back.
func TestSecretGapIncusIdentity(t *testing.T) {
	full := map[string]string{"server": "https://nixos.bioskop:8443", "client-crt": "c", "client-key": "k", "project": "rke2lab"}
	s := secretWith(full)
	if gap := secretGap(&s, incusIdentityKeys); gap != "" {
		t.Fatalf("a full identity reports gap %q", gap)
	}
	for _, key := range incusIdentityKeys {
		emptied := map[string]string{}
		for k, v := range full {
			emptied[k] = v
		}
		emptied[key] = ""
		s := secretWith(emptied)
		if gap := secretGap(&s, incusIdentityKeys); gap != key {
			t.Fatalf("emptying %q reports gap %q", key, gap)
		}
	}
}
