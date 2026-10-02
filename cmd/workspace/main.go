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
  -manifest PATH    the manifest (default: worktrees.yaml beside the principal's workspace slot)
  -principal DIR    the principal worktree (default: the current directory)
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
	principalDir := fs.String("principal", "", "the principal worktree")
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

	principal, err := resolvePrincipal(*principalDir)
	if err != nil {
		return err
	}
	layout, onDisk, err := workspace.DeriveLayout(principal)
	if err != nil {
		return err
	}
	m, err := workspace.Load(resolveManifest(*manifestPath, layout, onDisk.Repo))
	if err != nil {
		return err
	}

	if cmd == "verify" {
		return verify(m, layout, principal)
	}
	return materialize(materializeOpts{
		manifest:     m,
		layout:       layout,
		principalDir: principal,
		onDisk:       onDisk,
		registryPath: *registryPath,
		editorPath:   *editorPath,
		dryRun:       *dryRun,
	})
}

func resolvePrincipal(flagValue string) (string, error) {
	if flagValue != "" {
		return filepath.Abs(flagValue)
	}
	return os.Getwd()
}

// manifestSlot is the slot whose worktree carries this manifest — the orphan branch this
// binary is built from.
const manifestSlot = "workspace"

// resolveManifest defaults to the manifest's home: the `workspace` slot of the principal's own
// repository, located through the same layout function as everything else.
func resolveManifest(flagValue string, l workspace.Layout, repo string) string {
	if flagValue != "" {
		return flagValue
	}
	return filepath.Join(l.Dir(workspace.Coord{Repo: repo, Slot: manifestSlot}), "worktrees.yaml")
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
	principalDir string
	onDisk       workspace.Coord
	registryPath string
	editorPath   string
	dryRun       bool
}

func materialize(o materializeOpts) error {
	m, l, principal := o.manifest, o.layout, o.principalDir
	registryPath, dryRun := o.registryPath, o.dryRun

	// Materialising a workspace whose declaration disagrees with the disk would write a
	// registry pointing at directories that are not there.
	if problems := workspace.Verify(m, l, principal); len(problems) > 0 {
		for _, p := range problems {
			fmt.Fprintln(os.Stderr, "  "+p.String())
		}
		if !(workspace.OnlyDangling(problems) && os.Getenv(workspace.DanglingIncludeOverride) == "1") {
			return fmt.Errorf("refusing to materialise: %d failed assertion(s)", len(problems))
		}
	}

	registry, err := workspace.RegistryJSON(m, l)
	if err != nil {
		return err
	}
	// The canonical file is always the one READ, so the keys it carries beyond `folders` are
	// preserved. `-editor` only redirects where the result is written, which is what makes a
	// faithful comparison against the live file possible.
	existing, err := os.ReadFile(l.EditorFile(o.onDisk))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	editorFile := o.editorPath
	if editorFile == "" {
		editorFile = l.EditorFile(o.onDisk)
	}
	editor, err := workspace.EditorWorkspace(m, l, existing)
	if err != nil {
		return err
	}

	if registryPath == "" {
		registryPath = filepath.Join(principal, ".local.d", "registry.json")
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
