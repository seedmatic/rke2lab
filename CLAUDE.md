# Quick Start

## First-time setup

```bash
# Install flox (if not already installed)
curl -L https://downloads.flox.dev/by-env/stable/install | sh

# Activate flox environment (provides JDK 25 toolchain)
flox activate
```

All Maven commands must run through flox: `flox activate -- ./mvnw ...`

## Common commands

```bash
# Build all modules
./mvnw clean install

# Build specific module (use unprefixed artifact ID from module directory name)
./mvnw -pl :seed-outcluster clean install
./mvnw -pl :manifests clean package

# Run tests
./mvnw test

# Skip tests during build
./mvnw clean install -DskipTests

# Clean build artifacts
./mvnw clean
```

## Project structure

- `seed-outcluster/` - Main bootstrap orchestration and Pulumi control plane
- `manifests/` - Kubernetes manifest units (CDK8s-based, 27+ units)
- `systemd-port/` - Systemd unit abstractions and contracts (hexagonal port)
- `cdk8s-systemd/` - CDK8s integration for systemd units
- `netplan/` - Network configuration synthesis
- `sdks/incus/` - Incus API client SDK
- `bom/` - Bill of materials for dependency management
- `docs/` - Architecture documentation (40+ AsciiDoc files)

## Where to learn more

- **Architecture**: Start with `docs/README.adoc` for navigation
- **Bootstrap contract**: `docs/architecture/bootstrap/bootstrap-contract.adoc`
- **Pipeline (the seeding engine)**: `docs/architecture/bdd/bdd.adoc` (BDD-as-engine: jGiven scenarios on the embedded JUnit launcher)
- **Manifest architecture**: `docs/architecture/manifests/manifests-architecture.adoc`
- **Troubleshooting**: `docs/guides/troubleshooting-workflow.adoc`

# Project conventions

## Build & module layout

- Maven multi-module project. CLI selectors use the unprefixed module name: `./mvnw -pl :seed-outcluster` (not `:rke2lab-seed-outcluster`).
- **Never install project artifacts to the local repo** (`~/.m2`). Inter-module dependencies must always resolve through the **reactor**, from each module's `target/` — so a module build always uses `-am` (also-make) to build its siblings from source: `./mvnw -pl :seed-outcluster -am …`. A bare `-pl :seed-outcluster` resolves siblings from installed/stale jars and fails (e.g. `NodeEnvContributor` not found). Do not `mvn install` to work around it.
- Tests are skipped by default (root `.mvn` config). To actually execute them, pass `-DskipTests=false`. A build that prints `BUILD SUCCESS` with no `Tests run:` line means they were skipped, not passed.
- Group ids are nested under `io.seedmatic.rke2lab` (and `io.seedmatic.rke2lab.sdks` for the `sdks/` tree). Artifact ids match the directory name.
- `<name>` in each pom is the relative directory path from the repo root.
- Toolchain is JDK 25 via flox. Always run Maven through `flox activate -- ./mvnw …`. Claude may run any operation that does **not** mutate the provisioned system — compilation, tests, and dry-runs like `pulumi preview` are fine to run directly. Operations that change the live system (`pulumi up`, `kubectl apply`, and similar) are run by the user — propose them, don't execute them.

## Pipeline architecture — BDD-as-engine (jGiven + JUnit)

The seeding pipeline is expressed as **jGiven scenarios run by the embedded JUnit
Platform launcher** — the same jGiven + JUnit machinery we use to *test* rke2lab,
reused as the runtime engine (dogfooding / re-entrance; no richer model-level
alternative). The OSGi world owns the pipeline; the host (`seed-outcluster` +
`pulumi-edge`) boots the framework (`FrameworkLaunch.embedded().launch()`), injects
the `RunMode` fact + Pulumi context, and renders/writes (`RunMode` selects a
`PreviewExecutor` for preview — rendering decoupled from execution).

