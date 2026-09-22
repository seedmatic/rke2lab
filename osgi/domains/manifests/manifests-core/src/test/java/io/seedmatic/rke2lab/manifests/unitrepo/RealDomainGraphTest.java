package io.seedmatic.rke2lab.manifests.unitrepo;

import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistry;
import io.seedmatic.rke2lab.manifests.domain.CicdDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.ClusterDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.GitopsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.MeshDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.PlatformDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.StorageDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.TailscaleDomainRegistrar;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link CrossDomainRule} over the REAL registrars, not a fixture.
 *
 * <p>Why this exists: the rule was already covered by {@link CrossDomainRuleTest}, but only against
 * stub domains — so nothing checked the graph the product actually ships. A unit edge added across
 * a domain boundary whose DOMAIN does not declare the dependency compiles perfectly and fails at
 * SYNTHESIS, which is to say during a grow, minutes in and after provisioning has begun. That is
 * exactly what happened on 2026-09-22 with {@code tailscale/funnel-cert-restore ->
 * runtime/seed-incluster}: green build, failed grow.
 *
 * <p>Seconds here instead of minutes there. The registrars are OSGi {@code @Component}s wired by DS
 * at runtime, so there is no discovery to borrow — the list below is explicit, and a NEW registrar
 * must be added to it. That is the one weakness of this test; it still guards every domain listed.
 */
@Tag("osgi")
class RealDomainGraphTest {

  private static final List<ManifestsDomainRegistrar> REGISTRARS =
      List.of(
          new CicdDomainRegistrar(),
          new ClusterApiDomainRegistrar(),
          new ClusterDomainRegistrar(),
          new GitopsDomainRegistrar(),
          new HighAvailabilityDomainRegistrar(),
          new MeshDomainRegistrar(),
          new NetworkingDomainRegistrar(),
          new PlatformDomainRegistrar(),
          new RuntimeDomainRegistrar(),
          new StorageDomainRegistrar(),
          new TailscaleDomainRegistrar());

  @Test
  void everyUnitEdgeStaysInsideADeclaredDomainEdge() {
    // check() throws IllegalStateException naming the offending pair; letting it propagate makes
    // the
    // failure message the diagnosis.
    CrossDomainRule.check(
        new ManifestsDomainRegistry(
            REGISTRARS.stream().map(ManifestsDomainRegistrar::domain).toList()));
  }
}
