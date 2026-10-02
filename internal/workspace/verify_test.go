package workspace

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// seat builds a fake workspace on disk: {tmp}/seedmatic/{repo}.d/{slot}, each slot a git
// worktree. It returns the layout and the principal's directory.
func seat(t *testing.T, coords ...Coord) (Layout, string) {
	t.Helper()
	root := t.TempDir()
	l := Layout{StoreRoot: root, Org: "seedmatic"}
	for _, c := range coords {
		dir := l.Dir(c)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		// A real linked worktree carries .git as a FILE, not a directory.
		if err := os.WriteFile(filepath.Join(dir, ".git"), []byte("gitdir: /elsewhere\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return l, l.Dir(coords[0])
}

func problemsContain(problems []Problem, check string) bool {
	for _, p := range problems {
		if p.Check == check {
			return true
		}
	}
	return false
}

func TestVerifyHealthy(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	if got := Verify(m, l, principal); len(got) != 0 {
		t.Errorf("expected no problem, got %v", got)
	}
}

// A declared slot that is not on disk would make the registry point at nothing.
func TestVerifyReportsMissingWorktree(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	all := m.All()
	l, principal := seat(t, all[:len(all)-1]...) // leave the last one unmaterialised
	problems := Verify(m, l, principal)
	if !problemsContain(problems, "worktree missing") {
		t.Errorf("expected a missing worktree to be reported, got %v", problems)
	}
}

// A directory that is not a checkout satisfies a naive existence test while resolving to
// nothing a flake ref could read.
func TestVerifyReportsDirectoryThatIsNotAWorktree(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	if err := os.Remove(filepath.Join(l.Dir(Coord{Repo: "ndh", Slot: "develop"}), ".git")); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "not a git worktree") {
		t.Errorf("expected a non-worktree directory to be reported, got %v", problems)
	}
}

// A manifest that disagrees with the disk is a defect, not a default.
func TestVerifyReportsPrincipalMismatch(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, _ := seat(t, m.All()...)
	// Run it from a slot that is not the declared principal.
	problems := Verify(m, l, l.Dir(Coord{Repo: "rke2lab", Slot: "memory"}))
	if !problemsContain(problems, "principal") {
		t.Errorf("expected a principal mismatch to be reported, got %v", problems)
	}
}

// Measured: an include declared by eight environments named a directory that was never
// committed in any revision — and nothing failed, because the lock masks the composition
// until `flox include upgrade`. Only an executed check finds it.
func TestVerifyReportsDanglingInclude(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	writeEnv(t, principal, `[include]
  environments = [
    { dir = "../.flox.d/common" },
  ]
`)
	problems := Verify(m, l, principal)
	if !problemsContain(problems, "dangling include") {
		t.Fatalf("expected a dangling include to be reported, got %v", problems)
	}
	var found string
	for _, p := range problems {
		if p.Check == "dangling include" {
			found = p.Culprit
		}
	}
	if !strings.Contains(found, "common") {
		t.Errorf("the culprit must be named, got %q", found)
	}
}

// An include that resolves is not a problem, and the walk follows it.
func TestVerifyFollowsResolvingIncludes(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)

	shared := filepath.Join(filepath.Dir(l.RepoDir("rke2lab")), "envs", "jdk")
	if err := os.MkdirAll(shared, 0o755); err != nil {
		t.Fatal(err)
	}
	writeEnv(t, principal, `[include]
  environments = [ { dir = "../../envs/jdk" } ]
`)
	// The included env itself includes one that does NOT exist: the walk must reach it.
	writeEnv(t, shared, `[include]
  environments = [ { dir = "../common" } ]
`)
	problems := Verify(m, l, principal)
	if !problemsContain(problems, "dangling include") {
		t.Errorf("expected the walk to recurse into an included env, got %v", problems)
	}
}

// Two environments including each other must not loop for ever.
func TestVerifyIncludeWalkTerminatesOnACycle(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	other := l.Dir(Coord{Repo: "rke2lab", Slot: "memory"})
	writeEnv(t, principal, `[include]
  environments = [ { dir = "../memory" } ]
`)
	writeEnv(t, other, `[include]
  environments = [ { dir = "../develop" } ]
`)
	done := make(chan []Problem, 1)
	go func() { done <- Verify(m, l, principal) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the include walk did not terminate on a cycle")
	}
}

// The override excuses a dangling include and nothing else: a missing worktree or a broken
// folder-0 invariant must still refuse.
func TestOnlyDanglingGatesTheOverride(t *testing.T) {
	dangling := []Problem{{Check: "dangling include", Culprit: "a"}}
	if !OnlyDangling(dangling) {
		t.Error("a dangling include alone should be overridable")
	}
	mixed := append(dangling, Problem{Check: "worktree missing", Culprit: "b"})
	if OnlyDangling(mixed) {
		t.Error("a missing worktree must not be overridable")
	}
	if OnlyDangling(nil) {
		t.Error("no problem is not an override case")
	}
	if !Dangling(mixed) || Dangling(nil) {
		t.Error("Dangling misreports")
	}
}

func writeEnv(t *testing.T, dir, body string) {
	t.Helper()
	envDir := filepath.Join(dir, ".flox", "env")
	if err := os.MkdirAll(envDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(envDir, "manifest.toml"), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}