`ClusterSeedScenario` is the root runbook; every domain has its own scenario
(bbox, incus, systemd, cluster, manifests). The CLIs drive host-flat scenarios that
sow a coordinate through the **`seed.broker.port` membrane** — the one host↔OSGi
seam (JSON/Document only; never `awaitService` on a bundle-wired contract type).
The former fluent `during/then` grammar is gone; its one internal remnant is
`manifests-core`'s synthesis (`Phase`/`PhaseRunner`), un-exported and narrated by
`ManifestSynthesisScenario`.

- **Vision & rationale (why the runtime engine, not tests):**
  [docs/architecture/bdd/bdd.adoc](docs/architecture/bdd/bdd.adoc); discovery record
  [docs/architecture/osgi/bdd-pipeline-poc-design.adoc](docs/architecture/osgi/bdd-pipeline-poc-design.adoc).
- **Code ↔ vision delta (before/after figures):** the atlas —
  [docs/architecture/atlas/seed.adoc](docs/architecture/atlas/seed.adoc) and siblings.

## Code style

- Comments are exception, not the rule. Only document the *why* behind non-obvious choices. Do not narrate what the code does or reference task numbers / past callers.
- Avoid backwards-compatibility shims, dead-code comments, or speculative abstractions. Three similar lines beat a premature helper.
- Prefer editing existing files to creating new ones. Don't create design docs unless asked.
- No deprecation warnings. This is a single-developer project — when refactoring, delete the old API entirely and update all call sites in the same change.

## Design principles

- **Immutability by default**: When a class is mainly immutable and not designed for subclassing, convert it to a record. If mutation is needed later, generate a new instance via `with*` methods or a builder.
- **Builder enforcement**: If a class offers a builder and has multi-parameter constructors, make the constructor private to enforce builder usage and ease review. The builder pattern signals complex construction; direct construction bypasses that contract. *Exception — records:* a public record's canonical constructor cannot be made less visible than the record (Java rejects it). For a public record with a builder, the builder is the recommended-but-not-enforced path; nest the builder as a `static` class inside the record and route all factories (`defaults()`, `from(...)`) through it. Don't waste a cycle re-trying a private canonical constructor.
- **Functional APIs**: Design for composition and pipelines. Prefer fluent chains, function parameters (lambdas/method refs), and immutable transformations over stateful accumulators.
- **Multi-parameter methods**: When you encounter methods with 3+ parameters (especially booleans), note them as candidates for pipeline-based implementation. Consider whether the fluent grammar or a builder would improve readability and type safety.
- **Prefer instances over helpers**: Pass object instances through the call graph rather than creating static helper methods. This makes dependencies explicit, enables testing/mocking, and keeps state encapsulated. See "Instance-passing discipline" below.
- **Single source of truth for identifiers**: Use typed accessor methods from canonical registries (like `ManifestDomainCatalog`) instead of hardcoded string literals. This prevents identifier mismatches like the `clusterApi` bug where `"clusterApi"` string didn't match catalog's `"cluster-api"` ID. See "ManifestDomainCatalog discipline" below.
- **No instances with incomplete state**: Never create objects with `null` scope, missing required fields, or partial initialization. If construction must be deferred, use Supplier/Factory pattern or interface static factory methods. See "Lazy instantiation pattern" below.
- **Absolute uniformity in pattern implementation**: When N classes implement a pattern, ALL N must be identical — no "legacy" variants, no half-migrated code. Refactoring is atomic: migrate all or none. See "Uniformity enforcement" below.

### Instance-passing discipline

**Always prefer passing instances over creating static helpers.**

When you need functionality from another component:

✅ **DO**: Pass the instance through the call path

```java
// Caller creates and passes the instance
SystemdTarget systemdTarget = new SystemdTarget();
systemdTarget.materialize(paths);
FloxRuntimeAssets floxAssets = systemdTarget.getFloxRuntimeAssets();
List<DiscoveredEnvironment> envs = floxAssets.getDiscoveredEnvironments();
```

❌ **DON'T**: Create static helper methods

```java
// Anti-pattern: static helper hides dependencies
List<DiscoveredEnvironment> envs = FloxEnvironmentHelper.discover();
```

