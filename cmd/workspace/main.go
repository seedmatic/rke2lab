// Command workspace materialises a workspace from its manifest, and verifies one.
//
// Two subcommands with distinct roles, and the split is not cosmetic:
//
//	materialize  produces the registry and the editor file
//	verify       asserts only, and fails hard
//
// The registry cannot be a product of activation. Evaluating the principal's flake needs the
// registry, and the environment builds BEFORE an activation hook runs — a registry generated
// by that hook would arrive too late for the build that needs it. Measured the other way
// round: a committed lock carries its derivation and outputs, so it realises without
// re-evaluating, which is why activation needs no registry at all and `verify` is all a hook
// has to do.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/seedmatic/workspace/internal/workspace"
)

var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "FATAL: "+err.Error())
		os.Exit(1)
	}
}

func usage() string {
	return `usage: workspace <command> [flags]

commands:
  materialize   write the registry and the editor file from the manifest
  verify        assert the manifest against the disk, and fail hard
  version       print the version

flags (both commands):
  -manifest PATH    the manifest (default: worktrees.yaml in the workspace slot)
  -anchor DIR       any DECLARED worktree, used to derive the roots (default: the current directory)
  -registry PATH    where to write the registry (default: <principal>/.local.d/registry.json)
  -editor PATH      where to write the editor file (default: its canonical location)
  -dry-run          print what would be written instead of writing it
`
}

func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("no command given\n\n%s", usage())
	}
	cmd, rest := args[0], args[1:]

	fs := flag.NewFlagSet(cmd, flag.ContinueOnError)
	manifestPath := fs.String("manifest", "", "path to the manifest")
	anchorDir := fs.String("anchor", "", "any declared worktree, used to derive the roots")
	registryPath := fs.String("registry", "", "where to write the registry")
	editorPath := fs.String("editor", "", "where to write the editor file")
	dryRun := fs.Bool("dry-run", false, "print instead of writing")
	fs.Usage = func() { fmt.Fprint(os.Stderr, usage()) }

	switch cmd {
	case "version":
		fmt.Println(version)
		return nil
	case "materialize", "verify":
		if err := fs.Parse(rest); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown command %q\n\n%s", cmd, usage())
	}

	anchor, err := resolveAnchor(*anchorDir)
	if err != nil {
		return err
	}
	layout, onDisk, err := workspace.DeriveLayout(anchor)
	if err != nil {
		return err
	}
	manifest, err := resolveManifest(*manifestPath, layout, anchor, onDisk.Repo)
	if err != nil {
		return err
	}
	m, err := workspace.Load(manifest)
	if err != nil {
		return err
	}

	if cmd == "verify" {
		return verify(m, layout, anchor)
	}
	return materialize(materializeOpts{
		manifest:     m,
		layout:       layout,
		anchorDir:    anchor,
		registryPath: *registryPath,
		editorPath:   *editorPath,
		dryRun:       *dryRun,
	})
}

func resolveAnchor(flagValue string) (string, error) {
	if flagValue != "" {
		return filepath.Abs(flagValue)
	}
	return os.Getwd()
}

// manifestSlot is the slot whose worktree carries the manifest — the orphan branch this binary
// is built from.
const manifestSlot = "workspace"

const manifestName = "worktrees.yaml"

// resolveManifest finds the manifest WITHOUT assuming it lives in the anchor's repository.
//
// The anchor may be any declared worktree, so deriving the manifest path from its repository
// would look for `ndh.d/workspace/worktrees.yaml` when run from ndh — a path that does not
// exist. The candidates are therefore, in order: the anchor itself (the case when the command
// runs from the slot that carries the manifest), then the anchor repository's workspace slot,
// then every `*.d/workspace/` under the org. The last is what makes it anchor-independent, and
// ambiguity is reported rather than guessed.
func resolveManifest(flagValue string, l workspace.Layout, anchor string, repo string) (string, error) {
	if flagValue != "" {
		return flagValue, nil
	}
	for _, c := range []string{
		filepath.Join(anchor, manifestName),
		filepath.Join(l.Dir(workspace.Coord{Repo: repo, Slot: manifestSlot}), manifestName),
	} {
		if _, err := os.Stat(c); err == nil {
			return c, nil
		}
	}

	found, err := filepath.Glob(filepath.Join(l.StoreRoot, l.Org, "*.d", manifestSlot, manifestName))
	if err != nil {
		return "", err
	}
	switch len(found) {
	case 1:
		return found[0], nil
	case 0:
		return "", fmt.Errorf(
			"no %s found in %s, nor in any %s/*.d/%s — pass -manifest",
			manifestName, anchor, l.Org, manifestSlot)
	default:
		return "", fmt.Errorf(
			"several manifests found (%s) — pass -manifest to choose", strings.Join(found, ", "))
	}
}

