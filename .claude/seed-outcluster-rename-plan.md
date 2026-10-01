# `seed-master` → `seed-outcluster` — the rename, measured

Decided 2026-10-01. The pair `seed-master` / `seed-incluster` did not contrast on one axis:
`master` says "the authoritative one", `incluster` says "where it runs". `seed-outcluster`
puts both on the same axis, and matches the existing spelling (no internal hyphen), which is
what keeps the **branch** `seed-incluster` out of scope entirely.

★ Why that matters: `seed-incluster` is a git BRANCH, a flake INPUT
(`github:seedmatic/rke2lab/seed-incluster`) and a worktree. Renaming it would need the
propagation sequence of `docs/architecture/patterns/flake-lock-propagation.adoc` (push the
new ref, move the input URL, relock, keep the old ref until nothing pins it). Choosing
`seed-outcluster` means **no branch rename, no input URL change, no lock churn**.

## Scope, measured — not estimated

**Zero Java identifiers.** The package is `io.seedmatic.rke2lab.controlplane.*`, so no class,
package or import moves. This is a mechanical rename, which is why it is safe to do in one
pass.

### Structural — breaks if missed

| site | what |
|---|---|
| `exec/pom.xml:21` | `<module>seed-master</module>` |
| `exec/seed-master/pom.xml:13` | `<artifactId>` |
| `exec/seed-master/pom.xml:15` | `<name>exec/seed-master</name>` |
| `build-parent/pom.xml:118` | dependencyManagement `<artifactId>` |
| `build-parent/pom.xml:123` | dependencyManagement `<artifactId>` |
| `flake.nix:568` | `glob = "exec/seed-master/target/seed-master-*-exec.jar"` + `name` |
| `flake.nix:1157` | `seed-master = seedMasterJar;` (the package output) |
| the directory | `git mv exec/seed-master exec/seed-outcluster` |

Plus `seedMasterJar` → `seedOutclusterJar`, a nix binding with 7 further mentions in
`flake.nix` comments (565, 652, 1156, 1248, 1266, 1282, 1302, 1317).

★ **The list above was too narrow** — it counted the `:seed-master` selectors and the poms
already read, not the functional surface. Three MORE breaking sites, found by grepping config
and Java rather than docs:

| site | what | why it breaks |
|---|---|---|
| `Pulumi.yaml:10` | `binary: exec/seed-master/target~nxmatic/seed-master-…-exec.jar` | the dev inner loop's jar path |
| `.vscode/launch.json` | 5 hits (`projectName` ×3, `cwd` ×2) | every debug configuration |
| `LaunchConfig.java:42` | `DEFAULT_LOG_FILE = ".local.d/seed-master.log"` | a real log path, not prose |

Lesson: the inventory must grep `--include='*.xml' --include='*.json' --include='*.yaml'
--include='*.bnd' --include='*.nix'` and `*.java` separately from `docs/`. A name that only
appears in prose is free; the same name in a `binary:` field is an outage.

### Selectors — stop resolving if missed

`:seed-master` appears in 11 files: `CLAUDE.md`, `exec/seed-master/pom.xml`,
`.claude/hub/memory/docrepo-dag-state.md`, and 8 docs (`patterns/flake-build-entry-point`,
4 under `doctor/`, 4 under `osgi/`).

### Prose — breaks nothing, must follow

67 files under `docs/` and 6 hits in `CLAUDE.md`. The repo's uniformity law says same change:
leaving 67 docs saying `seed-master` is exactly the drift that cost four incidents on
2026-10-01 (a name nobody declares, answered by something else).

## Order

1. `git mv exec/seed-master exec/seed-outcluster`
2. the 8 structural sites above, then `seedMasterJar` → `seedOutclusterJar`
3. `:seed-master` selectors (11 files)
4. prose sweep (docs + CLAUDE.md)
5. `./mvnw -pl :seed-outcluster -am package -Pall-worlds,claude -DskipTests=false`
   — the selector itself is the acceptance test: it only resolves if 1–2 are complete
6. `nix eval .#packages.aarch64-darwin.seed-outcluster` must resolve

## Vocabulary — SETTLED 2026-10-01

The user's model: **`operator -> pulumi -> outcluster -> incluster`**, and *"l'operateur c'est
moi, celui qui lance la commande pulumi"*. So link 1 is a PERSON and has no code. The chain
projects onto the code as:

| link | code |
|---|---|
| 1. operator | **none** — the human |
| 2. pulumi | `RunMode` (`STANDALONE` / `PULUMI_PREVIEW` / `PULUMI_RUN`) |
| 3. outcluster | `ExecutionEnclosure.OPERATOR` |
| 4. incluster | `ExecutionEnclosure.IN_CLUSTER` |

`RunMode` and `ExecutionEnclosure` are **orthogonal** and say so: *"a process can be OPERATOR
within a PULUMI_RUN, or IN_CLUSTER with no Pulumi at all."*

Consequence for THIS rename: **prose stays correct.** "the operator's host" means the host the
human owns — link 1 — so the 67-doc sweep does not touch that vocabulary and nothing is
renamed twice. Only the artifact name moves.

### Follow-up, deliberately NOT in this commit

- `ExecutionEnclosure.OPERATOR` → `OUT_CLUSTER`. It is exact and symmetrical with `IN_CLUSTER`
  ("which container this JVM runs inside" — there are exactly two), and it names the MACHINE
  side rather than the machine's owner.
- **Keep `Reach.OPERATOR_ONLY`.** Its complement is not "out-cluster": it also covers the
  `NODE_BOOTSTRAP` lane ("consumed operator-side, **or delivered node-side**"). A node is
  neither, so `OUT_CLUSTER_ONLY` would lie. Its true meaning is the fail-closed negation of
  `IN_CLUSTER`.
- ★ The real drift the chain exposed: **`standalone` is used on BOTH axes.** In `RunMode` it
  means "outside Pulumi"; in `ExecutionEnvironment:53` it is accepted as a synonym of
  `OPERATOR` — `case "operator", "standalone", "local" -> ExecutionEnclosure.OPERATOR` — i.e.
  "outside the cluster". One word, two orthogonal axes. Drop that alias with the rename.

## Not in scope

- the branch `seed-incluster` (see above)
- the review of seed-outcluster / in-cluster responsibilities, for which this name is the
  first symptom: `master` claimed the authority the review is meant to redistribute
