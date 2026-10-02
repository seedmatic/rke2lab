package workspace

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/BurntSushi/toml"
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
func Verify(m *Manifest, l Layout, anchorDir string) []Problem {
	var out []Problem
	out = append(out, checkAnchor(m, anchorDir)...)
	out = append(out, checkOrg(m, l)...)
	out = append(out, checkWorktreesExist(m, l)...)
	out = append(out, checkFolderZero(m, l)...)
	out = append(out, checkIncludes(m, l)...)
	return out
}

// checkOrg compares the declared org with the one derived from the principal's location.
//
// Without this the field would be decorative: every generated path uses the DERIVED org, so a
// typo in the manifest would validate and change nothing — a declaration that cannot be wrong
// because nothing reads it, which is the shape of defect this tool exists to remove.
func checkOrg(m *Manifest, l Layout) []Problem {
	if m.Org != l.Org {
		return []Problem{{
			Check:   "org mismatch",
			Culprit: m.Org,
			Detail:  fmt.Sprintf("the principal sits under %q", l.Org),
		}}
	}
	return nil
}

// checkAnchor establishes that the directory the roots were derived from really belongs to the
// workspace this manifest describes.
//
// It deliberately does NOT require being run from the principal. The tool is useful from any
// slot — materialising from the slot that carries the manifest is the obvious case — and the
// roots derive identically from any of them. What would be wrong is deriving them from a
// directory the manifest never declares: the layout would then place every worktree under some
// unrelated tree, and every path would be confidently wrong.
func checkAnchor(m *Manifest, anchorDir string) []Problem {
	_, onDisk, err := DeriveLayout(anchorDir)
	if err != nil {
		return []Problem{{Check: "anchor", Culprit: anchorDir, Detail: err.Error()}}
	}
	for _, c := range m.All() {
		if c == onDisk {
			return nil
		}
	}
	return []Problem{{
		Check:   "anchor is outside the declared workspace",
		Culprit: onDisk.String(),
		Detail:  fmt.Sprintf("%s is not a declared worktree, so the derived roots cannot be trusted", anchorDir),
	}}
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
			if p := checkCheckout(c, dir); p != nil {
				out = append(out, *p)
			}
		}
	}
	return out
}

// checkCheckout establishes that a directory is a usable checkout, not merely one carrying
// something named `.git`.
//
// A linked worktree keeps a `.git` FILE holding `gitdir: <path>`, and that target can be
// removed while the file stays — in which case a bare existence test passes and the generated
// `git+file:` reference fails later, far from here.
func checkCheckout(c Coord, dir string) *Problem {
	gitPath := filepath.Join(dir, ".git")
	info, err := os.Stat(gitPath)
	if err != nil {
		return &Problem{Check: "not a git worktree", Culprit: c.String(), Detail: dir}
	}
	if info.IsDir() {
		return nil
	}
	body, err := os.ReadFile(gitPath)
	if err != nil {
		return &Problem{Check: "gitfile unreadable", Culprit: c.String(), Detail: gitPath}
	}
	target := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(string(body)), "gitdir:"))
	if target == "" {
		return &Problem{Check: "gitfile carries no gitdir", Culprit: c.String(), Detail: gitPath}
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(dir, target)
	}
	if _, err := os.Stat(target); err != nil {
		return &Problem{
			Check:   "gitdir target missing",
			Culprit: c.String(),
			Detail:  fmt.Sprintf("%s points at %s", gitPath, target),
		}
	}
	return nil
}

// checkFolderZero reads the CANONICAL editor file and checks its first folder.
//
// Asserting the freshly computed list instead would be vacuous: the generator always prepends
// the principal, so the check could never fail — and the failure mode it claims to catch is
// precisely the file ON DISK being reordered by hand or by another tool.
func checkFolderZero(m *Manifest, l Layout) []Problem {
	path := l.EditorFile(m.Principal)
	body, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		// Nothing to regress yet; materialisation will create it.
		return nil
	}
	if err != nil {
		return []Problem{{Check: "editor file unreadable", Culprit: path, Detail: err.Error()}}
	}

	var doc struct {
		Folders []struct {
			Path string `json:"path"`
		} `json:"folders"`
	}
	if err := json.Unmarshal(body, &doc); err != nil {
		return []Problem{{Check: "editor file unparseable", Culprit: path, Detail: err.Error()}}
	}

	want := l.FolderPath(m.Principal, m.Principal)
	got := "(none)"
	if len(doc.Folders) > 0 {
		got = doc.Folders[0].Path
	}
	if got != want {
		return []Problem{{
			Check:   "folder 0 is not the principal",
			Culprit: got,
			Detail: fmt.Sprintf(
				"%s should start with %q — the editor derives the session config home from it, "+
					"so a reorder breaks session history", path, want),
		}}
	}
	return nil
}

// floxManifest is the narrow slice of a flox environment manifest this tool reads. The rest of
// the document is deliberately ignored, but it is still PARSED as TOML rather than scanned:
//
// a regex over the raw text cannot tell a declaration from a COMMENTED TEMPLATE, and the
// environments in this very workspace ship one — `#  { dir = "../common" }` — plus a `[profile]`
// key that merely happens to be named `common`. Matching those produced three confident false
// positives, which is the same defect this tool exists to remove: answering something instead
// of saying what one cannot read.
type floxManifest struct {
	Include struct {
		Environments []struct {
			Dir string `toml:"dir"`
		} `toml:"environments"`
	} `toml:"include"`
}

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

	var parsed floxManifest
	if _, err := toml.DecodeFile(manifest, &parsed); err != nil {
		// Refusing to read is reported, never skipped: a manifest this tool cannot parse is a
		// gap in the assertion, and a silent gap is what it is here to prevent.
		return []Problem{{Check: "env manifest unparseable", Culprit: origin, Detail: err.Error()}}
	}

	var out []Problem
	for _, inc := range parsed.Include.Environments {
		raw := inc.Dir
		if raw == "" {
			continue
		}
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