func verify(m *workspace.Manifest, l workspace.Layout, principal string) error {
	problems := workspace.Verify(m, l, principal)
	if len(problems) == 0 {
		fmt.Printf("healthy — %d worktrees declared, folder 0 is %s\n", len(m.All()), m.Principal)
		return nil
	}

	for _, p := range problems {
		fmt.Fprintln(os.Stderr, "  "+p.String())
	}
	if workspace.OnlyDangling(problems) && os.Getenv(workspace.DanglingIncludeOverride) == "1" {
		fmt.Fprintf(os.Stderr, "WARN: %d dangling include(s) ignored via %s\n",
			len(problems), workspace.DanglingIncludeOverride)
		return nil
	}
	if workspace.Dangling(problems) {
		fmt.Fprintf(os.Stderr, "  override a dangling include with %s=1\n", workspace.DanglingIncludeOverride)
	}
	return fmt.Errorf("%d failed assertion(s)", len(problems))
}

type materializeOpts struct {
	manifest     *workspace.Manifest
	layout       workspace.Layout
	anchorDir    string
	registryPath string
	editorPath   string
	dryRun       bool
}

func materialize(o materializeOpts) error {
	m, l, anchor := o.manifest, o.layout, o.anchorDir
	registryPath, dryRun := o.registryPath, o.dryRun

	// Materialising a workspace whose declaration disagrees with the disk would write a
	// registry pointing at directories that are not there.
	if problems := workspace.VerifyInputs(m, l, anchor); len(problems) > 0 {
		for _, p := range problems {
			fmt.Fprintln(os.Stderr, "  "+p.String())
		}
		if !(workspace.OnlyDangling(problems) && os.Getenv(workspace.DanglingIncludeOverride) == "1") {
			return fmt.Errorf("refusing to materialise: %d failed assertion(s)", len(problems))
		}
		// Proceeding past a failed assertion is reported here exactly as `verify` reports it,
		// so an automated log cannot confuse a deliberate bypass with a clean run.
		fmt.Fprintf(os.Stderr, "WARN: materialising past %d dangling include(s) via %s\n",
			len(problems), workspace.DanglingIncludeOverride)
	}

	registry, err := workspace.RegistryJSON(m, l)
	if err != nil {
		return err
	}
	// The canonical file is always the one READ, so the keys it carries beyond `folders` are
	// preserved. `-editor` only redirects where the result is written, which is what makes a
	// faithful comparison against the live file possible.
	existing, err := os.ReadFile(l.EditorFile(m.Principal))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	editorFile := o.editorPath
	if editorFile == "" {
		editorFile = l.EditorFile(m.Principal)
	}
	editor, err := workspace.EditorWorkspace(m, l, existing)
	if err != nil {
		return err
	}

	if registryPath == "" {
		registryPath = filepath.Join(l.Dir(m.Principal), ".local.d", "registry.json")
	}

	if dryRun {
		fmt.Printf("--- %s\n%s", registryPath, registry)
		fmt.Printf("--- %s\n%s", editorFile, editor)
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(registryPath), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(registryPath, registry, 0o644); err != nil {
		return err
	}
	if err := os.WriteFile(editorFile, editor, 0o644); err != nil {
		return err
	}
	fmt.Printf("wrote %s\nwrote %s\n", registryPath, editorFile)
	fmt.Printf("export NIX_CONFIG=\"flake-registry = %s\"\n", registryPath)
	return nil
}
