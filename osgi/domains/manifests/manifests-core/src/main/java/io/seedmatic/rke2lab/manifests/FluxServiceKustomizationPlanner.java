package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxRootManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxControllerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxWebhookManifestsUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the per-service Flux {@code Kustomization} objects the root Kustomization ({@link
 * FluxRootManifestsUnit}) reconciles from {@code ./flux}. Runs AFTER the exploder, so it knows the
 * exact set of non-empty {@code (layer, domain, package)} cells (from the written tree) and can
 * SCAN every written doc — enough to wire each child's ordering and health-gating from real
 * rendered structure rather than a hand-maintained list of layer barriers.
 *
 * <p>Each child's {@code dependsOn} is the union of:
 *
 * <ul>
 *   <li>the SERVICE→service edges from each unit's {@code dependsOnManifestsUnitIds} — the declared
 *       intra-/cross-domain graph (e.g. {@code headplane → headscale}); a dep resolves to every
 *       cell of its coord (a multi-layer service is waited on in full); and
 *   <li>the DERIVED {@code CRD → CR} edges — for every rendered CR, the cell(s) that PROVIDE its
 *       CRD. A CRD is provided either by a cell that RENDERS it (scanned {@code
 *       CustomResourceDefinition} docs → {@link DocScan#crdProviderCoord}) or by a cell that
 *       INSTALLS it at runtime (an operator/HelmChart — the declared {@link #RUNTIME_INSTALLERS}
 *       map). The consumer depends on every cell of the provider's coord, so both the CRD and the
 *       controller that serves it are up first. This REPLACES the old global layer barrier: no more
 *       false coupling (a {@code networking} workload no longer waits on {@code cicd}'s operator).
 *   <li>the FLOX-RUNTIME edge — a cell whose pod templates consume a flox env / nix-build store (a
 *       {@code flox.seedmatic.io/*} pod-template annotation) waits on the flox-controller runtime
 *       cells ({@code runtime/flox-webhook} + {@code runtime/flox-controller}). The webhook's
 *       {@code failurePolicy: Ignore} means it cannot itself hold back an un-gated pod, so the Flux
 *       edge is the gate that keeps a flox consumer from scheduling before its env can be realised.
 * </ul>
 *
 * <p>The map of runtime installers is the one hand-maintained artifact, but it cannot silently rot:
 * a rendered CR whose group is neither a rendered CRD, nor a known runtime installer, nor a
 * built-in / distro-provided group ({@link #BUILTIN_GROUPS} / {@link #DISTRO_GROUPS}) FAILS THE
 * RENDER (see {@link #resolveProvider}). Failing loud at synth time beats the silent cold-start
 * fail-retry the coarse barrier used to mask.
 *
 * <p>Two health-gating derivations from the same scan:
 *
 * <ul>
 *   <li>a runtime installer that owns a readiness resource (Tekton's {@code TektonConfig config})
 *       carries it as {@code spec.healthChecks} on the cell rendering it, with {@code wait: false},
 *       so Flux gates on THAT resource — only once the operator has finished installing its CRDs —
 *       not on every applied object; the consumer cells depend on the installer cell.
 *   <li>a cell rendering a {@code WaitForFirstConsumer} PVC gets {@code wait: false} — the PVC
 *       stays {@code Pending} until a pod mounts it, so {@code wait: true} would wedge it forever.
 *       The binding mode is DERIVED (each {@code StorageClass}'s {@code volumeBindingMode} + the
 *       {@code is-default-class} annotation, then the PVC's explicit-or-default class).
 * </ul>
 *
 * <p>The layout is three levels (ownership): the root Kustomization ({@code ./flux}) applies the
 * per-DOMAIN Kustomizations; each domain Kustomization ({@code ./flux/<domain>}) applies its
 * per-SERVICE Kustomizations. Each level carries an explicit {@code kustomization.yaml} listing
 * only its own resources, so kustomize never recurses across levels (deterministic, no reliance on
 * Flux's directory-scan behaviour). {@code flux tree} then reads by domain. Both ORDERING and
 * READINESS stay at the SERVICE level — the precise edges above (which reference peer
 * Kustomizations by name and so hold across domains) and each service's own {@code wait: true}; the
 * domain level is ownership/grouping only ({@code wait: false}, no inter-domain coupling), so it
 * never health-gates and never holds a reconciler worker waiting.
 *
 * <p>Result: {@code flux get kustomizations} shows per-service progress with precise dependencies;
 * a failure stays local. Service names are the bare {@code <domain>-<package>}, with a {@code
 * -<layer>} suffix only for a service whose resources span more than one layer.
 */
final class FluxServiceKustomizationPlanner {

  private static final Logger LOG = LoggerFactory.getLogger(FluxServiceKustomizationPlanner.class);

  private static final String CUSTOM_RESOURCE_DEFINITION = "CustomResourceDefinition";
  private static final String PERSISTENT_VOLUME_CLAIM = "PersistentVolumeClaim";
  private static final String STORAGE_CLASS = "StorageClass";
  private static final String IS_DEFAULT_CLASS = "storageclass.kubernetes.io/is-default-class";
  private static final String WAIT_FOR_FIRST_CONSUMER = "WaitForFirstConsumer";

  /**
   * A pod-template annotation whose key starts with one of these prefixes ({@code
   * flox.seedmatic.io/environment.<c>} or {@code flox.seedmatic.io/nix-build.<c>}) marks the pod as
   * a flox consumer — the two capabilities that pull in the flox runtime (the NRI plugin mounts the
   * env / nix-build store, the controller webhook gates scheduling). The other {@link
   * FloxAnnotation} keys (HOME/UID/DEBUG…) only modify an existing opt-in, so they do not by
   * themselves make a cell wait on the runtime. Derived from the enum, never a string literal.
   */
  private static final List<String> FLOX_CONSUMER_KEY_PREFIXES =
      List.of(FloxAnnotation.ENVIRONMENT.key() + ".", FloxAnnotation.NIX_BUILD.key() + ".");

  /**
   * The flox runtime coords a flox-consumer cell must wait on. The webhook's {@code failurePolicy:
   * Ignore} means a pod created before the webhook serves is admitted UN-gated (schedules before
   * its env is realised → {@code CreateContainerError}); the Flux edge is therefore the real gate.
   * Both cells are needed: {@link FloxWebhookManifestsUnit} registers the {@code
   * MutatingWebhookConfiguration} + its cert-manager serving cert, and {@link
   * FloxControllerManifestsUnit} runs the node-agent DaemonSet that both SERVES that webhook (only
   * under {@code --enable-webhook}) and REALISES the env onto the node. Neither carries a flox
   * consumer annotation itself, so a consumer cell never loops back onto these.
   */
  private static final Set<String> FLOX_RUNTIME_COORDS =
      Set.of(
          FloxControllerManifestsUnit.MANIFEST_UNIT_ID, FloxWebhookManifestsUnit.MANIFEST_UNIT_ID);

  /** API groups the API server serves natively — always present, never need a CRD provider. */
  private static final Set<String> BUILTIN_GROUPS =
      Set.of(
          "", // core (apiVersion: v1)
          "apps",
          "batch",
          "autoscaling",
          "policy",
          "apiextensions.k8s.io",
          "admissionregistration.k8s.io",
          "apiregistration.k8s.io",
          "authentication.k8s.io",
          "authorization.k8s.io",
          "certificates.k8s.io",
          "coordination.k8s.io",
          "discovery.k8s.io",
          "events.k8s.io",
          "flowcontrol.apiserver.k8s.io",
          "networking.k8s.io",
          "node.k8s.io",
          "rbac.authorization.k8s.io",
          "scheduling.k8s.io",
          "storage.k8s.io");

  /**
   * API groups whose CRDs the DISTRO (RKE2) ships — present before any rendered manifest, so no
   * in-repo cell provides them. {@code helm.cattle.io} is RKE2's bundled helm-controller (the
   * {@code HelmChart}/{@code HelmChartConfig} kinds every chart-installing unit uses).
   */
  private static final Set<String> DISTRO_GROUPS = Set.of("helm.cattle.io");

  /**
   * A CRD installed at RUNTIME by an operator/HelmChart (not rendered as a {@code
   * CustomResourceDefinition} in the tree) → the coord of the cell that installs it, plus the
   * optional readiness resource that signals "the CRDs are now servable". Consulted only for a
   * group with no rendered CRD; a group here that a CR uses but whose cell is absent from the tree
   * fails the render (a broken manifest — the CR outlives its installer).
   */
  private record RuntimeInstaller(String coord, Optional<HealthCheck> healthCheck) {}

  private static final HealthCheck TEKTON_CONFIG =
      new HealthCheck("operator.tekton.dev/v1alpha1", "TektonConfig", "config");

  private static final Map<String, RuntimeInstaller> RUNTIME_INSTALLERS = buildRuntimeInstallers();

  private static Map<String, RuntimeInstaller> buildRuntimeInstallers() {
    final Map<String, RuntimeInstaller> installers = new HashMap<>();
    // Tekton operator: reconciling TektonConfig (profile all) installs the pipeline CRDs.
    final RuntimeInstaller tekton =
        new RuntimeInstaller("cicd/tekton-pipelines", Optional.of(TEKTON_CONFIG));
    installers.put("tekton.dev", tekton);
    installers.put("pipelinesascode.tekton.dev", tekton);
    installers.put("triggers.tekton.dev", tekton);
    // flux-operator's HelmChart (installCRDs) registers the operator's own group.
    final RuntimeInstaller fluxOperator =
        new RuntimeInstaller("gitops/flux-operator", Optional.empty());
    installers.put("fluxcd.controlplane.io", fluxOperator);
    // The FluxInstance CR makes the operator deploy the toolkit controllers, which own these
    // groups.
    final RuntimeInstaller fluxInstance =
        new RuntimeInstaller("gitops/flux-instance", Optional.empty());
    installers.put("source.toolkit.fluxcd.io", fluxInstance);
    installers.put("kustomize.toolkit.fluxcd.io", fluxInstance);
    installers.put("notification.toolkit.fluxcd.io", fluxInstance);
    installers.put("helm.toolkit.fluxcd.io", fluxInstance);
    installers.put("image.toolkit.fluxcd.io", fluxInstance);
    // cert-manager's Helm chart (crds.enabled) registers its groups.
    final RuntimeInstaller certManager =
        new RuntimeInstaller("platform/cert-manager", Optional.empty());
    installers.put("cert-manager.io", certManager);
    installers.put("acme.cert-manager.io", certManager);
    // Cilium's HelmChartConfig (installCRDs) registers cilium.io + (gatewayAPI) the Gateway-API
    // CRDs.
    final RuntimeInstaller cilium =
        new RuntimeInstaller("networking/cilium-config", Optional.empty());
    installers.put("cilium.io", cilium);
    installers.put("gateway.networking.k8s.io", cilium);
    // The tailscale-operator HelmChart registers tailscale.com at runtime.
    installers.put("tailscale.com", new RuntimeInstaller("mesh/tailscale", Optional.empty()));
    // The openebs zfs-localpv HelmChart installs the CSI driver + its zfs.openebs.io CRDs
    // (ZFSVolume et al.) — the static funnel-cert/maven-cache PVs render ZFSVolume CRs.
    installers.put("zfs.openebs.io", new RuntimeInstaller("storage/openebs-zfs", Optional.empty()));
    // The CAPI operator + the four providers it brings up (rendered by
    // ClusterApiOperatorManifestsUnit) install the Cluster API CRD groups at runtime: CAPI core
    // (cluster.x-k8s.io), the incus infrastructure provider (infrastructure.cluster.x-k8s.io), and
    // CAPRKE2's control-plane + bootstrap providers. The workload CR set
    // (ClusterApiWorkloadManifestsUnit) renders CRs in these groups; its Flux Kustomization already
    // dependsOn the operator cell, so no separate health check.
    final RuntimeInstaller clusterApi =
        new RuntimeInstaller(
            ManifestDomainCatalog.CLUSTER_API + "/" + ClusterApiOperatorManifestsUnit.OUTPUT_DIR,
            Optional.empty());
    installers.put("cluster.x-k8s.io", clusterApi);
    installers.put("infrastructure.cluster.x-k8s.io", clusterApi);
    installers.put("controlplane.cluster.x-k8s.io", clusterApi);
    installers.put("bootstrap.cluster.x-k8s.io", clusterApi);
    return Map.copyOf(installers);
  }

  private final CoherentManifestsDomainRegistry registry;
  private final YamlMapper yaml;

  FluxServiceKustomizationPlanner(
      final CoherentManifestsDomainRegistry registry, final YamlMapper yaml) {
    this.registry = registry;
    this.yaml = yaml;
  }

  /** A rendered service cell — the tree dir {@code <layer>/<domain>/<package>}. */
  private record Cell(String layer, String domain, String pkg) {
    String coord() {
      return domain + "/" + pkg;
    }

    String path() {
      return "./" + layer + "/" + domain + "/" + pkg;
    }
  }

  /** A resource identity for indexing — the {@code apiVersion} group + {@code kind}. */
  private record GroupKind(String group, String kind) {
    static GroupKind of(final String apiVersion, final String kind) {
      final int slash = apiVersion.indexOf('/');
      return new GroupKind(slash < 0 ? "" : apiVersion.substring(0, slash), kind);
    }
  }

  /** A Flux {@code NamespacedObjectKindReference} for {@code spec.healthChecks}. */
  private record HealthCheck(String apiVersion, String kind, String name) {
    GroupKind groupKind() {
      return GroupKind.of(apiVersion, kind);
    }

    Map<String, Object> toMap() {
      return Map.of("apiVersion", apiVersion, "kind", kind, "name", name);
    }
  }

  /**
   * What the doc scan yields: which cells render each {@code (group,kind)}, the coord that renders
   * each CRD (keyed by the group+kind the CRD DEFINES), the cells whose PVC binds WFC, and the
   * cells whose pod templates consume a flox env / nix-build store (annotation {@link
   * #FLOX_CONSUMER_ANNOTATION_PREFIX}).
   */
  private record DocScan(
      Map<GroupKind, Set<Cell>> renderedBy,
      Map<GroupKind, String> crdProviderCoord,
      Set<Cell> wfcPvcCells,
      Set<Cell> floxConsumerCells) {}

  public void plan(final ManifestExplodeResult result) {
    final Path target = result.explodedTargetDir();

    // 1. The non-empty cells, from the written tree paths (<layer>/<domain>/<package>/<file>).
    final Set<Cell> cells = new LinkedHashSet<>();
    for (final Path file : result.writtenFiles()) {
      cellOf(target, file).ifPresent(cells::add);
    }
    if (cells.isEmpty()) {
      return;
    }

    // 2. Scan the written docs: kind index + CRD-provider index + WFC-PVC cells.
    final DocScan scan = scanDocuments(target, result.writtenFiles());

    // 3. coord (<domain>/<package>) -> its unit's dependency ids, from the in-memory graph. A unit
    //    is keyed <domainId>/<outputDir>, and outputDir() is exactly the package dir segment the
    //    exploder wrote the cell into.
    final Map<String, List<String>> depsByCoord = new HashMap<>();
    final Map<String, String> coordByUnitId = new HashMap<>();
    for (final ManifestsUnit unit : registry.visitOrder()) {
      final String unitId = unit.manifestUnitId();
      final String coord =
          registry.requireDomainIdForManifestsUnit(unitId) + "/" + unit.outputDir();
      depsByCoord.put(coord, unit.dependsOnManifestsUnitIds());
      coordByUnitId.put(unitId, coord);
    }

    // 4. Index cells: coord -> its layers (for multi-layer naming + resolving a coord to its
    // cells).
    final Map<String, Set<String>> layersByCoord = new TreeMap<>();
    for (final Cell cell : cells) {
      layersByCoord.computeIfAbsent(cell.coord(), k -> new TreeSet<>()).add(cell.layer());
    }

    // 5. Derive the CRD -> CR edges + the readiness healthChecks from the scan. A CR of each
    //    rendered (group,kind) depends on every cell of its CRD provider's coord; an installer's
    //    readiness resource becomes a healthCheck on the cell that renders it.
    final Map<Cell, Set<String>> derivedEdges = new HashMap<>();
    final Map<Cell, HealthCheck> healthCheckByCell = new HashMap<>();
    scan.renderedBy()
        .forEach(
            (gk, consumers) -> {
              if (gk.kind().equals(CUSTOM_RESOURCE_DEFINITION)
                  || BUILTIN_GROUPS.contains(gk.group())
                  || DISTRO_GROUPS.contains(gk.group())) {
                return; // a provider, a built-in, or a distro-shipped group — no edge to derive
              }
              final String providerCoord = resolveProvider(gk, scan, consumers);
              final Set<String> providerCells = cellNamesOfCoord(providerCoord, layersByCoord);
              if (providerCells.isEmpty()) {
                throw new IllegalStateException(
                    "CR "
                        + gk.group()
                        + "/"
                        + gk.kind()
                        + " resolves to installer coord '"
                        + providerCoord
                        + "', which renders no cell in this tree — the CR outlives its installer");
              }
              for (final Cell consumer : consumers) {
                // Skip only the consumer's OWN cell, NOT its whole coord: when the CRD provider
                // shares the consumer's coord (an operator whose HelmChart sits in the operators
                // layer while its CR sits in workloads — the tailscale Connector), the real
                // cross-layer edge (workloads -> operators) must still be emitted. A coord-level
                // skip suppressed it, so the CR dry-ran before the operator registered its CRD.
                final String consumerName = cellName(consumer, layersByCoord);
                for (final String providerCell : providerCells) {
                  if (!providerCell.equals(consumerName)) {
                    derivedEdges
                        .computeIfAbsent(consumer, k -> new LinkedHashSet<>())
                        .add(providerCell);
                  }
                }
              }
            });
    // A runtime installer's readiness resource (if any) gates the installer cell that renders it.
    for (final RuntimeInstaller installer : Set.copyOf(RUNTIME_INSTALLERS.values())) {
      installer
          .healthCheck()
          .ifPresent(
              hc ->
                  scan.renderedBy()
                      .getOrDefault(hc.groupKind(), Set.of())
                      .forEach(cell -> healthCheckByCell.put(cell, hc)));
    }

    // 6. Emit the three-level tree into ./flux: an explicit kustomization.yaml at each level (so
    //    nothing recurses) → per-DOMAIN Kustomizations → per-SERVICE Kustomizations. The root
    //    (FluxRootManifestsUnit, path ./flux) applies the domain Kustomizations; each domain
    //    Kustomization (path ./flux/<domain>) applies only its services. Ordering stays at the
    //    SERVICE level (the precise edges below), so cross-domain deps by name still hold; the
    //    domain level is ownership/grouping only (flux tree reads by domain).
    final Path fluxDir = target.resolve(FluxRootManifestsUnit.FLUX_DIR);
    try {
      Files.createDirectories(fluxDir);
      final Map<String, Set<String>> serviceFilesByDomain = new TreeMap<>();
      for (final Cell cell : cells) {
        final String name = cellName(cell, layersByCoord);
        final Set<String> dependsOn = new TreeSet<>();

        // (a) service->service edges from the unit graph — every cell of the dep's coord. A dep
        //     with no cell (a node-bootstrap unit, not in the tree) resolves to nothing and is
        //     skipped.
        for (final String depUnitId : depsByCoord.getOrDefault(cell.coord(), List.of())) {
          final String depCoord = coordByUnitId.get(depUnitId);
          if (depCoord != null) {
            dependsOn.addAll(cellNamesOfCoord(depCoord, layersByCoord));
          }
        }
        // (b) derived CRD -> CR edges.
        dependsOn.addAll(derivedEdges.getOrDefault(cell, Set.of()));
        // (c) flox runtime edge: a cell whose pod templates consume a flox env / nix-build store
        //     waits for the flox-controller runtime (webhook + node-agent DaemonSet), since the
        //     webhook's failurePolicy:Ignore cannot itself hold back an un-gated pod. Skip the flox
        //     runtime cells themselves (they carry no consumer annotation, but guard regardless).
        if (scan.floxConsumerCells().contains(cell)
            && !FLOX_RUNTIME_COORDS.contains(cell.coord())) {
          for (final String coord : FLOX_RUNTIME_COORDS) {
            dependsOn.addAll(cellNamesOfCoord(coord, layersByCoord));
          }
        }
        dependsOn.remove(name); // never depend on self

        final Optional<HealthCheck> healthCheck = Optional.ofNullable(healthCheckByCell.get(cell));
        final boolean wait = healthCheck.isEmpty() && !scan.wfcPvcCells().contains(cell);
        final Path domainDir = fluxDir.resolve(cell.domain());
        Files.createDirectories(domainDir);
        Files.writeString(
            domainDir.resolve(name + ".yml"),
            yaml.dump(kustomization(cell, name, dependsOn, wait, healthCheck)));
        serviceFilesByDomain
            .computeIfAbsent(cell.domain(), k -> new TreeSet<>())
            .add(name + ".yml");
      }

      // Per-domain: a kustomization.yaml listing its service files + the domain Kustomization
      // object.
      final Set<String> domainFiles = new TreeSet<>();
      for (final Map.Entry<String, Set<String>> entry : serviceFilesByDomain.entrySet()) {
        final String domain = entry.getKey();
        Files.writeString(
            fluxDir.resolve(domain).resolve("kustomization.yaml"),
            yaml.dump(kustomizeResources(entry.getValue())));
        Files.writeString(fluxDir.resolve(domain + ".yml"), yaml.dump(domainKustomization(domain)));
        domainFiles.add(domain + ".yml");
      }
      // Root: the kustomization.yaml the root Kustomization reads — lists ONLY the domain files, so
      // kustomize never descends into the ./flux/<domain>/ service dirs.
      Files.writeString(
          fluxDir.resolve("kustomization.yaml"), yaml.dump(kustomizeResources(domainFiles)));
      LOG.info(
          "Planned {} services across {} domains under {}",
          cells.size(),
          serviceFilesByDomain.size(),
          fluxDir);
    } catch (final IOException ex) {
      throw new UncheckedIOException("cannot write per-service Flux Kustomizations", ex);
    }
  }

  /** A kustomize {@code kustomization.yaml} listing exactly {@code resources} — no recursion. */
  private Map<String, Object> kustomizeResources(final Set<String> resources) {
    final Map<String, Object> kustomization = new LinkedHashMap<>();
    kustomization.put("apiVersion", "kustomize.config.k8s.io/v1beta1");
    kustomization.put("kind", "Kustomization");
    kustomization.put("resources", List.copyOf(resources));
    return kustomization;
  }

  /**
   * The per-domain Flux {@code Kustomization} — owns the domain's service Kustomizations (path
   * {@code ./flux/<domain>}), no dependsOn (ordering is at the service level) and no decryption (it
   * applies only Kustomization objects). {@code wait: false}: the domain is Ready once it has
   * APPLIED its service Kustomizations, NOT once they are healthy — readiness stays at the service
   * level (each service is {@code wait: true}), consistent with ordering living there too. A {@code
   * wait: true} health roll-up here would make the domain hold a kustomize-controller worker for
   * the full health-check timeout while its services converge; ten domains doing so starve the
   * controller's concurrency so the services never get a reconcile slot — a self-inflicted
   * cold-start near-stall. The per-service verdict in {@code flux get ks} is the honest assessment
   * surface; the domain level is ownership/grouping only.
   */
  private Map<String, Object> domainKustomization(final String domain) {
    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("interval", "5m");
    spec.put("path", "./" + FluxRootManifestsUnit.FLUX_DIR + "/" + domain);
    spec.put("prune", true);
    spec.put("wait", false);
    spec.put(
        "sourceRef",
        Map.of("kind", "GitRepository", "name", FluxRootManifestsUnit.GIT_REPOSITORY_NAME));
    final Map<String, Object> ks = new LinkedHashMap<>();
    ks.put("apiVersion", "kustomize.toolkit.fluxcd.io/v1");
    ks.put("kind", "Kustomization");
    ks.put("metadata", Map.of("name", domain, "namespace", "flux-system"));
    ks.put("spec", spec);
    return ks;
  }

  /**
   * The coord of the cell(s) that provide {@code gk}'s CRD: a cell that RENDERS the CRD, else the
   * declared runtime installer for the group. An unresolved CR fails the render — it is neither
   * rendered, nor installed by a known operator, nor a built-in/distro group.
   */
  private String resolveProvider(
      final GroupKind gk, final DocScan scan, final Set<Cell> consumers) {
    final String rendered = scan.crdProviderCoord().get(gk);
    if (rendered != null) {
      return rendered;
    }
    final RuntimeInstaller installer = RUNTIME_INSTALLERS.get(gk.group());
    if (installer != null) {
      return installer.coord();
    }
    throw new IllegalStateException(
        "No CRD provider for CR "
            + gk.group()
            + "/"
            + gk.kind()
            + " (rendered e.g. in "
            + consumers.stream().map(Cell::coord).sorted().findFirst().orElse("?")
            + ") — add its group to RUNTIME_INSTALLERS (operator/HelmChart install), render its CRD,"
            + " or add it to BUILTIN_GROUPS / DISTRO_GROUPS if the API server already serves it");
  }

  /** The {@code (layer, domain, package)} cell a written file belongs to, if it is a cell leaf. */
  private Optional<Cell> cellOf(final Path target, final Path file) {
    final Path rel = target.relativize(file);
    if (rel.getNameCount() < 4) {
      return Optional.empty(); // not a <layer>/<domain>/<package>/<file> leaf (e.g. .gitattributes)
    }
    return Optional.of(
        new Cell(rel.getName(0).toString(), rel.getName(1).toString(), rel.getName(2).toString()));
  }

  /** The child-Kustomization names of every cell (all layers) of a coord present in the tree. */
  private Set<String> cellNamesOfCoord(
      final String coord, final Map<String, Set<String>> layersByCoord) {
    final Set<String> layers = layersByCoord.get(coord);
    if (layers == null) {
      return Set.of();
    }
    final int slash = coord.indexOf('/');
    final String domain = coord.substring(0, slash);
    final String pkg = coord.substring(slash + 1);
    return layers.stream()
        .map(layer -> cellName(new Cell(layer, domain, pkg), layersByCoord))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Scan every written doc once: build the {@code (group,kind) -> cells} index (for CRD-provider
   * edges + readiness), the {@code (definedGroup,definedKind) -> coord} index of rendered CRDs, and
   * — by reading each {@code StorageClass}'s binding mode + default class — the cells whose PVC
   * binds {@code WaitForFirstConsumer}.
   */
  private DocScan scanDocuments(final Path target, final List<Path> writtenFiles) {
    final Map<GroupKind, Set<Cell>> renderedBy = new HashMap<>();
    final Map<GroupKind, String> crdProviderCoord = new HashMap<>();
    final Map<String, String> bindingModeByClass = new HashMap<>();
    final Set<String> defaultClasses = new LinkedHashSet<>();
    final Set<Cell> floxConsumerCells = new LinkedHashSet<>();
    // A PVC may be scanned before the StorageClass it references, so collect (cell, explicit class)
    // and resolve WFC after the full StorageClass picture is known.
    record PvcRef(Cell cell, Optional<String> explicitClass) {}
    final List<PvcRef> pvcRefs = new ArrayList<>();

    for (final Path file : writtenFiles) {
      final Optional<Cell> cell = cellOf(target, file);
      if (cell.isEmpty() || !file.getFileName().toString().endsWith(".yml")) {
        continue;
      }
      for (final JsonNode doc : yaml.read(file).nodes().toList()) {
        final String kind = doc.path("kind").asText("");
        if (kind.isEmpty()) {
          continue;
        }
        if (rendersFloxConsumer(doc, kind)) {
          floxConsumerCells.add(cell.get());
        }
        renderedBy
            .computeIfAbsent(
                GroupKind.of(doc.path("apiVersion").asText(""), kind), k -> new LinkedHashSet<>())
            .add(cell.get());
        switch (kind) {
          case CUSTOM_RESOURCE_DEFINITION ->
              crdProviderCoord.put(
                  new GroupKind(
                      doc.path("spec").path("group").asText(""),
                      doc.path("spec").path("names").path("kind").asText("")),
                  cell.get().coord());
          case STORAGE_CLASS -> {
            final String scName = doc.path("metadata").path("name").asText("");
            bindingModeByClass.put(scName, doc.path("volumeBindingMode").asText(""));
            if (doc.path("metadata")
                .path("annotations")
                .path(IS_DEFAULT_CLASS)
                .asText("")
                .equals("true")) {
              defaultClasses.add(scName);
            }
          }
          case PERSISTENT_VOLUME_CLAIM -> {
            final JsonNode explicit = doc.path("spec").path("storageClassName");
            pvcRefs.add(
                new PvcRef(
                    cell.get(),
                    explicit.isMissingNode() ? Optional.empty() : Optional.of(explicit.asText())));
          }
          default -> {}
        }
      }
    }

    final Optional<String> defaultClass = defaultClasses.stream().findFirst();
    final Set<Cell> wfcPvcCells = new LinkedHashSet<>();
    for (final PvcRef ref : pvcRefs) {
      ref.explicitClass()
          .or(() -> defaultClass)
          .flatMap(sc -> Optional.ofNullable(bindingModeByClass.get(sc)))
          .filter(WAIT_FOR_FIRST_CONSUMER::equals)
          .ifPresent(mode -> wfcPvcCells.add(ref.cell()));
    }
    return new DocScan(renderedBy, crdProviderCoord, wfcPvcCells, floxConsumerCells);
  }

  /**
   * Whether {@code doc}'s pod template carries a {@link #FLOX_CONSUMER_ANNOTATION_PREFIX}
   * annotation — the marker the flox NRI plugin + controller webhook key on. Reads the annotation
   * node at the one location that holds a pod template for the kind (bare {@code Pod}, {@code
   * CronJob}'s nested job template, else the {@code spec.template} every workload controller and
   * {@code Job} share).
   */
  private boolean rendersFloxConsumer(final JsonNode doc, final String kind) {
    final JsonNode annotations =
        switch (kind) {
          case "Pod" -> doc.path("metadata").path("annotations");
          case "CronJob" ->
              doc.path("spec")
                  .path("jobTemplate")
                  .path("spec")
                  .path("template")
                  .path("metadata")
                  .path("annotations");
          default -> doc.path("spec").path("template").path("metadata").path("annotations");
        };
    if (!annotations.isObject()) {
      return false;
    }
    final Iterator<String> names = annotations.fieldNames();
    while (names.hasNext()) {
      final String key = names.next();
      if (FLOX_CONSUMER_KEY_PREFIXES.stream().anyMatch(key::startsWith)) {
        return true;
      }
    }
    return false;
  }

  /** {@code <domain>-<package>}, plus a {@code -<layer>} suffix when the service spans layers. */
  private String cellName(final Cell cell, final Map<String, Set<String>> layersByCoord) {
    final String base = cell.domain() + "-" + cell.pkg();
    final Set<String> layers = layersByCoord.get(cell.coord());
    return layers != null && layers.size() > 1 ? base + "-" + cell.layer() : base;
  }

  private Map<String, Object> kustomization(
      final Cell cell,
      final String name,
      final Set<String> dependsOn,
      final boolean wait,
      final Optional<HealthCheck> healthCheck) {
    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("interval", "5m");
    // While NotReady (the cold-start state), Flux re-queues after retryInterval, NOT interval —
    // and a Kustomization only re-checks its dependsOn on its own tick, so each dependency LEVEL
    // costs up to one tick. With the default (retryInterval = interval = 5m), a healthy object is
    // detected up to 5m late and a deep dependsOn chain (e.g. a flox consumer →flox-runtime
    // →cluster-issuer →cert-manager) compounds a tick per level — the cold-start cascade measured
    // at
    // ~11m of pure polling though the objects were healthy in ~2m. A short retryInterval collapses
    // that to seconds-per-level; interval stays 5m so healthy resources are not re-polled hot.
    spec.put("retryInterval", "30s");
    spec.put("path", cell.path());
    spec.put("prune", true);
    spec.put("wait", wait);
    // Recreate immutable resources whose apply would otherwise fail. A service cell may render a
    // one-shot Job (e.g. the funnel-cert restore/backup) whose pod template changes across renders;
    // Job.spec.template is immutable, so a plain server-side apply fails "field is immutable" and
    // wedges the whole cell (Ready=false) until an operator deletes the Job by hand. force makes
    // Flux delete-and-recreate on that failure. A no-op for normally-patchable resources — Flux
    // only
    // forces when an apply actually fails on immutability.
    spec.put("force", true);
    spec.put(
        "sourceRef",
        Map.of("kind", "GitRepository", "name", FluxRootManifestsUnit.GIT_REPOSITORY_NAME));
    // Each child carries its own sops decryption — the root applies only these Kustomization
    // objects, but a child's own <layer>/<domain>/<package> dir may hold a sops-filtered Secret.
    spec.put(
        "decryption",
        Map.of(
            "provider",
            "sops",
            "secretRef",
            Map.of("name", FluxRootManifestsUnit.SOPS_AGE_SECRET)));
    if (!dependsOn.isEmpty()) {
      spec.put("dependsOn", dependsOn.stream().map(n -> Map.of("name", n)).toList());
    }
    // An explicit healthCheck (with wait:false) gates the installer cell on its readiness resource
    // only — Flux ignores healthChecks when wait is true, and wait:true would instead health-gate
    // every applied object (Namespace/Secret/…), missing the CRD-install signal.
    healthCheck.ifPresent(hc -> spec.put("healthChecks", List.of(hc.toMap())));
    final Map<String, Object> ks = new LinkedHashMap<>();
    ks.put("apiVersion", "kustomize.toolkit.fluxcd.io/v1");
    ks.put("kind", "Kustomization");
    ks.put("metadata", Map.of("name", name, "namespace", "flux-system"));
    ks.put("spec", spec);
    return ks;
  }
}
