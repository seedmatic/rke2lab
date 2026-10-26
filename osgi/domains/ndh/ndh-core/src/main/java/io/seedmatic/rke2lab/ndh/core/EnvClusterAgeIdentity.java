package io.seedmatic.rke2lab.ndh.core;

import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.util.List;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * The cluster's age identity, read from the variable {@value #ENV} the operator's shell sets on
 * {@code pass show seedmatic/cluster-age/<n>} when it runs the grow or a {@code pulumi preview}. It
 * is never written, logged, or quoted in an error.
 *
 * <p>A variable rather than a descriptor: a pipe on fd 3 is inherited by the whole tree Pulumi
 * forks, and the first reader drains it for all (T4's preview, 2026-10-26). In-cluster the variable
 * is never read: the cluster holds the identity in its {@code sops-age} Secret already.
 */
@Component(service = ClusterAgeIdentity.class)
public final class EnvClusterAgeIdentity implements ClusterAgeIdentity {

  static final String ENV = "RKE2LAB_CLUSTER_AGE";
  private static final String PREFIX = "AGE-SECRET-KEY-1";

  private final Optional<String> value;

  @Activate
  public EnvClusterAgeIdentity(
      @Reference(
              cardinality = ReferenceCardinality.OPTIONAL,
              policyOption = ReferencePolicyOption.GREEDY)
          EnclosureGate enclosure) {
    this(System.getenv(ENV), Optional.ofNullable(enclosure));
  }

  EnvClusterAgeIdentity(final String value, final Optional<EnclosureGate> enclosure) {
    this.value =
        enclosure.map(EnclosureGate::inCluster).orElse(false)
            ? Optional.empty()
            : Optional.ofNullable(value).filter(v -> !v.isBlank());
  }

  @Override
  public boolean present() {
    return value.isPresent();
  }

  @Override
  public String identity() {
    return parse(
        value.orElseThrow(
            () ->
                new IllegalStateException(
                    ENV
                        + " is not set: hand the cluster's age identity over, e.g. "
                        + ENV
                        + "=\"$(pass show seedmatic/cluster-age/<n>)\" …")));
  }

  /** The one identity line of age-keygen's output; the content never reaches a message. */
  static String parse(final String content) {
    final List<String> identities =
        content.lines().map(String::strip).filter(line -> line.startsWith(PREFIX)).toList();
    if (identities.size() != 1) {
      throw new IllegalStateException(
          ENV + " holds " + identities.size() + " age identities, not exactly one");
    }
    return identities.get(0);
  }
}