**Why this matters:**

1. **Explicit dependencies**: The call graph shows what data flows where. Static helpers hide this.
2. **Testability**: Instances can be mocked or stubbed. Static methods can't (without PowerMock hacks).
3. **State encapsulation**: Instances own their state. Static helpers either have no state (re-computing expensively) or hidden global state (coupling).
4. **Refactoring safety**: IDEs track instance passing. Static calls are harder to refactor.
5. **Context availability**: Instances can hold context (config, logger, metrics). Static methods can't without passing everything as parameters.

**Common scenarios:**

- **Discovered data** (like flox environments): The discovery is expensive and should happen once. Store in an instance field, expose via getter.
- **Configuration**: Pass `BootstrapConfig` instances, don't make static accessors.
- **Resource handles** (git repo, file trees): Wrap in an instance that manages lifecycle. Don't make static "open/close" helpers.

**When static methods ARE appropriate:**

- Pure utility functions with no state: `Math.max()`, `String.format()`, `Collections.unmodifiableList()`
- Factory methods: `ClassName.builder()`, `ClassName.of(...)`, `ClassName.parse(...)`
- Type-safe enum conversions: `SlotType.fromYamlValue(String)`

**Pattern evolution example:**

Starting point (static helper anti-pattern):

```java
// FloxEnvironmentHelper.java
public class FloxEnvironmentHelper {
  public static List<DiscoveredEnvironment> discover() {
    // Re-scans classpath every call - expensive!
  }
}
```

Refactored to instance (correct):

```java
// FloxRuntimeAssets.java
public class FloxRuntimeAssets {
  private final List<DiscoveredEnvironment> discovered;  // Computed once
  
  public List<DiscoveredEnvironment> getDiscoveredEnvironments() {
    return discovered;
  }
}

// Caller
FloxRuntimeAssets assets = FloxRuntimeAssets.builder().build();
List<DiscoveredEnvironment> envs = assets.getDiscoveredEnvironments();
```

**Enforcement:**

When reviewing code or implementing features:

- If you find yourself writing `public static X doSomething()`, ask: "Should this be an instance method?"
- If you're about to create a `XyzHelper` class with static methods, ask: "Which existing class should own this behavior?"
- If you see `SomeHelper.staticMethod()` calls, refactor to pass the instance.

### ManifestDomainCatalog discipline

**Never use hardcoded domain ID strings. Always use the ManifestDomainCatalog.**

On May 31, 2026, we fixed a critical bug where `clusterApi` policy showed `false` in MANIFEST.yaml despite being configured as `true`. Root cause: hardcoded string `"clusterApi"` didn't match the catalog's actual ID `"cluster-api"` (kebab-case). The mismatch caused `policy.isEnabled("clusterApi")` to silently return `false` (default) instead of the configured `true`.

**The fix**: Import `ManifestDomainCatalog` and use typed methods.

✅ **DO**: Use catalog accessor methods

```java
private static final ManifestDomainCatalog CATALOG =
    ManifestDomainCatalog.builder().addDefaultDomains().build();

// Domain registration
return new LayerDomain(
    CATALOG.gitops(),                    // ✅ Type-safe
    List.of(CATALOG.replication()),      // ✅ No typos possible
    units);

// Policy queries
if (policy.isEnabled(CATALOG.optionalUnit())) { // ✅ Guaranteed match
  units.add(new OptionalManifestsUnit());
}
```

❌ **DON'T**: Use magic strings

```java
// Anti-patterns that caused the clusterApi bug:
return new LayerDomain("gitops", List.of("replication"), units);  // ❌ Duplicates catalog
if (policy.isEnabled("optional-unit")) { ... }                       // ❌ Could typo to "optionalUnit"
manifestDomain.put("clusterApi", policy.isEnabled("clusterApi")); // ❌ Mismatch: "clusterApi" ≠ "cluster-api"
```

**Why multi-word domains are dangerous**:
- `catalog.clusterApi()` returns `"cluster-api"` (kebab-case)
- `catalog.highAvailability()` returns `"high-availability"` (kebab-case)
- Hardcoding `"clusterApi"` creates a mismatch → silent failure

