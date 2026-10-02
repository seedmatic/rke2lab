package workspace

import "testing"

const validManifest = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: rke2lab
    slot: memory
    genre: toolingPath
    why: the memory branch
  - repo: ndh
    slot: develop
    genre: flakeInput
  - repo: rke2lab
    slot: manifests
    genre: publicationBranch
registry:
  - id: ndh
    repo: ndh
    slot: develop
`

func TestParseValid(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if m.Org != "seedmatic" {
		t.Errorf("org = %q", m.Org)
	}
	if m.Principal != (Coord{Repo: "rke2lab", Slot: "develop"}) {
		t.Errorf("principal = %v", m.Principal)
	}
	if got := len(m.Worktrees); got != 3 {
		t.Errorf("worktrees = %d, want 3", got)
	}
}

// An unknown key is the exact class of silent drift the manifest exists to remove, so it must
// not be ignored on the way in.
func TestParseRefusesUnknownField(t *testing.T) {
	const typo = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: ndh
    slot: develop
    genre: flakeInput
    wyh: a typo
`
	if _, err := Parse([]byte(typo)); err == nil {
		t.Fatal("expected a typo'd key to be refused")
	}
}

func TestParseRefusesOtherVersion(t *testing.T) {
	if _, err := Parse([]byte("version: 2\norg: x\nprincipal:\n  repo: a\n  slot: b\n")); err == nil {
		t.Fatal("expected an unsupported version to be refused")
	}
}

// Measured: the hand-maintained editor file held one folder twice, because two edits inserted
// it in different places. A declaration that can express the duplicate would reproduce it.
func TestParseRefusesDuplicateWorktree(t *testing.T) {
	const dup = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: rke2lab
    slot: workspace
    genre: toolingPath
  - repo: rke2lab
    slot: workspace
    genre: toolingPath
`
	if _, err := Parse([]byte(dup)); err == nil {
		t.Fatal("expected a duplicated worktree to be refused")
	}
}

// The principal is the anchor and is declared once. Listing it again among the worktrees is
// the other way the same duplicate could come back.
func TestParseRefusesPrincipalListedTwice(t *testing.T) {
	const again = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: rke2lab
    slot: develop
    genre: toolingPath
`
	if _, err := Parse([]byte(again)); err == nil {
		t.Fatal("expected the principal to be refused among the worktrees")
	}
}

func TestParseRefusesUnknownGenre(t *testing.T) {
	const bad = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: ndh
    slot: develop
    genre: floxInclude
`
	if _, err := Parse([]byte(bad)); err == nil {
		t.Fatal("expected a retired genre to be refused")
	}
}

// A binding to a worktree nobody declared would resolve an indirect ref to a directory the
// workspace never materialises.
func TestParseRefusesBindingToUndeclaredWorktree(t *testing.T) {
	const orphanBinding = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
registry:
  - id: nnh
    repo: nnh
    slot: main
`
	if _, err := Parse([]byte(orphanBinding)); err == nil {
		t.Fatal("expected a binding to an undeclared worktree to be refused")
	}
}

func TestParseRefusesDuplicateRegistryID(t *testing.T) {
	const dup = `
version: 1
org: seedmatic
principal:
  repo: rke2lab
  slot: develop
worktrees:
  - repo: ndh
    slot: develop
    genre: flakeInput
registry:
  - id: ndh
    repo: ndh
    slot: develop
  - id: ndh
    repo: ndh
    slot: develop
`
	if _, err := Parse([]byte(dup)); err == nil {
		t.Fatal("expected a duplicated registry id to be refused")
	}
}

// Being in the window is a consequence of the genre's direction, not a field.
func TestInboundExcludesPublicationBranch(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	for _, w := range m.Inbound() {
		if w.Genre == GenrePublicationBranch {
			t.Errorf("%s is outbound and must not be a folder", w.Coord)
		}
	}
	if got := len(m.Inbound()); got != 2 {
		t.Errorf("inbound = %d, want 2", got)
	}
}

func TestAllIncludesPrincipal(t *testing.T) {
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	all := m.All()
	if len(all) != 4 {
		t.Fatalf("all = %d, want 4", len(all))
	}
	if all[0] != m.Principal {
		t.Errorf("all[0] = %v, want the principal", all[0])
	}
}
