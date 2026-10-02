package workspace

import (
	"encoding/json"
	"fmt"
)

// nixRegistryVersion is the schema version nix expects in a flake registry.
const nixRegistryVersion = 2

type registryRef struct {
	Type string `json:"type"`
	ID   string `json:"id,omitempty"`
	URL  string `json:"url,omitempty"`
}

type registryEntry struct {
	From registryRef `json:"from"`
	To   registryRef `json:"to"`
}

type registryFile struct {
	Version int             `json:"version"`
	Flakes  []registryEntry `json:"flakes"`
}

// RegistryJSON renders the generated nix flake registry.
//
// The target is git+file:, never path:. Measured: path: does not consult git and copies the
// tree as it is — 2 700 MB for the principal against 25 MB, including a directory that changes
// on every render, so a fresh hash every time. git+file: consults git, reads the seated branch
// by itself, and records a verifiable rev.
//
// The absolute paths are why this file is never committed: a registry refuses relative paths,
// so only the manifest can stay relative.
func RegistryJSON(m *Manifest, l Layout) ([]byte, error) {
	f := registryFile{Version: nixRegistryVersion, Flakes: make([]registryEntry, 0, len(m.Registry))}
	for _, b := range m.Registry {
		f.Flakes = append(f.Flakes, registryEntry{
			From: registryRef{Type: "indirect", ID: b.ID},
			To:   registryRef{Type: "git", URL: "file://" + l.Dir(b.Coord)},
		})
	}
	out, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(out, '\n'), nil
}

type editorFolder struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// EditorWorkspace renders the folders of the editor file.
//
// `existing` is the current file, or nil. Everything in it other than `folders` is PRESERVED:
// the file also carries the editor settings — the Claude process wrapper, the Java runtime
// configuration — and a generator that dropped them would break the window while appearing to
// succeed.
//
// The first folder is the principal. The editor launches with that folder as its working
// directory and the session config home is derived from it, so reordering the list breaks
// session history silently. That is an invariant, not a sort preference.
func EditorWorkspace(m *Manifest, l Layout, existing []byte) ([]byte, error) {
	folders := make([]editorFolder, 0, len(m.Worktrees)+1)
	add := func(c Coord) {
		folders = append(folders, editorFolder{
			Name: l.FolderName(c),
			Path: l.FolderPath(m.Principal, c),
		})
	}
	add(m.Principal)
	for _, w := range m.Inbound() {
		add(w.Coord)
	}

	doc := map[string]json.RawMessage{}
	if len(existing) > 0 {
		if err := json.Unmarshal(existing, &doc); err != nil {
			return nil, fmt.Errorf("existing editor file is not readable JSON: %w", err)
		}
	}
	raw, err := json.Marshal(folders)
	if err != nil {
		return nil, err
	}
	doc["folders"] = raw

	// A map marshals with sorted keys, so the rendering is deterministic and a diff is
	// reviewable.
	out, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(out, '\n'), nil
}

// EditorFolders is the folder list as it would be written, for assertions that need to look at
// it without rendering the whole file.
func EditorFolders(m *Manifest, l Layout) []string {
	out := []string{l.FolderPath(m.Principal, m.Principal)}
	for _, w := range m.Inbound() {
		out = append(out, l.FolderPath(m.Principal, w.Coord))
	}
	return out
}
