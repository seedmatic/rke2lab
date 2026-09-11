package io.seedmatic.rke2lab.clusterpki.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiCoordinate;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiSealInput;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.clusterpki.contract.WorkloadClusterCas;
import io.seedmatic.rke2lab.clusterpki.core.ClusterSeal;
import io.seedmatic.rke2lab.clusterpki.core.SealedClusterPki;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.Reach;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The cluster-PKI seal scion — the in-container {@code @SeedScenario} the host sows ONCE per
 * cluster (the crossing before provisioning), in the cluster-pki domain's own register. It mints
 * the deterministic CA rooted on the operator's ndh {@code mammoth-skate-tls} root and files it for
 * the host GROW to pose over devlxd.
 *
 * <p>GIVEN the operator's root of trust; WHEN the CA is sealed (the fabrication — idempotent: a
 * prior grow's bundle in the cellar means the CA already exists, so it is KEPT, never re-minted,
 * and the CA stays stable across the destroy/recreate loop); THEN the PKI is filed — {@link
 * ClusterCaBundle} {@code PLAIN} (it is already sops-sealed) and {@link
 * io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey} {@code SEALED} (the {@code CellarCipher},
 * so the decryption key never rests in the clear). The G/W/T rule holds: the WHEN fabricates, the
 * THEN seals to the cellar.
 *
 * <p>It takes NO host input — {@link ClusterSeal} reads {@code keys.yaml} / {@code .sops.yaml}
 * in-container. The two external seams are {@code @OsgiService}-injected from THIS bundle's
 * registry ({@link SshToAgeConverter}, realised by ssh-to-age-edge; {@link SopsEncryptor}, by
 * sops-edge) and handed to {@code ClusterSeal} (instance-passing). The seal is a pure computation
 * (crypto + local tool reads), so it runs in both preview and live; the cellar routes the store
 * (pre-reserve vs conserve). See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedScenario
