package controller

import (
	"context"
	"errors"

	adoptionv1alpha1 "github.com/seedmatic/seed-incluster/api/v1alpha1"
)

// ReflectorGit is the cluster→git write surface of the reflector: it commits a PoolReflection onto
// the managing branch manifests/<selfCluster> via go-git (fetch → commit → push, App-token,
// retry-on-conflict, NEVER force-push, staging ONLY the PoolReflection — see the spec's
// <<reflector-no-merge>>). The render preserves the file across its wholesale regeneration (the
// carve-out / rehydrate allow-list); the PoolAdoption reconciler reads it back DIRECTLY from git for
// the adopt-vs-greenfield decision (not etcd — immune to Flux apply-ordering).
//
// STUB — the go-git implementation is the next build step (b). Until then the manager wires Git=nil,
// so the reflector runs OBSERVE-ONLY (it derives + logs the roster, writes nothing).
type ReflectorGit struct {
	// RepoURL is the git remote of the managing branch (e.g. https://github.com/seedmatic/rke2lab.git).
	RepoURL string
	// TokenSecretName is the App-token Secret (github-token, rke2lab-system) the push authenticates with.
	TokenSecretName string
}

// errReflectorGitNotImplemented is returned until the go-git write lands (b), so wiring Git before it
// exists fails LOUD rather than silently dropping the reflection.
var errReflectorGitNotImplemented = errors.New("reflector git-write not implemented yet (part b)")

// WriteReflection commits the PoolReflection onto manifests/<selfCluster>.
func (g *ReflectorGit) WriteReflection(ctx context.Context, selfCluster string, reflection *adoptionv1alpha1.PoolReflection) error {
	return errReflectorGitNotImplemented
}
