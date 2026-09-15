package controller

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/go-git/go-billy/v5/memfs"
	"github.com/go-git/go-billy/v5/util"
	git "github.com/go-git/go-git/v5"
	"github.com/go-git/go-git/v5/plumbing"
	"github.com/go-git/go-git/v5/plumbing/object"
	"github.com/go-git/go-git/v5/plumbing/transport"
	githttp "github.com/go-git/go-git/v5/plumbing/transport/http"
	"github.com/go-git/go-git/v5/storage/memory"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/yaml"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// ReflectorGit is the cluster→git write surface of the reflector: it commits a PoolReflection as a
// git-only YAML document onto the managing branch manifests/<selfCluster>, under reflections/. The
// document is NEVER applied to etcd (see the reflector design); the render preserves it across its
// wholesale regeneration via its escape/rehydrate allow-list. go-git BOTH ends (clone + commit) so
// sops-filtered blobs round-trip as identity (go-git runs no smudge/clean) — safe because we stage
// ONLY the reflection document. Never force-push; retry on a non-fast-forward.
type ReflectorGit struct {
	// Client reads the Flux GitRepository (for the repo URL) and the App-token Secret.
	Client client.Client
	// SourceRef is the Flux GitRepository whose spec.url is the managing repo — the in-cluster
	// projection of the manifests SSOT (e.g. flux-system/rke2lab). The reflector DERIVES the repo URL
	// from it, never a hard-coded/injected URL.
	SourceRef types.NamespacedName
	// TokenSecret is the App-token Secret (e.g. rke2lab-system/github-token) authenticating the push.
	TokenSecret types.NamespacedName
	// TokenSecretKey is the data key holding the token (e.g. "token").
	TokenSecretKey string
	// AuthorName / AuthorEmail identify the reflector's commits.
	AuthorName  string
	AuthorEmail string
}

const reflectorMaxRetries = 3

// WriteReflection commits (or updates) the PoolReflection document onto manifests/<selfCluster>. It is
// a no-op when the document is byte-identical to what is already on the branch (no churn commits).
func (g *ReflectorGit) WriteReflection(ctx context.Context, selfCluster string, reflection *adoptionv1alpha1.PoolReflection) error {
	branch := "manifests/" + selfCluster
	relPath := reflectionPath(reflection)
	body, err := yaml.Marshal(reflection)
	if err != nil {
		return fmt.Errorf("marshal PoolReflection: %w", err)
	}
	repoURL, err := g.readRepoURL(ctx)
	if err != nil {
		return err
	}
	token, err := g.readToken(ctx)
	if err != nil {
		return err
	}
	// GitHub App installation tokens authenticate as basic-auth user "x-access-token".
	auth := &githttp.BasicAuth{Username: "x-access-token", Password: token}

	var lastErr error
	for attempt := 0; attempt < reflectorMaxRetries; attempt++ {
		lastErr = g.commitOnce(ctx, repoURL, branch, relPath, body, auth)
		if lastErr == nil {
			return nil
		}
		// A non-fast-forward means a concurrent writer (the render, or another reflect) advanced the
		// branch — re-clone and retry, NEVER force-push.
		if !errors.Is(lastErr, git.ErrNonFastForwardUpdate) {
			return lastErr
		}
	}
	return fmt.Errorf("write reflection %s after %d attempts: %w", relPath, reflectorMaxRetries, lastErr)
}

// commitOnce clones the branch shallowly in-memory, writes the document if changed, commits, and
// pushes. Returns git.ErrNonFastForwardUpdate (wrapped) when the push races another writer.
func (g *ReflectorGit) commitOnce(ctx context.Context, repoURL, branch, relPath string, body []byte, auth transport.AuthMethod) error {
	fs := memfs.New()
	repo, err := git.CloneContext(ctx, memory.NewStorage(), fs, &git.CloneOptions{
		URL:           repoURL,
		Auth:          auth,
		ReferenceName: plumbing.NewBranchReferenceName(branch),
		SingleBranch:  true,
		Depth:         1,
	})
	if err != nil {
		return fmt.Errorf("clone %s: %w", branch, err)
	}
	wt, err := repo.Worktree()
	if err != nil {
		return err
	}

	// No-op if the document is already byte-identical on the branch (avoid churn commits).
	if existing, rerr := util.ReadFile(fs, relPath); rerr == nil && bytes.Equal(existing, body) {
		return nil
	}
	if err := util.WriteFile(fs, relPath, body, 0o644); err != nil {
		return fmt.Errorf("write %s: %w", relPath, err)
	}
	if _, err := wt.Add(relPath); err != nil {
		return fmt.Errorf("stage %s: %w", relPath, err)
	}
	if _, err := wt.Commit("reflect: observed roster "+relPath, &git.CommitOptions{
		Author: &object.Signature{Name: g.AuthorName, Email: g.AuthorEmail, When: time.Now()},
	}); err != nil {
		return fmt.Errorf("commit %s: %w", relPath, err)
	}
	// Default (non-force) push — a non-fast-forward surfaces as git.ErrNonFastForwardUpdate.
	if err := repo.PushContext(ctx, &git.PushOptions{Auth: auth}); err != nil {
		return err
	}
	return nil
}

