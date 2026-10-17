package io.seedmatic.rke2lab.sops.edge;

import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import io.seedmatic.rke2lab.sops.process.SopsProcess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code sops} decrypt edge, twin of {@link ProcessBuilderSopsEncryptor}: opens a
 * sops-encrypted YAML by piping it to {@code sops --decrypt --config /dev/null --input-type yaml
 * --output-type yaml /dev/stdin}. An explicit age identity goes out-of-band through the {@code
 * SOPS_AGE_KEY} environment variable (never on the command line — it would leak to the process
 * table); without one, sops looks the key up itself. {@code --config /dev/null} mirrors the encrypt
 * edge (the repo's {@code .sops.yaml} plays no part in a decrypt).
 */
@Component(service = SopsDecryptor.class)
public final class ProcessBuilderSopsDecryptor implements SopsDecryptor {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderSopsDecryptor.class);

  private static final List<String> DECRYPT =
      List.of(
          "--decrypt",
          "--config",
          "/dev/null",
          "--input-type",
          "yaml",
          "--output-type",
          "yaml",
          "/dev/stdin");

  private final SopsProcess ambient;
  private final Optional<EnclosureGate> enclosure;

  /** Outside a framework (a test, a standalone tool): the OPERATOR enclosure. */
  public ProcessBuilderSopsDecryptor() {
    this((EnclosureGate) null);
  }

  /**
   * The enclosure decides whether the sops stderr opt-in may hold (never IN_CLUSTER); absent, it is
   * the OPERATOR one.
   */
  @Activate
  public ProcessBuilderSopsDecryptor(
      @Reference(
              cardinality = ReferenceCardinality.OPTIONAL,
              policyOption = ReferencePolicyOption.GREEDY)
          EnclosureGate enclosure) {
    this(Optional.ofNullable(enclosure));
  }

  private ProcessBuilderSopsDecryptor(Optional<EnclosureGate> enclosure) {
    this(new SopsProcess(Map.of(), enclosure), enclosure);
  }

  ProcessBuilderSopsDecryptor(SopsProcess ambient, Optional<EnclosureGate> enclosure) {
    this.ambient = ambient;
    this.enclosure = enclosure;
  }

  @Override
  public String decryptYaml(String sopsYaml, String ageIdentity) {
    if (ageIdentity == null || ageIdentity.isBlank()) {
      throw new SopsEncryptionException("no age identity — cannot open the sealed bundle");
    }
    final String plaintext =
        new SopsProcess(Map.of("SOPS_AGE_KEY", ageIdentity), enclosure).run(DECRYPT, sopsYaml);
    LOG.debug("sops opened a {}-byte sealed bundle", plaintext.length());
    return plaintext;
  }

  @Override
  public String decryptYaml(String sopsYaml) {
    final String plaintext = ambient.run(DECRYPT, sopsYaml);
    LOG.debug("sops opened a {}-byte sealed bundle with its own key lookup", plaintext.length());
    return plaintext;
  }
}
