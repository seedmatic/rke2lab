package workspace

import (
	"encoding/json"
	"strings"
	"testing"
)

func testManifest(t *testing.T) (*Manifest, Layout) {
	t.Helper()
	m, err := Parse([]byte(validManifest))
	if err != nil {
		t.Fatal(err)
	}
	return m, Layout{StoreRoot: "/store", Org: "seedmatic"}
}

// Measured: `path:` does not consult git and copies the tree as it is — 2 700 MB for the
// principal against 25 MB, including a directory that changes on every render. The registry
// target must therefore be git+file:.
func TestRegistryTargetsGitNotPath(t *testing.T) {
	m, l := testManifest(t)
	out, err := RegistryJSON(m, l)
	if err != nil {
		t.Fatal(err)
	}
	var got registryFile
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("not valid JSON: %v", err)
	}
	if got.Version != 2 {
		t.Errorf("version = %d, want 2", got.Version)
	}
	if len(got.Flakes) != 1 {
		t.Fatalf("flakes = %d, want 1", len(got.Flakes))
	}
	e := got.Flakes[0]
	if e.From.Type != "indirect" || e.From.ID != "ndh" {
		t.Errorf("from = %+v", e.From)
	}
	if e.To.Type != "git" {
		t.Errorf("to.type = %q, want %q — path: would copy untracked files", e.To.Type, "git")
	}
	if e.To.URL != "file:///store/seedmatic/ndh.d/develop" {
		t.Errorf("to.url = %q", e.To.URL)
	}
}

// A registry refuses relative paths, so the generated file must hold absolute ones. That is
// also why it is never committed.
func TestRegistryPathsAreAbsolute(t *testing.T) {
	m, l := testManifest(t)
	out, err := RegistryJSON(m, l)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "../") {
		t.Errorf("registry carries a relative path:\n%s", out)
	}
}

// The editor file also carries the editor settings — the Claude process wrapper, the Java
// runtime configuration. A generator that dropped them would break the window while appearing
// to succeed.
func TestEditorWorkspacePreservesOtherKeys(t *testing.T) {
	m, l := testManifest(t)
	existing := []byte(`{
  "folders": [{"name": "stale", "path": "stale"}],
  "settings": {"java.import.maven.enabled": true},
  "extensions": {"recommendations": ["one"]}
}`)
	out, err := EditorWorkspace(m, l, existing)
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]json.RawMessage
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatalf("not valid JSON: %v", err)
	}
	if _, ok := doc["settings"]; !ok {
		t.Error("settings were dropped")
	}
	if _, ok := doc["extensions"]; !ok {
		t.Error("extensions were dropped")
	}
	if strings.Contains(string(doc["folders"]), "stale") {
		t.Error("the stale folder list survived")
	}
}

// The editor launches with folder 0 as its working directory and the session config home is
// derived from it, so a reorder breaks session history in silence.
func TestEditorWorkspacePutsPrincipalFirst(t *testing.T) {
	m, l := testManifest(t)
	out, err := EditorWorkspace(m, l, nil)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Folders []editorFolder `json:"folders"`
	}
	if err := json.Unmarshal(out, &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Folders) == 0 {
		t.Fatal("no folders")
	}
	if doc.Folders[0].Path != "develop" {
		t.Errorf("folder 0 = %q, want the principal", doc.Folders[0].Path)
	}
	if doc.Folders[0].Name != "seedmatic/rke2lab.d/develop" {
		t.Errorf("folder 0 label = %q", doc.Folders[0].Name)
	}
}

// An outbound branch is a bus written for a remote consumer, not a place to edit.
func TestEditorWorkspaceOmitsOutbound(t *testing.T) {
	m, l := testManifest(t)
	out, err := EditorWorkspace(m, l, nil)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "manifests") {
		t.Errorf("an outbound worktree reached the window:\n%s", out)
	}
}

// A set cannot hold the same node twice, which is how the generated file is immune to the
// duplicate the hand-maintained one carried.
func TestEditorFoldersHoldNoDuplicate(t *testing.T) {
	m, l := testManifest(t)
	seen := map[string]bool{}
	for _, p := range EditorFolders(m, l) {
		if seen[p] {
			t.Errorf("folder %q listed twice", p)
		}
		seen[p] = true
	}
}

func TestEditorWorkspaceRefusesUnreadableExisting(t *testing.T) {
	m, l := testManifest(t)
	if _, err := EditorWorkspace(m, l, []byte("{not json")); err == nil {
		t.Fatal("expected an unreadable existing file to be refused rather than overwritten")
	}
}
