package io.seedmatic.rke2lab.unitrepo.configspike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.unitrepo.core.UnitResolver;
import io.seedmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.Resolver;

/**
 * SPIKE (throwaway) for Step 2 — the config bundle/host contract at the RIGHT grain, after the OSGi
 * completeness review (2026-06-17). The earlier per-key model put config keys into the resolution
 * closure; the standard (verified on the jars) keeps config VALUES out of resolution — Config Admin
 * delivers them at runtime, Metatype describes their schema, DS {@code
 * configuration-policy=require} makes the loud-fail an ACTIVATION concern.
 *
 * <p>What DOES touch resolution is the DELIVERY MECHANISM: a bundle that needs its config delivered
 * declares {@code Require osgi.extender} (e.g. {@code osgi.metatype} or {@code osgi.component});
 * the host that runs that extender {@code Provide}s the capability. {@code osgi.extender} is a real
 * OSGi resolution namespace ({@code org.osgi.namespace.extender.ExtenderNamespace}, value {@code
 * "osgi.extender"}, version attribute {@code "version"}, verified on disk 1.0.1).
 *
 * <p>So the generic bundle/host contract — the cas-zero — is OSGi-native, not invented: a bundle
 * Requires an extender, the host Provides it, the resolver wires them; a missing extender is a loud
 * ResolutionException, not a silent empty closure (the coherence rule: report, never auto-resolve).
 */
@Tag("spike")
class ConfigExtenderResolutionSpikeTest {

  /** The resolver the spike injects — a direct ResolverImpl; production binds the OSGi service. */
  private static Resolver resolver() {
    return new ResolverImpl(new Logger(Logger.LOG_ERROR));
  }

  // org.osgi.namespace.extender.ExtenderNamespace (verified on the 1.0.1 jar)
  static final String NS_EXTENDER = "osgi.extender";
  static final String ATTR_EXTENDER = "osgi.extender";
  static final String ATTR_VERSION = "version";

  // canonical extender names
  static final String METATYPE = "osgi.metatype";
  static final String COMPONENT = "osgi.component";

  /**
   * A host unit that runs the config-delivery extenders (DS + Metatype) provides their capability.
   */
  private static UnitResource configHost() {
    return new UnitResource("config-host")
        .provide(NS_EXTENDER, Map.of(ATTR_EXTENDER, METATYPE, ATTR_VERSION, "1.4"))
        .provide(NS_EXTENDER, Map.of(ATTR_EXTENDER, COMPONENT, ATTR_VERSION, "1.5"));
  }

  /**
   * A config-bearing domain bundle declares it needs the metatype + component extenders to live.
   */
  private static UnitResource domainBundle(String id) {
    return new UnitResource(id)
        .require(NS_EXTENDER, "(" + ATTR_EXTENDER + "=" + METATYPE + ")")
        .require(NS_EXTENDER, "(" + ATTR_EXTENDER + "=" + COMPONENT + ")");
  }

  @Test
  void contractHolds_bundleWiresToTheExtenderProvider() throws ResolutionException {
    UnitResource host = configHost();
    UnitResource incus = domainBundle("incus");

    UnitResolver resolver = new UnitResolver(List.of(host, incus), resolver());
    Map<Resource, List<Wire>> wiring = resolver.resolve(incus);
    Set<Resource> closure = wiring.keySet();

    assertTrue(closure.contains(incus), "closure contains the config bundle");
    assertTrue(closure.contains(host), "config bundle wires to the extender-providing host");

    long extenderWires =
        wiring.get(incus).stream()
            .filter(w -> w.getCapability().getNamespace().equals(NS_EXTENDER))
            .count();
    assertEquals(2, extenderWires, "both required extenders (metatype + component) must wire");
  }

  @Test
  void missingExtender_failsLoudly_notSilently() {
    // the host runs Metatype but NOT the component extender
    UnitResource partialHost =
        new UnitResource("config-host")
            .provide(NS_EXTENDER, Map.of(ATTR_EXTENDER, METATYPE, ATTR_VERSION, "1.4"));
    UnitResource incus = domainBundle("incus");

    UnitResolver resolver = new UnitResolver(List.of(partialHost, incus), resolver());

    assertThrows(
        ResolutionException.class,
        () -> resolver.resolve(incus),
        "a missing extender is a diagnosable failure, not a silent empty closure");
  }
}
