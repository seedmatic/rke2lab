package io.seedmatic.rke2lab.ndh.core;

import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * The cluster's age identity, read from the file descriptor {@value #FD_ENV} names — the one the
 * operator's shell opens on {@code pass show seedmatic/cluster-age/<n>} when it runs the grow. The
 * descriptor is a pipe, so it is read ONCE and kept in memory for the run; it is never written,
 * logged, or quoted in an error.
 *
 * <p>The number is at least 3: stdin does not reach a Pulumi program, an inherited descriptor does
 * (measured 2026-10-18). In-cluster the descriptor is never read: the cluster holds the identity in
 * its {@code sops-age} Secret already.
 */
@Component(service = ClusterAgeIdentity.class)
public final class FdClusterAgeIdentity implements ClusterAgeIdentity {

  static final String FD_ENV = "RKE2LAB_CLUSTER_AGE_FD";
  private static final String PREFIX = "AGE-SECRET-KEY-1";

  private final Optional<String> fd;
  private String identity;

  @Activate
  public FdClusterAgeIdentity(
      @Reference(
              cardinality = ReferenceCardinality.OPTIONAL,
              policyOption = ReferencePolicyOption.GREEDY)
          EnclosureGate enclosure) {
    this(System.getenv(FD_ENV), Optional.ofNullable(enclosure));
  }

  FdClusterAgeIdentity(final String fd, final Optional<EnclosureGate> enclosure) {
    this.fd =
        enclosure.map(EnclosureGate::inCluster).orElse(false)
            ? Optional.empty()
            : Optional.ofNullable(fd).filter(value -> !value.isBlank());
  }

  @Override
  public boolean present() {
    return fd.isPresent();
  }

  @Override
  public synchronized String identity() {
    if (identity == null) {
      identity = read(source());
    }
    return identity;
  }

  private Path source() {
    final String number =
        fd.orElseThrow(
            () ->
                new IllegalStateException(
                    FD_ENV
                        + " is not set: open the cluster's age identity on a descriptor, e.g. "
                        + FD_ENV
                        + "=3 … 3< <(pass show seedmatic/cluster-age/<n>)"));
    if (!number.matches("[3-9]|[1-9][0-9]+")) {
      throw new IllegalStateException(
          FD_ENV + " must name a descriptor of 3 or more, not '" + number + "'");
    }
    return Path.of("/dev/fd", number);
  }

  /** The one identity line; the content never reaches a message. */
  static String read(final Path source) {
    final List<String> identities;
    try {
      identities =
          Files.readAllLines(source).stream()
              .map(String::strip)
              .filter(line -> line.startsWith(PREFIX))
              .toList();
    } catch (IOException ex) {
      throw new IllegalStateException(
          "cannot read the cluster's age identity from "
              + source
              + " ("
              + ex.getClass().getName()
              + ")");
    }
    if (identities.size() != 1) {
      throw new IllegalStateException(
          source + " holds " + identities.size() + " age identities, not exactly one");
    }
    return identities.get(0);
  }
}