public class ClusterPkiSealScenario
    extends ScenarioTestBase<
        ClusterPkiSealScenario.Given, ClusterPkiSealScenario.When, ClusterPkiSealScenario.Then>
    implements CellarReceiver<ScenarioCellar>,
        InputReceiver<ClusterPkiSealInput>,
        ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler seeds the {@link ClusterPkiSealInput} through — the
   * workload cluster names the WORKLOAD_TARGETS amendment bound. Absent (no amendment) → the scion
   * mints only the mgmt CA.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<ClusterPkiSealInput> INPUT =
      new ScenarioInputSeed<>(ClusterPkiSealInput.class, "cluster-pki-seal-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  /**
   * Injected by {@code ScenarioCellarExtension} before the body (store→tag, durable fallthrough).
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  /** The seal's one host-held input — null when sown with no WORKLOAD_TARGETS amendment. */
  @MonotonicNonNull private ClusterPkiSealInput input;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The collaborators the seal drives — injected from THIS bundle's registry. */
  @OsgiService private Optional<NdhKeystoreReader> keystore = Optional.empty();

  @OsgiService private Optional<SshToAgeConverter> sshToAge = Optional.empty();

  @OsgiService private Optional<SopsEncryptor> encryptor = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Override
  public void receiveInput(ClusterPkiSealInput input) {
    this.input = input;
  }

  @Test
  void the_cluster_ca_is_sealed_once() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    final List<String> workloadClusters = input == null ? List.of() : input.workloadClusters();
    given().the_operators_root_of_trust();
    when()
        .the_cluster_ca_is_sealed(
            plot,
            tx,
            keystore.orElseThrow(() -> new IllegalStateException("no NdhKeystoreReader")),
            sshToAge.orElseThrow(() -> new IllegalStateException("no SshToAgeConverter edge")),
            encryptor.orElseThrow(() -> new IllegalStateException("no SopsEncryptor edge")),
            workloadClusters);
    then().the_cluster_pki_is_filed(plot, tx);
  }

  /**
   * GIVEN — the operator's root of trust is present (narration; the seal reads it in-container).
   */
  public static class Given extends Stage<Given> {

    public Given the_operators_root_of_trust() {
      return self();
    }
  }

  /**
   * WHEN — the CA is sealed (idempotent). Fresh cluster: mint the whole PKI. Existing cluster (a
   * cellar hit): the CA is KEPT (never re-minted — stable across re-grows), so the seal is a no-op.
   */
  public static class When extends Stage<When> {

    @ProvidedScenarioState(resolution = Resolution.NAME)
    Optional<SealedClusterPki> sealed = Optional.empty();

    @ProvidedScenarioState(resolution = Resolution.NAME)
    WorkloadClusterCas workloadCas = new WorkloadClusterCas(List.of());

    @As("the cluster CA is sealed")
    public When the_cluster_ca_is_sealed(
        @Hidden Parcel parcel,
        @Hidden Cellar cellar,
        @Hidden NdhKeystoreReader keystore,
        @Hidden SshToAgeConverter sshToAge,
        @Hidden SopsEncryptor encryptor,
        @Hidden List<String> workloadClusters) {
      final ClusterSeal seal = new ClusterSeal(keystore, sshToAge, encryptor);
      final Optional<ClusterCaBundle> existing =
          cellar.fetch(parcel, ClusterPkiCoordinate.CLUSTER_CA_BUNDLE, ClusterCaBundle.class);
      if (existing.isEmpty()) {
        this.sealed = Optional.of(seal.seal());
      }
      // The workload BYO-CA sets — minted ADDITIVELY (keep the entries already sealed, mint only
      // the clusters newly appearing in workloadTargets), independent of the mgmt CA idempotency
      // gate above so a new workload cluster gets its CA even on a re-grow of an existing mgmt.
      final Optional<WorkloadClusterCas> existingWorkload =
          cellar.fetch(parcel, ClusterPkiCoordinate.WORKLOAD_CLUSTER_CAS, WorkloadClusterCas.class);
      this.workloadCas = seal.sealWorkloadCas(workloadClusters, existingWorkload);
      return self();
    }
  }

  /**
   * THEN — the PKI is filed: the bundle PLAIN (already sealed), the age identity SEALED, the
   * operator's admin credentials SEALED (they carry the admin private key).
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState(resolution = Resolution.NAME)
    Optional<SealedClusterPki> sealed;

    @ExpectedScenarioState(resolution = Resolution.NAME)
    WorkloadClusterCas workloadCas;

    @As("the cluster PKI is filed")
    public Then the_cluster_pki_is_filed(@Hidden Parcel parcel, @Hidden Cellar cellar) {
      sealed.ifPresent(
          pki -> {
            cellar.store(parcel, ClusterPkiCoordinate.CLUSTER_CA_BUNDLE, pki.bundle());
            cellar.store(
                parcel, ClusterPkiCoordinate.CLUSTER_AGE_KEY, pki.ageKey(), Sensitivity.SEALED);
            // IN_CLUSTER: admin credentials render the operator kubeconfig, a local-config Secret
            // committed to the branch (sops-encrypted). Flux ignores local-config, but the SEAL
            // must
            // reach in-cluster or the steady-state render drops the branch kubeconfig every pass
            // (churn). No new exposure — the material already rides the branch as that Secret. This
            // is ORTHOGONAL to the NODE_BOOTSTRAP delivery of admin to the node; the two coexist.
            cellar.store(
                parcel,
                ClusterPkiCoordinate.ADMIN_CREDENTIALS,
                pki.adminCredentials(),
                Sensitivity.SEALED,
                Reach.IN_CLUSTER);
            cellar.store(
                parcel,
                ClusterPkiCoordinate.CLUSTER_ISSUER_CA,
                pki.clusterIssuerCa(),
                Sensitivity.SEALED);
          });
      // The workload BYO-CA sets — SEALED (they carry the CA private keys). Filed whenever any
      // workload cluster was requested (the additive mint returns the current set); a mgmt-only run
      // returns an empty set and files nothing.
      if (!workloadCas.entries().isEmpty()) {
        // IN_CLUSTER: the workload BYO-CA Secrets ride the branch (CAPI secretRef), so the
        // in-cluster
        // render must resolve them — extracted to the branch cellar asset. Of the sibling stores
        // above, admin is ALSO IN_CLUSTER (it renders the branch operator kubeconfig); bundle +
        // age-key + issuer stay OPERATOR_ONLY — they render NO branch Secret (pure NODE_BOOTSTRAP).
        cellar.store(
            parcel,
            ClusterPkiCoordinate.WORKLOAD_CLUSTER_CAS,
            workloadCas,
            Sensitivity.SEALED,
            Reach.IN_CLUSTER);
      }
      return self();
    }
  }
}
