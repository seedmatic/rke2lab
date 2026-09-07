package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import io.seedmatic.rke2lab.manifests.cdk8s.Cdk8sApps;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeService;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.seedmatic.rke2lab.manifests.contract.profiles.SigningKeyMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.seedmatic.rke2lab.manifests.internal.synthesis.OnFailure;
import io.seedmatic.rke2lab.manifests.internal.synthesis.Phase;
import io.seedmatic.rke2lab.manifests.internal.synthesis.PhaseRunner;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default SPI implementation for canonical manifest synthesis. */
@Component(service = ManifestSynthesisService.class)
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

  /**
   * The OSGi resolver SCR binds from the registry — felix.resolver's service, registered by its
   * bundle activator. Threaded into {@link ManifestsDomainRegistry#resolve(Resolver)}, the single
   * coherence gate, so this component never reaches into the resolver's impl package.
   */
  @Reference private Resolver resolver;

  /**
   * The domain registrars, SCR-collected — the unit contribution channel (cardinality MULTIPLE).
   * Each registrar contributes one manifest domain with its units; {@link #buildDomainRegistry}
   * iterates them as {@code domain(policy)} at build time, so a registrar stays a stateless
   * singleton and the run policy is a call-time argument.
   */
  @Reference(cardinality = ReferenceCardinality.MULTIPLE)
  private List<ManifestsDomainRegistrar> domainRegistrars;

  /**
   * The deterministic YAML service, threaded into each unit's context (units are not components).
   */
  @Reference private YamlMapper yaml;

  /**
   * The explode service — splits the consolidated {@code manifests.yaml} into the per-resource tree
   * the node consumes ({@code <domain>/<package>/…}). {@code local-config} resources land as hidden
   * dotfiles the RKE2 auto-deploy skips but host consumers read (e.g. the incus scion's NoCloud
   * seed from {@code runtime/cloud-config}). Run at synth time (the exploded tree is the applied
   * source; the consolidated file stays for traceability, no other consumer).
   */
  @Reference private ManifestExplodeService explodeService;

  /**
   * The ssh-to-age edge, bound from the registry. Mandatory: without the edge the component never
   * activates, so {@code ManifestSynthesisService} never publishes — a missing edge fails the boot
   * fast rather than silently half-rendering the sops-age Secret. The pre-synthesis step calls it.
   */
  @Reference private SshToAgeConverter sshToAgeConverter;

  @Reference private NdhKeystoreReader ndhKeystore;

  // The ambient enclosure gate (host-published, § pac-in-cluster-render-spec auth). OPTIONAL +
  // DYNAMIC/GREEDY, not mandatory: the host publishes it AFTER this standing component activates,
  // so
  // a mandatory reference would never bind; the dynamic field is set when the host registers it,
  // before synthesize() runs. Absent (a bare survey / test that publishes none) → OPERATOR default:
  // read the key-store, the pre-existing behaviour.
  @Reference(
      cardinality = ReferenceCardinality.OPTIONAL,
      policy = ReferencePolicy.DYNAMIC,
      policyOption = ReferencePolicyOption.GREEDY)
  private volatile Optional<EnclosureGate> enclosureGate = Optional.empty();

  static final Set<String> SCRIPT_DATA_SUFFIXES =
      Set.of(".sh", ".bash", ".env", ".yaml", ".yml", ".conf", ".policy");

  static final TypeReference<Map<String, Object>> DOCUMENT_TYPE = new TypeReference<>() {};

  @Override
  public String providerId() {
    return "default-cdk8s-synthesizer";
  }

  @Override
  public ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException {
    LOG.info(
        "Starting manifests synthesis via provider '{}' (floxDebugPolicy={})",
        providerId(),
        request.floxDebugPolicy());

    // Pre-synthesis step: resolve the age key (read the SSH key from the key-store, convert it via
    // the ssh-to-age edge) BEFORE binding the context, so units only render — synthesis takes its
    // prerequisites, it does not fetch them. Gated on the enclosure: IN_CLUSTER the sops-age Secret
    // is a NODE_BOOTSTRAP bootstrap artifact already applied at the operator's grow (and the git
    // tree's key-store is sops-encrypted at rest), so the resolver skips rather than reads.
    final Optional<EnclosureGate> enclosure = enclosureGate;
    final Optional<SopsAgeMaterial> sopsAgeMaterial =
        new SopsAgeMaterialResolver(sshToAgeConverter, ndhKeystore, enclosure).resolve();
    // The commit-signing key, resolved the same way (OPERATOR-only, § pac-in-cluster-render-spec):
    // the operator's grow emits the manifests-render-signing Secret the in-cluster render mounts.
    final Optional<SigningKeyMaterial> signingKeyMaterial =
        new SigningKeyMaterialResolver(ndhKeystore, enclosure).resolve();

    final var contextScope =
        ManifestSynthesisContext.of(request, sopsAgeMaterial, signingKeyMaterial).bind();
    try (contextScope) {
      return synthesizeInContext(request);
    }
  }

  ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request) throws IOException {
    // Local pipeline for the manifest synthesis workflow (fluent synthesis grammar).
    // Structure: setup → registry → synthesis
    final class SynthesisPipeline {
      final ManifestSynthesisRequest request;
      final PhaseRunner runner = new PhaseRunner("manifest-synthesis");

      SynthesisPipeline(ManifestSynthesisRequest request) {
        this.request = request;
      }

      AwaitingOnFailure onFailure(OnFailure handler) {
        return new AwaitingOnFailure(new State(request, handler));
      }

      /** Cdk8s app, chart and output paths — produced once by the setup stage. */
      record Scaffold(App app, Chart chart, Path synthOutdir, Path synthManifestFile) {}

      /** Configured domain registry and its unit hit count — produced by the registry stage. */
      record Registry(
          ManifestsDomainRegistry domainRegistry,
          CoherentManifestsDomainRegistry coherent,
          int manifestUnitHitCount) {}

      /**
       * Working memory shared across pipeline stages. Each phase produces one immutable record; the
       * type-state chain guarantees a record is set before a later stage reads it, so the narrowing
       * accessors never fail. These two slots are the only mutable state.
       */
      final class State {
        final ManifestSynthesisRequest request;
        final OnFailure onFailure;

        @MonotonicNonNull Scaffold scaffold;
        @MonotonicNonNull Registry registry;

        State(ManifestSynthesisRequest request, OnFailure onFailure) {
          this.request = request;
          this.onFailure = onFailure;
        }

        Scaffold scaffold() {
          return Objects.requireNonNull(scaffold, "cdk8s scaffold not yet produced");
        }

        Registry registry() {
          return Objects.requireNonNull(registry, "domain registry not yet produced");
        }
      }

      final class AwaitingOnFailure {
        final State state;

        AwaitingOnFailure(State state) {
          this.state = state;
        }

        Cdk8sSetupDone during(String phase, Function<Cdk8sSetupPhase, Cdk8sSetupPhase> body) {
          final Cdk8sSetupPhase stage =
              new Cdk8sSetupPhase(state, scaffold -> state.scaffold = scaffold);
          runner.runDuring(phase, stage, body, state.onFailure);
          return new Cdk8sSetupDone(state);
        }
      }

      final class Cdk8sSetupPhase implements Phase.Execution {
        final State state;
        final Sink sink;

        Cdk8sSetupPhase(State state, Sink sink) {
          this.state = state;
          this.sink = sink;
        }

        /** The write-face of the cdk8s-setup phase. */
        interface Sink extends Phase.Sink {
          void scaffold(Scaffold scaffold);
        }

        @Override
        public String role() {
          return "cdk8s setup";
        }

        Cdk8sSetupPhase createChartsAndPaths() {
          final Path synthOutdir = state.request.synthOutdir();
          final Path synthManifestFile = state.request.synthManifestFile();

          final App app =
              Cdk8sApps.create(AppProps.builder().outdir(synthOutdir.toString()).build());
          final Chart chart = new Chart(app, "manifests");

          sink.scaffold(new Scaffold(app, chart, synthOutdir, synthManifestFile));

          return this;
        }
      }

      final class Cdk8sSetupDone {
        final State state;

        Cdk8sSetupDone(State state) {
          this.state = state;
        }

        AwaitingDomainRegistry then() {
          return new AwaitingDomainRegistry(state);
        }
      }

      final class AwaitingDomainRegistry {
        final State state;

        AwaitingDomainRegistry(State state) {
          this.state = state;
        }

        DomainRegistryDone during(
            String phase, Function<DomainRegistryPhase, DomainRegistryPhase> body) {
          final DomainRegistryPhase stage =
              new DomainRegistryPhase(state, registry -> state.registry = registry);
          runner.runDuring(phase, stage, body, state.onFailure);
          return new DomainRegistryDone(state);
        }
      }

      final class DomainRegistryPhase implements Phase.Execution {
        final State state;
        final Sink sink;

        DomainRegistryPhase(State state, Sink sink) {
          this.state = state;
          this.sink = sink;
        }

        /** The write-face of the domain-registry phase. */
        interface Sink extends Phase.Sink {
          void registry(Registry registry);
        }

        @Override
        public String role() {
          return "domain registry";
        }

        DomainRegistryPhase buildAndApplyUnits() {
          final ManifestsDomainRegistry configuredDomainRegistry =
              buildDomainRegistry(state.request.manifestDomainPolicy());

          final ManifestsDomainRegistry domainRegistry =
              applyManifestDomainPolicy(state.request, configuredDomainRegistry);

          final Cdk8sApiObjectResolver resolver =
              new Cdk8sApiObjectResolver(state.scaffold().chart());
          final ManifestsUnitVisitor manifestUnitVisitor = new ApplyingManifestsUnitVisitor();

          LOG.info("Configured {} manifest domains", domainRegistry.domains().size());
          LOG.debug(
              "Manifest domains: {}",
              domainRegistry.domains().stream().map(ManifestsDomain::domainId).sorted().toList());

          final CoherentManifestsDomainRegistry coherent =
              domainRegistry.resolve(DefaultManifestSynthesisService.this.resolver);

          // The run-scoped policy + node environment: built ONCE from the request's handed-over
          // identity (the single source of the cluster name) and threaded to every unit through its
          // context — no unit re-derives it, no compile-time literal.
          final ManifestDomainPolicy runPolicy =
              state
                  .request
                  .manifestDomainPolicy()
                  .orElseGet(() -> new ManifestDomainPolicy(java.util.Map.of()));
          final NodeEnvContext nodeEnvContext =
              new DefaultNodeEnvContext(state.request.bootstrapIdentity());

          int manifestUnitHitCount = 0;
          for (ManifestsUnit manifestUnit : coherent.visitOrder()) {
            manifestUnitHitCount++;
            final String manifestUnitId = manifestUnit.manifestUnitId();
            LOG.debug("Applying manifest unit '{}'", manifestUnitId);
            final String domainId = coherent.requireDomainIdForManifestsUnit(manifestUnitId);
            manifestUnitVisitor.visit(
                manifestUnit,
                new ManifestsUnitContext(
                    state.scaffold().chart(),
                    domainId,
                    manifestUnitId,
                    resolver,
                    runPolicy,
                    nodeEnvContext,
                    DefaultManifestSynthesisService.this.yaml));
          }

          sink.registry(new Registry(domainRegistry, coherent, manifestUnitHitCount));

          return this;
        }
      }

      final class DomainRegistryDone {
        final State state;

        DomainRegistryDone(State state) {
          this.state = state;
        }

        AwaitingSynthesis then() {
          return new AwaitingSynthesis(state);
        }
      }

      final class AwaitingSynthesis {
        final State state;

        AwaitingSynthesis(State state) {
          this.state = state;
        }

        SynthesisDone during(String phase, Function<SynthesisPhase, SynthesisPhase> body) {
          final SynthesisPhase stage = new SynthesisPhase(state);
          runner.runDuring(phase, stage, body, state.onFailure);
          return new SynthesisDone(state);
        }
      }

      final class SynthesisPhase implements Phase.Execution {
        final State state;

        SynthesisPhase(State state) {
          this.state = state;
        }

        @Override
        public String role() {
          return "synthesis";
        }

        SynthesisPhase synthAndPostprocess() {
          final Scaffold scaffold = state.scaffold();
          try {
            LOG.info(
                "Calling app.synth() to synthesize K8s manifests to: {}", scaffold.synthOutdir());
            scaffold.app().synth();

            final Path synthesizedFile = getSynthesizedFile();

            enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

            Files.createDirectories(scaffold.synthManifestFile().getParent());
            Files.move(
                synthesizedFile, scaffold.synthManifestFile(), StandardCopyOption.REPLACE_EXISTING);

            LOG.info(
                "Synthesized K8s manifests from canonical manifest units (manifest unit hits={})",
                state.registry().manifestUnitHitCount());

            // Explode the consolidated manifest into the per-resource tree the node consumes and
            // the host artifacts read: cluster-apply resources become visible files (RKE2
            // auto-deploys them), local-config resources become hidden dotfiles (skipped by apply,
            // read by host consumers — e.g. runtime/cloud-config/.configmap-cloud-config.yml, the
            // NoCloud seed the incus scion unwraps). Into synthOutdir.
            final ManifestExplodeResult explodeResult =
                explodeService.explode(
                    new ManifestExplodeRequest(
                        scaffold.synthManifestFile(), scaffold.synthOutdir()));
            LOG.info(
                "Exploded consolidated manifest into the per-resource tree at {}",
                scaffold.synthOutdir());

            // Post-explode: plan one Flux Kustomization per service (layer,domain,package) cell
            // into ./flux — the root Kustomization (FluxRootManifestsUnit) reconciles them. The
            // exploder knows the non-empty cells (writtenFiles); the coherent registry carries the
            // dependsOn graph. See FluxServiceKustomizationPlanner.
            new FluxServiceKustomizationPlanner(
                    state.registry().coherent(), DefaultManifestSynthesisService.this.yaml)
                .plan(explodeResult);

            return this;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }

        private Path getSynthesizedFile() throws IOException {
          final Path synthOutdir = state.scaffold().synthOutdir();
          try (var files = Files.list(synthOutdir)) {
            return files
                .filter(
                    p -> {
                      final String name = p.getFileName().toString();
                      return name.endsWith("-manifests.k8s.yaml")
                          || name.equals("manifests.k8s.yaml");
                    })
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Expected synthesized manifest file (manifests.k8s.yaml or"
                                + " *-manifests.k8s.yaml) is missing in: "
                                + synthOutdir));
          }
        }
      }

      final class SynthesisDone {
        final State state;

        SynthesisDone(State state) {
          this.state = state;
        }

        ManifestSynthesisResult complete() {
          final Scaffold scaffold = state.scaffold();
          final Registry registry = state.registry();
          return new ManifestSynthesisResult(
              scaffold.synthManifestFile(),
              registry.manifestUnitHitCount(),
              registry.domainRegistry().domains().size());
        }
      }
    }
    return new SynthesisPipeline(request)
        .onFailure((phase, cause) -> LOG.error("Synthesis failed in phase '{}'", phase, cause))
        .during("cdk8s setup", setup -> setup.createChartsAndPaths())
        .then()
        .during("domain registry", registry -> registry.buildAndApplyUnits())
        .then()
        .during("synthesis", synthesis -> synthesis.synthAndPostprocess())
        .complete();
  }

  ManifestsDomainRegistry buildDomainRegistry(Optional<ManifestDomainPolicy> policy) {
    final ManifestDomainPolicy effectivePolicy =
        policy.orElseGet(() -> ManifestDomainPolicy.builder().build());

    final List<ManifestsDomain> domains =
        domainRegistrars.stream()
            .map(registrar -> registrar.domain(effectivePolicy))
            .sorted(Comparator.comparing(ManifestsDomain::domainId))
            .toList();
    return new ManifestsDomainRegistry(domains);
  }

  ManifestsDomainRegistry applyManifestDomainPolicy(
      ManifestSynthesisRequest request, ManifestsDomainRegistry configuredDomainRegistry) {
    if (request.manifestDomainPolicy().isEmpty()) {
      return configuredDomainRegistry;
    }

    final ManifestDomainPolicy manifestDomainPolicy = request.manifestDomainPolicy().orElseThrow();
    final Map<String, ManifestsDomain> configuredDomainsById =
        configuredDomainRegistry.domains().stream()
            .collect(
                Collectors.toMap(
                    ManifestsDomain::domainId,
                    domain -> domain,
                    (left, right) -> left,
                    LinkedHashMap::new));

    final List<String> unknownDomainIds =
        manifestDomainPolicy.domainIds().stream()
            .filter(domainId -> !configuredDomainsById.containsKey(domainId))
            .sorted()
            .toList();
    if (!unknownDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy references domains unsupported by provider '"
              + providerId()
              + "': "
              + unknownDomainIds);
    }

    final LinkedHashSet<String> requestedEnabledDomainIds =
        new LinkedHashSet<>(manifestDomainPolicy.enabledDomainIds());
    if (requestedEnabledDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy disables all domains for provider '" + providerId() + "'.");
    }

    final LinkedHashSet<String> effectiveDomainIds = new LinkedHashSet<>();
    for (String requestedDomainId : requestedEnabledDomainIds) {
      collectDomainDependencies(requestedDomainId, configuredDomainsById, effectiveDomainIds);
    }

    LOG.info(
        "Applying manifest-domain policy: requested domains={}, effective domains={}",
        requestedEnabledDomainIds,
        effectiveDomainIds);

    final List<ManifestsDomain> filteredDomains =
        configuredDomainRegistry.domains().stream()
            .filter(domain -> effectiveDomainIds.contains(domain.domainId()))
            .toList();
    return new ManifestsDomainRegistry(filteredDomains);
  }

  void collectDomainDependencies(
      String domainId,
      Map<String, ManifestsDomain> configuredDomainsById,
      Set<String> effectiveDomainIds) {
    if (!effectiveDomainIds.add(domainId)) {
      return;
    }

    final ManifestsDomain domain = configuredDomainsById.get(domainId);
    if (domain == null) {
      throw new IllegalArgumentException(
          "Unknown manifest domain in policy resolution: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      collectDomainDependencies(dependencyDomainId, configuredDomainsById, effectiveDomainIds);
    }
  }

  void enforceLiteralBlockStyleForConfigMapScripts(Path synthesizedFile) {
    final List<Map<String, Object>> documents =
        yaml.read(synthesizedFile).as(DOCUMENT_TYPE).map(this::normalizeConfigMapScripts).toList();
    yaml.write(synthesizedFile).documents(documents);
  }

  Map<String, Object> normalizeConfigMapScripts(Map<String, Object> document) {
    if (!"ConfigMap".equals(document.get("kind"))) {
      return document;
    }
    final Object data = document.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return document;
    }

    final LinkedHashMap<String, Object> rewrittenData = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
      final Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key instanceof String dataKey
          && value instanceof String textValue
          && isScriptLikeConfigMapKey(dataKey)) {
        rewrittenData.put(dataKey, normalizeScriptConfigMapText(textValue));
      } else if (key instanceof String dataKey) {
        rewrittenData.put(dataKey, value);
      }
    }
    document.put("data", rewrittenData);
    return document;
  }

  boolean isScriptLikeConfigMapKey(String dataKey) {
    final String key = dataKey.toLowerCase(Locale.ROOT);
    return SCRIPT_DATA_SUFFIXES.stream().anyMatch(key::endsWith) || key.contains("script");
  }

  String normalizeScriptConfigMapText(String textValue) {
    String normalized = textValue.replace("\r\n", "\n").replace("\r", "\n");
    if (!normalized.contains("\n") && normalized.contains("\\n")) {
      normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n");
    }
    if (!normalized.endsWith("\n")) {
      normalized = normalized + "\n";
    }
    return normalized;
  }
}
