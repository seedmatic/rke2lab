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
		// A real linked worktree carries .git as a FILE holding `gitdir: <path>`, and that
		// target has to exist for the checkout to be usable.
		gitdir := filepath.Join(root, ".bare", c.Repo, c.Slot)
		if err := os.MkdirAll(gitdir, 0o755); err != nil {
			t.Fatal(err)
		}
		body := []byte("gitdir: " + gitdir + "\n")
		if err := os.WriteFile(filepath.Join(dir, ".git"), body, 0o644); err != nil {
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

// Running from a slot that is not the principal is LEGITIMATE: the roots derive identically
// from any declared worktree, and materialising from the slot that carries the manifest is the
// obvious case. Requiring the principal would make `nix run` fail from its own directory.
func TestVerifyAcceptsAnyDeclaredAnchor(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, _ := seat(t, m.All()...)
	problems := Verify(m, l, l.Dir(Coord{Repo: "rke2lab", Slot: "memory"}))
	if problemsContain(problems, "anchor is outside the declared workspace") {
		t.Errorf("a declared non-principal slot must be a valid anchor, got %v", problems)
	}
}

// Deriving the roots from a directory the manifest never declares would place every worktree
// under an unrelated tree, and every path would be confidently wrong.
func TestVerifyRejectsAnchorOutsideTheWorkspace(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, _ := seat(t, m.All()...)
	stray := l.Dir(Coord{Repo: "stranger", Slot: "main"})
	if err := os.MkdirAll(stray, 0o755); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, stray); !problemsContain(problems, "anchor is outside the declared workspace") {
		t.Errorf("expected a stray anchor to be reported, got %v", problems)
	}
}

// The declared org is compared with the derived one, otherwise the field would be decorative:
// every generated path uses the derived value, so a typo would validate and change nothing.
func TestVerifyReportsOrgMismatch(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	m.Org = "not-the-org-on-disk"
	if problems := Verify(m, l, principal); !problemsContain(problems, "org mismatch") {
		t.Errorf("expected an org mismatch to be reported, got %v", problems)
	}
}

// ★ THE TEST THAT WAS MISSING, and the defect it covers was live in the tree: the shared
// environments ship a COMMENTED template line `#  { dir = "../common" }`. A regex over the raw
// text read it as a declaration and produced three confident false positives, which blocked
// materialisation for a reason that did not exist.
func TestVerifyIgnoresCommentedIncludes(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	writeEnv(t, principal, `# A template, kept as documentation:
# [include]
# environments = [
#     { dir = "../common" }
# ]

[profile]
  common = """
  echo not an include
  """
`)
	if problems := Verify(m, l, principal); len(problems) != 0 {
		t.Errorf("a commented include and a profile key named common are not includes, got %v", problems)
	}
}

// A live include may carry a trailing comment, and the array may span several lines — neither
// is a reason to miss it, and neither is expressible with a line-oriented scan.
func TestVerifyReadsLiveIncludesAcrossLinesAndComments(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	writeEnv(t, principal, `[include]
environments = [
  # the jdk toolchain
  { dir = "../missing-one" }, # trailing comment
  { dir = "../missing-two" },
]
`)
	problems := Verify(m, l, principal)
	if got := len(problems); got != 2 {
		t.Errorf("expected both live includes to be reported, got %d: %v", got, problems)
	}
}

// A manifest this tool cannot parse is a GAP in the assertion, so it is reported rather than
// skipped.
func TestVerifyReportsUnparseableEnvManifest(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	writeEnv(t, principal, "[include\nthis is not toml\n")
	if problems := Verify(m, l, principal); !problemsContain(problems, "env manifest unparseable") {
		t.Errorf("expected an unparseable manifest to be reported, got %v", problems)
	}
}

// A linked worktree whose gitdir target was removed passes a bare existence test while the
// generated git+file: reference fails later, far from here.
func TestVerifyReportsMissingGitdirTarget(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	dir := l.Dir(Coord{Repo: "ndh", Slot: "develop"})
	if err := os.WriteFile(filepath.Join(dir, ".git"), []byte("gitdir: /nowhere/at/all\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "gitdir target missing") {
		t.Errorf("expected a dangling gitdir to be reported, got %v", problems)
	}
}

// The folder-0 invariant is about the file ON DISK. Asserting the freshly computed list would
// be vacuous, since the generator always prepends the principal.
func TestVerifyDetectsReorderedEditorFile(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	reordered := `{"folders":[{"name":"x","path":"memory"},{"name":"y","path":"develop"}]}`
	if err := os.WriteFile(l.EditorFile(m.Principal), []byte(reordered), 0o644); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "folder 0 is not the principal") {
		t.Errorf("expected a reordered editor file to be reported, got %v", problems)
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

// Stripping an absent `gitdir:` prefix would leave the whole line as the target, so a `.git`
// file containing nothing but an existing path would be accepted on that path's existence.
func TestVerifyRejectsGitfileWithoutHeader(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	dir := l.Dir(Coord{Repo: "ndh", Slot: "develop"})
	if err := os.WriteFile(filepath.Join(dir, ".git"), []byte(t.TempDir()+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "gitfile is not a gitdir pointer") {
		t.Errorf("expected a headerless gitfile to be reported, got %v", problems)
	}
}

// A git directory is a directory; a regular file there is an unusable checkout.
func TestVerifyRejectsGitdirTargetThatIsAFile(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	target := filepath.Join(t.TempDir(), "not-a-dir")
	if err := os.WriteFile(target, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	dir := l.Dir(Coord{Repo: "ndh", Slot: "develop"})
	if err := os.WriteFile(filepath.Join(dir, ".git"), []byte("gitdir: "+target+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "gitdir target is not a directory") {
		t.Errorf("expected a file gitdir target to be reported, got %v", problems)
	}
}

// A lexical cycle guard is defeated by a directory symlink pointing at its own parent: the same
// manifest then appears under ever-deeper paths, each of which exists and none of which
// repeats, so the walk recurses until the stack is exhausted.
func TestVerifyIncludeWalkTerminatesOnASymlinkLoop(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)

	envRoot := filepath.Join(t.TempDir(), "envs")
	if err := os.MkdirAll(envRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	// `loop` points back at the directory that contains it.
	if err := os.Symlink(envRoot, filepath.Join(envRoot, "loop")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	writeEnv(t, envRoot, "[include]\nenvironments = [ { dir = \"./loop\" } ]\n")
	writeEnv(t, principal, "[include]\nenvironments = [ { dir = \""+envRoot+"\" } ]\n")

	done := make(chan []Problem, 1)
	go func() { done <- Verify(m, l, principal) }()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("the include walk did not terminate on a symlink loop")
	}
}

// ★ The split that lets the generator repair the drift it exists to replace: the folder-0
// assertion reads the editor file ON DISK, so materialisation must not run it — otherwise a
// reordered file makes the generator refuse instead of rewriting it.
func TestVerifyInputsExcludesTheOnDiskEditorFile(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	l, principal := seat(t, m.All()...)
	reordered := `{"folders":[{"name":"x","path":"memory"},{"name":"y","path":"develop"}]}`
	if err := os.WriteFile(l.EditorFile(m.Principal), []byte(reordered), 0o644); err != nil {
		t.Fatal(err)
	}
	if problems := VerifyInputs(m, l, principal); len(problems) != 0 {
		t.Errorf("inputs are sound, so materialisation must proceed; got %v", problems)
	}
	if problems := Verify(m, l, principal); !problemsContain(problems, "folder 0 is not the principal") {
		t.Errorf("verify must still report the drift, got %v", problems)
	}
}
