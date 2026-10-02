package workspace

import (
	"bytes"
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// SupportedVersion is the only manifest version this binary reads. A manifest from the future
// is refused rather than guessed at.
const SupportedVersion = 1

// Genre records why a worktree is in the workspace. Direction decides editor membership: an
// inbound entry is a folder of the window, an outbound one is not. Nothing else is derived
// from it — the genre explains an entry, it does not discover one.
type Genre string

const (
	GenreFlakeInput        Genre = "flakeInput"
	GenreSubtreeUpstream   Genre = "subtreeUpstream"
	GenreToolingPath       Genre = "toolingPath"
	GenrePublicationBranch Genre = "publicationBranch"
)

func (g Genre) Inbound() bool {
	switch g {
	case GenreFlakeInput, GenreSubtreeUpstream, GenreToolingPath:
		return true
	default:
		return false
	}
}

func (g Genre) Known() bool { return g.Inbound() || g == GenrePublicationBranch }

// Coord is the identity of a worktree. Neither half suffices on its own: one repository
// occupies several slots, and the branch a slot carries moves with a stack of pull requests.
type Coord struct {
	Repo string `yaml:"repo"`
	Slot string `yaml:"slot"`
}

func (c Coord) String() string { return c.Repo + "/" + c.Slot }

func (c Coord) complete() bool { return c.Repo != "" && c.Slot != "" }

type Worktree struct {
	Coord `yaml:",inline"`
	Genre Genre  `yaml:"genre"`
	Why   string `yaml:"why,omitempty"`
}

// Binding resolves an indirect flake reference to a local worktree. It is the one fact nothing
// in the tree can answer, and it is exactly a registry entry.
type Binding struct {
	ID    string `yaml:"id"`
	Coord `yaml:",inline"`
	Why   string `yaml:"why,omitempty"`
}

type Manifest struct {
	Version   int        `yaml:"version"`
	Org       string     `yaml:"org"`
	Principal Coord      `yaml:"principal"`
	Worktrees []Worktree `yaml:"worktrees"`
	Registry  []Binding  `yaml:"registry"`
}

func Load(path string) (*Manifest, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	m, err := Parse(b)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return m, nil
}

// Parse refuses unknown fields. A typo in a key is the exact class of silent drift this
// manifest exists to remove, so it must not be ignored on the way in.
func Parse(b []byte) (*Manifest, error) {
	dec := yaml.NewDecoder(bytes.NewReader(b))
	dec.KnownFields(true)
	var m Manifest
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	if err := m.validate(); err != nil {
		return nil, err
	}
	return &m, nil
}

func (m *Manifest) validate() error {
	if m.Version != SupportedVersion {
		return fmt.Errorf("manifest version %d, want %d", m.Version, SupportedVersion)
	}
	if m.Org == "" {
		return fmt.Errorf("org is empty")
	}
	if !m.Principal.complete() {
		return fmt.Errorf("principal needs both repo and slot")
	}

	seen := map[Coord]bool{}
	for i, w := range m.Worktrees {
		if !w.complete() {
			return fmt.Errorf("worktrees[%d]: needs both repo and slot", i)
		}
		if !w.Genre.Known() {
			return fmt.Errorf("worktrees[%d] (%s): unknown genre %q", i, w.Coord, w.Genre)
		}
		// The principal is the anchor and is declared once, above. Listing it again is how a
		// duplicate folder entered the hand-maintained editor file.
		if w.Coord == m.Principal {
			return fmt.Errorf("worktrees[%d]: %s is the principal, already declared", i, w.Coord)
		}
		if seen[w.Coord] {
			return fmt.Errorf("worktrees[%d]: %s declared twice", i, w.Coord)
		}
		seen[w.Coord] = true
	}

	ids := map[string]bool{}
	for i, b := range m.Registry {
		if b.ID == "" {
			return fmt.Errorf("registry[%d]: id is empty", i)
		}
		if ids[b.ID] {
			return fmt.Errorf("registry[%d]: id %q bound twice", i, b.ID)
		}
		ids[b.ID] = true
		if !b.complete() {
			return fmt.Errorf("registry[%d] (%s): needs both repo and slot", i, b.ID)
		}
		// A binding to a worktree nobody declared would resolve to a directory the workspace
		// never materialises.
		if b.Coord != m.Principal && !seen[b.Coord] {
			return fmt.Errorf("registry[%d] (%s): %s is not a declared worktree", i, b.ID, b.Coord)
		}
	}
	return nil
}

// Inbound returns the worktrees that are folders of the editor window, in declaration order.
// The principal is not among them: it is prepended by the caller, because it must stay first.
func (m *Manifest) Inbound() []Worktree {
	var out []Worktree
	for _, w := range m.Worktrees {
		if w.Genre.Inbound() {
			out = append(out, w)
		}
	}
	return out
}

// All returns every declared worktree including the principal, which is what has to exist on
// disk.
func (m *Manifest) All() []Coord {
	out := []Coord{m.Principal}
	for _, w := range m.Worktrees {
		out = append(out, w.Coord)
	}
	return out
}