**Enforcement**:
- Grep in code review: `! grep -r 'isEnabled("' manifests/src/ seed-outcluster/src/`
- When adding a new domain, add the accessor method to `ManifestDomainCatalog` first
- See `docs/architecture/manifests/manifest-domain-catalog-pattern.adoc` for full pattern documentation

### Lazy instantiation pattern

**Never create objects with incomplete state.** If an object requires constructor arguments not yet available (e.g., CDK8s Construct needing a Chart scope), use lazy instantiation.

**Pattern**: Interface provides static factory method that returns anonymous implementation holding only metadata. Real instance created on-demand when arguments become available.

**Example** (from ManifestsUnit):

```java
public interface ManifestsUnit {
  static ManifestsUnit lazy(
      String manifestUnitId,
      List<String> dependsOnManifestsUnitIds,
      BiFunction<Construct, String, ? extends ManifestsUnit> factory) {
    return new ManifestsUnit() {
      @Override public String manifestUnitId() { return manifestUnitId; }
      @Override public List<String> dependsOnManifestsUnitIds() { return dependsOnManifestsUnitIds; }
      @Override public void apply(Chart chart) {
        factory.apply(chart, manifestUnitId.replace("/", "-"));
      }
    };
  }
}

// Usage in registrar:
ManifestsUnit.lazy(
    XxxManifestsUnit.MANIFEST_UNIT_ID,
    List.of(),
    XxxManifestsUnit::new  // method reference to (Construct, String) constructor
)
```

**Why this pattern**:

1. No objects with `null` or incomplete state
2. Type-safe: method reference verifies constructor signature at compile time
3. Clean: factory lives in the interface, no separate wrapper class needed
4. Defers construction until arguments available, avoiding "create then reinitialize" anti-pattern

**Anti-patterns**:

- ❌ `new XxxUnit(null, "temp")` — incomplete state
- ❌ Separate `LazyXxxUnit` wrapper class — verbose, couples pattern to specific type
- ❌ No-arg constructor + separate `initialize()` method — allows usage before initialization

**When to apply**: Any time construction arguments aren't available at object creation time.

### Local classes vs inner classes

**Prefer local classes (defined within methods) over inner classes when the class is only used in that single method.** This reduces namespace pollution and makes the scope explicit.

**When to use local classes:**

- Class is used in only one method
- Class is simple and short (< ~50 lines total)
- Class encapsulates method-specific logic that doesn't need to be shared

**When to use inner classes:**

- Class is shared across multiple methods of the outer class
- Class is complex/long (50+ lines) — putting it in a method would hurt readability
- Class is part of a fluent pipeline pattern with many stages (e.g., `SynthesisPipeline`)

**Static vs non-static:**

- **Static** when the class doesn't need access to the outer instance's fields/methods
- **Non-static** when it does need that access

**Example** (local class for single-method usage):

```java
// ✅ DO: Local class defined in the method
private String synthesizeImageStateConfigMapYaml(...) {
  record ImageStateData(...) {}  // Local record
  
  final ImageStateData data = new ImageStateData(...);
  
  // Method body using data...
  return result;
}

// ❌ DON'T: Inner class when only one method uses it
private static final class ImageStateSynthesizer {
  record ImageStateData(...) {}
  String synthesize(...) { ... }
}

private String synthesizeImageStateConfigMapYaml(...) {
  return new ImageStateSynthesizer().synthesize(...);  // Only call site
}
```

**Example** (inner class for complex multi-method logic):

```java
// ✅ DO: Inner class when used by multiple methods or complex
private final class NetworkSetup {
  boolean shouldSkip() { ... }
  String resolveProject() { ... }
  NetworkArgs buildArgs() { ... }
  // ... 7 methods total
}

private void ensureNetwork(...) {
  final NetworkSetup setup = new NetworkSetup();
  if (setup.shouldSkip()) return;
  // ... use multiple methods
}
```

**Refactoring from inner to local class:**

