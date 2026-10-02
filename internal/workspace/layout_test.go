package workspace

import "testing"

func TestDeriveLayout(t *testing.T) {
	l, c, err := DeriveLayout("/Volumes/git-worktree-store/seedmatic/rke2lab.d/develop")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if l.StoreRoot != "/Volumes/git-worktree-store" {
		t.Errorf("storeRoot = %q", l.StoreRoot)
	}
	if l.Org != "seedmatic" {
		t.Errorf("org = %q", l.Org)
	}
	if c != (Coord{Repo: "rke2lab", Slot: "develop"}) {
		t.Errorf("coord = %v", c)
	}
}

// The slot segment is NOT the branch: measured, the develop slot carries a feature branch and
// the workspace slot carries another. Derivation must read the slot and say nothing about the
// branch.
func TestDeriveLayoutReadsTheSlotNotTheBranch(t *testing.T) {
	_, c, err := DeriveLayout("/store/seedmatic/rke2lab.d/workspace")
	if err != nil {
		t.Fatal(err)
	}
	if c.Slot != "workspace" {
		t.Errorf("slot = %q, want the directory segment", c.Slot)
	}
}

func TestDeriveLayoutRefusesNonRepoParent(t *testing.T) {
	for _, dir := range []string{"/store/seedmatic/rke2lab/develop", "/store/seedmatic/.d/develop"} {
		if _, _, err := DeriveLayout(dir); err == nil {
			t.Errorf("%s: expected a parent without the repo-dir shape to be refused", dir)
		}
	}
}

// Measured: three of thirteen hand-written labels disagreed with the convention — two carried
// `.git`, one carried no suffix at all. A derived label cannot disagree.
func TestFolderNameIsDerived(t *testing.T) {
	l := Layout{StoreRoot: "/store", Org: "seedmatic"}
	cases := map[Coord]string{
		{Repo: "rke2lab", Slot: "develop"}:         "seedmatic/rke2lab.d/develop",
		{Repo: "flox-nri-plugin", Slot: "develop"}: "seedmatic/flox-nri-plugin.d/develop",
		{Repo: "ndh", Slot: "develop"}:             "seedmatic/ndh.d/develop",
	}
	for c, want := range cases {
		if got := l.FolderName(c); got != want {
			t.Errorf("FolderName(%v) = %q, want %q", c, got, want)
		}
	}
}

// Folder paths are relative to the editor file's own directory, which is one level ABOVE the
// worktrees — hence a bare slot inside the principal's repository and a hop out for any other.
func TestFolderPath(t *testing.T) {
	l := Layout{StoreRoot: "/store", Org: "seedmatic"}
	p := Coord{Repo: "rke2lab", Slot: "develop"}

	if got := l.FolderPath(p, p); got != "develop" {
		t.Errorf("principal path = %q, want %q", got, "develop")
	}
	if got := l.FolderPath(p, Coord{Repo: "rke2lab", Slot: "memory"}); got != "memory" {
		t.Errorf("same-repo path = %q, want %q", got, "memory")
	}
	if got := l.FolderPath(p, Coord{Repo: "ndh", Slot: "develop"}); got != "../ndh.d/develop" {
		t.Errorf("other-repo path = %q, want %q", got, "../ndh.d/develop")
	}
}

// The editor file keeps its existing location: beside the principal's slot, inside the repo
// dir. Moving it would add a `../` to every path and blind the session guard, which looks for
// it exactly there.
func TestEditorFileKeepsItsLocation(t *testing.T) {
	l := Layout{StoreRoot: "/store", Org: "seedmatic"}
	want := "/store/seedmatic/rke2lab.d/develop.code-workspace"
	if got := l.EditorFile(Coord{Repo: "rke2lab", Slot: "develop"}); got != want {
		t.Errorf("EditorFile = %q, want %q", got, want)
	}
}

func TestDirUsesTheLayout(t *testing.T) {
	l := Layout{StoreRoot: "/store", Org: "seedmatic"}
	want := "/store/seedmatic/fleet.d/main"
	if got := l.Dir(Coord{Repo: "fleet", Slot: "main"}); got != want {
		t.Errorf("Dir = %q, want %q", got, want)
	}
}