// readRepoURL derives the managing repo URL from the Flux GitRepository (spec.url) — the in-cluster
// projection of the manifests SSOT, so the reflector never carries a hard-coded/injected URL.
func (g *ReflectorGit) readRepoURL(ctx context.Context) (string, error) {
	gr := &unstructured.Unstructured{}
	gr.SetGroupVersionKind(gvkGitRepository)
	if err := g.Client.Get(ctx, g.SourceRef, gr); err != nil {
		return "", fmt.Errorf("read Flux GitRepository %s: %w", g.SourceRef, err)
	}
	url, found, err := unstructured.NestedString(gr.Object, "spec", "url")
	if err != nil || !found || url == "" {
		return "", fmt.Errorf("Flux GitRepository %s has no spec.url", g.SourceRef)
	}
	return url, nil
}

// readToken reads the App token from the configured Secret.
func (g *ReflectorGit) readToken(ctx context.Context) (string, error) {
	var s corev1.Secret
	if err := g.Client.Get(ctx, g.TokenSecret, &s); err != nil {
		return "", fmt.Errorf("read token Secret %s: %w", g.TokenSecret, err)
	}
	token, ok := s.Data[g.TokenSecretKey]
	if !ok || len(token) == 0 {
		return "", fmt.Errorf("token Secret %s has no key %q", g.TokenSecret, g.TokenSecretKey)
	}
	return string(token), nil
}

// reflectionPath is the deterministic, reflector-owned path of a pool's reflection document on the
// managing branch: reflections/<cluster>-<pool>.yaml. It lives under reflections/ (a subtree the
// render never generates and preserves via its escape allow-list), keeping it disjoint from the
// render's files at the FILE grain.
func reflectionPath(reflection *adoptionv1alpha1.PoolReflection) string {
	return reflectionRelPath(reflection.Spec.ClusterRef, reflection.Spec.Pool)
}

func reflectionRelPath(clusterRef, pool string) string {
	return fmt.Sprintf("reflections/%s-%s.yaml", clusterRef, pool)
}

// ReadReflection reads a pool's reflection document from the managing branch (go-git, read-only) — the
// DECISION source for the PoolAdoption reconciler. present is false (nil error) when the document is
// absent (⇒ greenfield). It reads git DIRECTLY (not etcd), so the switch is immune to any Flux timing.
func (g *ReflectorGit) ReadReflection(ctx context.Context, selfCluster, clusterRef, pool string) (reflection *adoptionv1alpha1.PoolReflection, present bool, err error) {
	branch := "manifests/" + selfCluster
	relPath := reflectionRelPath(clusterRef, pool)
	repoURL, err := g.readRepoURL(ctx)
	if err != nil {
		return nil, false, err
	}
	token, err := g.readToken(ctx)
	if err != nil {
		return nil, false, err
	}
	auth := &githttp.BasicAuth{Username: "x-access-token", Password: token}

	fs := memfs.New()
	if _, err := git.CloneContext(ctx, memory.NewStorage(), fs, &git.CloneOptions{
		URL:           repoURL,
		Auth:          auth,
		ReferenceName: plumbing.NewBranchReferenceName(branch),
		SingleBranch:  true,
		Depth:         1,
	}); err != nil {
		return nil, false, fmt.Errorf("clone %s: %w", branch, err)
	}
	data, rerr := util.ReadFile(fs, relPath)
	if rerr != nil {
		if errors.Is(rerr, os.ErrNotExist) {
			return nil, false, nil // absent ⇒ greenfield
		}
		return nil, false, fmt.Errorf("read %s: %w", relPath, rerr)
	}
	var refl adoptionv1alpha1.PoolReflection
	if err := yaml.Unmarshal(data, &refl); err != nil {
		return nil, false, fmt.Errorf("unmarshal %s: %w", relPath, err)
	}
	return &refl, true, nil
}
