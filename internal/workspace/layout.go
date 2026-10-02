package workspace

import (
	"fmt"
	"path/filepath"
	"strings"
)

// repoDirSuffix is the one spelling convention the layout depends on.
const repoDirSuffix = ".d"

// Layout places a worktree at {StoreRoot}/{Org}/{repo}.d/{slot}.
//
// Both roots are DERIVED from the principal worktree's own path and never declared, which is
// what lets one manifest resolve on either seat and inside a devpod.
type Layout struct {
	StoreRoot string
	Org       string
}

// DeriveLayout reads the roots and the principal's coordinate out of the principal worktree's
// own location. The returned Coord is what the disk says; the caller compares it with what the
// manifest declares, because a manifest that disagrees with the disk is a defect.
func DeriveLayout(principalDir string) (Layout, Coord, error) {
	abs, err := filepath.Abs(principalDir)
	if err != nil {
		return Layout{}, Coord{}, err
	}
	slot := filepath.Base(abs)
	repoDir := filepath.Dir(abs)
	repoBase := filepath.Base(repoDir)
	if !strings.HasSuffix(repoBase, repoDirSuffix) || repoBase == repoDirSuffix {
		return Layout{}, Coord{}, fmt.Errorf(
			"%s: parent %q does not look like a repo dir (expected {repo}%s)", abs, repoBase, repoDirSuffix)
	}
	orgDir := filepath.Dir(repoDir)
	return Layout{
		StoreRoot: filepath.Dir(orgDir),
		Org:       filepath.Base(orgDir),
	}, Coord{Repo: strings.TrimSuffix(repoBase, repoDirSuffix), Slot: slot}, nil
}

func (l Layout) RepoDir(repo string) string {
	return filepath.Join(l.StoreRoot, l.Org, repo+repoDirSuffix)
}

func (l Layout) Dir(c Coord) string { return filepath.Join(l.RepoDir(c.Repo), c.Slot) }

// FolderName is the label shown in the editor. It is derived from the coordinate, never
// written by hand: three of the thirteen hand-written labels disagreed with the convention.
func (l Layout) FolderName(c Coord) string {
	return strings.Join([]string{l.Org, c.Repo + repoDirSuffix, c.Slot}, "/")
}

// EditorFile keeps its existing location and name: it sits beside the principal's slot, inside
// the principal's repo dir. Moving it onto the manifest's own branch would add a `../` to every
// path and would blind the session guard, which looks for it exactly here.
func (l Layout) EditorFile(principal Coord) string {
	return filepath.Join(l.RepoDir(principal.Repo), principal.Slot+".code-workspace")
}

// FolderPath is relative to the directory holding the editor file — which is the principal's
// repo dir, one level ABOVE the worktrees. Hence a bare slot for the principal's own
// repository and a `../` hop for any other.
func (l Layout) FolderPath(principal, c Coord) string {
	if c.Repo == principal.Repo {
		return c.Slot
	}
	return strings.Join([]string{"..", c.Repo + repoDirSuffix, c.Slot}, "/")
}
