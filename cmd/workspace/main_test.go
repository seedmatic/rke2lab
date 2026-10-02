package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/seedmatic/workspace/internal/workspace"
)

// The anchor may be any declared worktree, so the manifest must be found without assuming it
// lives in the anchor's repository: from a neighbour's slot, deriving the path from that
// repository looks for a file that does not exist.
func TestResolveManifestFromAForeignAnchor(t *testing.T) {
	root := t.TempDir()
	l := workspace.Layout{StoreRoot: root, Org: "seedmatic"}

	home := l.Dir(workspace.Coord{Repo: "rke2lab", Slot: "workspace"})
	if err := os.MkdirAll(home, 0o755); err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(home, "worktrees.yaml")
	if err := os.WriteFile(want, []byte("version: 1\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	anchor := l.Dir(workspace.Coord{Repo: "ndh", Slot: "develop"})
	if err := os.MkdirAll(anchor, 0o755); err != nil {
		t.Fatal(err)
	}

	got, err := resolveManifest("", l, anchor, "ndh")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != want {
		t.Errorf("resolveManifest = %q, want %q", got, want)
	}
}

// Running from the slot that carries the manifest is the `nix run` case, and it must not
// depend on the glob.
func TestResolveManifestPrefersTheAnchorItself(t *testing.T) {
	root := t.TempDir()
	l := workspace.Layout{StoreRoot: root, Org: "seedmatic"}
	anchor := l.Dir(workspace.Coord{Repo: "rke2lab", Slot: "workspace"})
	if err := os.MkdirAll(anchor, 0o755); err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(anchor, "worktrees.yaml")
	if err := os.WriteFile(want, []byte("version: 1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := resolveManifest("", l, anchor, "rke2lab")
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Errorf("resolveManifest = %q, want %q", got, want)
	}
}

// Ambiguity is reported rather than guessed.
func TestResolveManifestRefusesSeveralCandidates(t *testing.T) {
	root := t.TempDir()
	l := workspace.Layout{StoreRoot: root, Org: "seedmatic"}
	for _, repo := range []string{"rke2lab", "other"} {
		dir := l.Dir(workspace.Coord{Repo: repo, Slot: "workspace"})
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "worktrees.yaml"), []byte("version: 1\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	anchor := l.Dir(workspace.Coord{Repo: "ndh", Slot: "develop"})
	if err := os.MkdirAll(anchor, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveManifest("", l, anchor, "ndh"); err == nil {
		t.Fatal("expected several candidates to be refused")
	}
}

// Nothing to find is a clear error naming where it looked, not a silent fallback.
func TestResolveManifestReportsNothingFound(t *testing.T) {
	root := t.TempDir()
	l := workspace.Layout{StoreRoot: root, Org: "seedmatic"}
	anchor := l.Dir(workspace.Coord{Repo: "ndh", Slot: "develop"})
	if err := os.MkdirAll(anchor, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveManifest("", l, anchor, "ndh"); err == nil {
		t.Fatal("expected a clear error when no manifest exists")
	}
}
