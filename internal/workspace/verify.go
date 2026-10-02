package workspace

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
)

// DanglingIncludeOverride lets an emergency proceed past a dangling include. The default
// refuses; this override is traced — it shows up in shell history and is reported as a
// warning, so it cannot quietly become the normal path.
const DanglingIncludeOverride = "SEED_ALLOW_DANGLING_INCLUDES"

// Problem is one failed assertion. Every problem names its culprit: a message that says only
// "something is wrong" sends the reader back to the search that the assertion just did.
type Problem struct {
	Check   string
	Culprit string
	Detail  string
}

func (p Problem) String() string {
	if p.Detail == "" {
		return fmt.Sprintf("%s: %s", p.Check, p.Culprit)
	}
	return fmt.Sprintf("%s: %s — %s", p.Check, p.Culprit, p.Detail)
}

// Verify runs every assertion over a manifest and the disk it claims to describe.
//
// A static list that nobody checks rots exactly like the pins it replaces: same defect, merely
// relocated. These are therefore not optional niceties — they are the reason the manifest is
// worth having.
func Verify(m *Manifest, l Layout, principalDir string) []Problem {
	var out []Problem
	out = append(out, checkPrincipal(m, principalDir)...)
	out = append(out, checkWorktreesExist(m, l)...)
	out = append(out, checkFolderZero(m, l)...)
	out = append(out, checkIncludes(m, l)...)
	return out
}

// checkPrincipal compares what the manifest declares with what the disk says. The declaration
// is a claim about the world and is checked like one.
func checkPrincipal(m *Manifest, principalDir string) []Problem {
	_, onDisk, err := DeriveLayout(principalDir)
	if err != nil {
		return []Problem{{Check: "principal", Culprit: principalDir, Detail: err.Error()}}
	}
	if onDisk != m.Principal {
		return []Problem{{
			Check:   "principal",
			Culprit: m.Principal.String(),
			Detail:  fmt.Sprintf("the worktree on disk is %s", onDisk),
		}}
	}
	return nil
}

func checkWorktreesExist(m *Manifest, l Layout) []Problem {
	var out []Problem
	for _, c := range m.All() {
		dir := l.Dir(c)
		info, err := os.Stat(dir)
		switch {
		case err != nil:
			out = append(out, Problem{Check: "worktree missing", Culprit: c.String(), Detail: dir})
		case !info.IsDir():
			out = append(out, Problem{Check: "worktree not a directory", Culprit: c.String(), Detail: dir})
		default:
			// A directory that is not a checkout would satisfy a naive existence test while
			// resolving to nothing a flake ref could read.
			if _, err := os.Stat(filepath.Join(dir, ".git")); err != nil {
				out = append(out, Problem{Check: "not a git worktree", Culprit: c.String(), Detail: dir})
			}
		}
	}
	return out
}

func checkFolderZero(m *Manifest, l Layout) []Problem {
	folders := EditorFolders(m, l)
	want := l.FolderPath(m.Principal, m.Principal)
	if len(folders) == 0 || folders[0] != want {
		got := "(none)"
		if len(folders) > 0 {
			got = folders[0]
		}
		return []Problem{{
			Check:   "folder 0 is not the principal",
			Culprit: got,
			Detail:  "the editor derives the session config home from it, so a reorder breaks session history",
		}}
	}
	return nil
}

var includeDir = regexp.MustCompile(`(?m)\bdir\s*=\s*["']([^"']+)["']`)

// checkIncludes walks the flox include graph of every declared worktree.
//
// This is the assertion that earns the whole exercise. A dangling include does NOT break
// activation: flox freezes a composition until `flox include upgrade` is run explicitly, so an
// include pointing at a path that was never committed keeps working and reports nothing. The
// defect is invisible rather than broken, which is why only an executed check finds it.
func checkIncludes(m *Manifest, l Layout) []Problem {
	var out []Problem
	seen := map[string]bool{}
	for _, c := range m.All() {
		out = append(out, walkIncludes(l.Dir(c), c.String(), seen)...)
	}
	return out
}

// envManifest returns the manifest path of a flox environment rooted at dir, trying both
// shapes in use: a project environment keeps it under .flox/env, while the catalogue stores a
// bare manifest beside its lock.
func envManifest(dir string) string {
	for _, p := range []string{
		filepath.Join(dir, ".flox", "env", "manifest.toml"),
		filepath.Join(dir, "manifest.toml"),
	} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func walkIncludes(dir, origin string, seen map[string]bool) []Problem {
	manifest := envManifest(dir)
	if manifest == "" {
		return nil
	}
	if seen[manifest] {
		return nil
	}
	seen[manifest] = true

	body, err := os.ReadFile(manifest)
	if err != nil {
		return []Problem{{Check: "env manifest unreadable", Culprit: origin, Detail: err.Error()}}
	}

	var out []Problem
	for _, match := range includeDir.FindAllStringSubmatch(string(body), -1) {
		raw := match[1]
		// Include paths resolve against the environment's project directory, which is the
		// worktree — one level above .flox — not against the manifest's own directory.
		target := raw
		if !filepath.IsAbs(target) {
			target = filepath.Join(dir, raw)
		}
		if info, err := os.Stat(target); err != nil || !info.IsDir() {
			out = append(out, Problem{
				Check:   "dangling include",
				Culprit: fmt.Sprintf("%s -> %s", origin, raw),
				Detail:  fmt.Sprintf("%s not found", target),
			})
			continue
		}
		out = append(out, walkIncludes(target, fmt.Sprintf("%s -> %s", origin, raw), seen)...)
	}
	return out
}

// Dangling reports whether any problem is a dangling include, which is the only class the
// override may excuse.
func Dangling(problems []Problem) bool {
	for _, p := range problems {
		if p.Check == "dangling include" {
			return true
		}
	}
	return false
}

// OnlyDangling reports whether every problem is a dangling include. The override must not
// excuse a missing worktree or a broken folder-0 invariant.
func OnlyDangling(problems []Problem) bool {
	for _, p := range problems {
		if p.Check != "dangling include" {
			return false
		}
	}
	return len(problems) > 0
}
