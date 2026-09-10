package io.seedmatic.rke2lab.host.runtime;

import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ambient execution environment — the instance that OWNS a process's environment and resolves
 * the runtime facts + projections that depend on it: the {@link ExecutionEnclosure} it runs in, and
 * (projected from that) the {@link SecretsGateway} chain it sources secrets from. An instance, not
 * a static helper: it holds the environment it reads, is passed through the call graph, and is
 * trivially substituted in a test (construct it over a fixed map). Future container-dependent
 * concerns (push auth, telemetry) add member methods here, resolved from the same held environment.
 *
 * <p>Enclosure detection is detect-but-let-yourself-be-contradicted: an explicit override ({@code
 * RKE2LAB_EXECUTION_ENCLOSURE=operator|in-cluster}) wins; otherwise the kubelet signal {@code
 * KUBERNETES_SERVICE_HOST} — injected into every pod, absent everywhere else — decides. The secrets
 * projection:
 *
 * <ul>
 *   <li>{@link ExecutionEnclosure#OPERATOR} → ndh's provisioned OAuth client ({@code tailscale})
 *       chained ahead of the operator's {@code .secrets} (everything else) — the standalone chain.
 *   <li>{@link ExecutionEnclosure#IN_CLUSTER} → a {@link DotSecretsGateway} over {@code .secrets}:
 *       the in-cluster render is secret-FULL, because the render pod carries the {@code
 *       toolchains/git-sops} flox env + the replicated cluster age key, so it re-smudges {@code
 *       .secrets} and reads it exactly as the operator does (no {@code TailscaleOauthClientGateway}
 *       — the ndh-provisioned OAuth client is operator-only; in-cluster the tailnet OAuth rides the
 *       replicated {@code operator-oauth} Secret).
 * </ul>
 */
public final class ExecutionEnvironment {

  /** The override env var; when set to {@code operator} or {@code in-cluster} it wins. */
  public static final String OVERRIDE_ENV = "RKE2LAB_EXECUTION_ENCLOSURE";

  /** The kubelet-injected signal present in every pod, absent everywhere else. */
  public static final String KUBERNETES_SIGNAL = "KUBERNETES_SERVICE_HOST";

  private final Map<String, String> env;

  public ExecutionEnvironment(final Map<String, String> env) {
    this.env = Map.copyOf(env);
  }

  /** The enclosure this process runs in, resolved from the held environment. */
  public ExecutionEnclosure enclosure() {
    final String override = env.get(OVERRIDE_ENV);
    if (override != null && !override.isBlank()) {
      return switch (override.strip().toLowerCase(Locale.ROOT)) {
        case "in-cluster", "in_cluster", "cluster" -> ExecutionEnclosure.IN_CLUSTER;
        case "operator", "standalone", "local" -> ExecutionEnclosure.OPERATOR;
        default ->
            throw new IllegalArgumentException(
                OVERRIDE_ENV + " must be 'operator' or 'in-cluster', got: " + override);
      };
    }
    final String kubernetes = env.get(KUBERNETES_SIGNAL);
    return kubernetes != null && !kubernetes.isBlank()
        ? ExecutionEnclosure.IN_CLUSTER
        : ExecutionEnclosure.OPERATOR;
  }

  /** The secrets gateway chain this environment's enclosure sources its secrets from. */
  public SecretsGateway secretsGateway() {
    return switch (enclosure()) {
      case OPERATOR ->
          new ChainedSecretsGateway(
              List.of(new TailscaleOauthClientGateway(), new DotSecretsGateway()));
      // Secret-FULL in-cluster: the render pod carries the toolchains/git-sops flox env + the
      // replicated cluster age key (SOPS_AGE_KEY), so the render re-smudges `.secrets` and this
      // gateway reads the CLEAR file — the same source the operator uses. NO
      // TailscaleOauthClientGateway: that reads the operator's ndh-provisioned OAuth client, which
      // is not present in-cluster (the tailnet OAuth arrives via the replicated operator-oauth
      // Secret, not `.secrets`).
      case IN_CLUSTER -> new DotSecretsGateway();
    };
  }

  /**
   * The ambient enclosure gate the host publishes into the framework — the named seam projection of
   * {@link #enclosure()} the scion resolves (twin of the {@code RunGate} the run mode projects).
   * The fact stays this host type; only this seam query crosses.
   */
  public EnclosureGate enclosureGate() {
    final boolean inCluster = enclosure().inCluster();
    return () -> inCluster;
  }
}