When you find an inner class that:

1. Has only ONE instantiation site (grep for `new ClassName()`)
2. That instantiation is in ONE method
3. The class is reasonably simple

→ Move it into that method as a local class.

**Benefits:**

- Reduced namespace pollution in large classes (e.g., `IncusResourceBootstrap` had 171 private methods)
- Explicit scope: impossible to accidentally use the class elsewhere
- Easier to understand: logic is colocated with its single usage point
- Refactoring: when you delete the method, the class goes with it

**Pattern applied**: On 2026-06-03, refactored `ImageStateSynthesizer` and `NetworkEnsurer` from inner classes to local classes, reducing namespace pollution in `IncusResourceBootstrap`.

### Uniformity enforcement

**All implementations of a pattern must be identical.** If you're refactoring 27 classes to a new pattern, ALL 27 must follow the same structure — no "legacy" variants, no "compatibility" branches, no mixed old/new patterns.

**Why absolute uniformity matters**:

1. **Code review**: Spot deviations instantly — any difference is a bug
2. **Maintenance**: Change pattern once, apply everywhere with confidence
3. **Cognitive load**: One pattern to remember, not "pattern + 3 variants"
4. **Refactoring safety**: Search/replace works when code is uniform

**Enforcement**:

- During refactor: Count classes affected. Before finishing, verify count matches (e.g., `grep -l "pattern" | wc -l`)
- No "backward compatibility" constructors after refactor completes
- Remove old pattern completely in same commit that adds new pattern
- If 1 of N classes can't be migrated, find out why before proceeding with the other N-1

**Code smell**: Comments like "legacy constructor for compatibility" or `@Deprecated` annotations in single-developer project = incomplete refactor. Delete old pattern entirely.

**Example** (from this session): 27 ManifestsUnit classes all have exactly ONE constructor `(Construct scope, String id)` — no no-arg variants, no apply() overrides. Uniformity means any unit can be audited by reading just one, and changes apply to all 27.

### Single-source-of-truth pattern for identifiers

**The pattern**: ManifestDomainCatalog, BootstrapPaths.HostPathCatalog, and SystemdUnitCatalog all follow the same principle - **define identifiers once, reference everywhere**.

**Why**: Identifier mismatches cause silent failures. Three bugs fixed on May 31, 2026:

1. `"clusterApi"` string vs `"cluster-api"` catalog ID → policy showed false
2. `"manifests.d"` directory vs `"/srv/host/rke2-manifests.d"` constant → units never started (condition failed)
3. `"rke2lab-clusterapi-manifests.service"` dependency vs `"rke2lab-cluster-api-manifests.service"` filename → systemd "Unit not found"

**When to create a catalog**:

- Multiple files reference the same identifier (domain ID, path, unit name, etc.)
- Identifier format is non-obvious (kebab-case, camelCase, with/without prefix)
- Mismatch would cause silent failure or runtime error

**Existing catalogs**:

- `ManifestDomainCatalog` - manifest domain IDs
- `BootstrapPaths.HostPathCatalog` - container mount paths  
- `SystemdUnitCatalog` - systemd unit filenames

**Template**:

```java
public final class XyzCatalog {
  public static final String SOME_ID = "actual-value";
  // ... more constants
  
  private XyzCatalog() {} // Utility class
}
```

For systemd units specifically: unit files can't use Java code, so the catalog serves as the **authoritative registry during code review**. When writing `After=some-unit.service`, verify the name exists in `SystemdUnitCatalog`.

## Documentation standards

Documentation is critical for context recovery in this multi-concern project.
When writing or updating architecture/design docs (AsciiDoc under `docs/`), the
**`doc-standards` skill** carries the full standard — when to document, the
C4/Mermaid quality bar, ✅/❌ usage patterns, cross-referencing + `docs/README.adoc`
upkeep, and the pre-completion checklist. It auto-loads for documentation tasks;
invoke `/doc-standards` if it doesn't.

# Common instructions (shared via the claude-hub subtree)

@.claude/hub/instructions.md
