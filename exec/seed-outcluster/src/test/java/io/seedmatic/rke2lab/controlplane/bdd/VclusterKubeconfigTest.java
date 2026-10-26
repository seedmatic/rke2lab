package io.seedmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiCoordinate;
import io.seedmatic.rke2lab.clusterpki.contract.VclusterPki;
import io.seedmatic.rke2lab.netplan.ingress.NetplanIngressCoordinate;
import io.seedmatic.rke2lab.seed.broker.internal.CodecCellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ccr's context sits at the URL its host's projection gives, and is absent without one. */
class VclusterKubeconfigTest {

  record Projection(Map<String, Object> addressing) {}

  private static int contexts(Map<String, Object> ips) {
    final Parcel parcel = new Parcel("rke2lab", "bioskop-mgmt");
    final CodecCellar cellar = new CodecCellar(new NetplanProjectionTest.KeptCellar(), null);
    final VclusterPki.Pair p = new VclusterPki.Pair("C", "K");
    cellar.store(
        parcel,
        NetplanIngressCoordinate.PROJECTION,
        new Projection(Map.of("nikopol-wrkld", Map.of("master", Map.of("ips", ips)))));
    cellar.store(
        parcel,
        ClusterPkiCoordinate.VCLUSTER_PKI,
        new VclusterPki(
            List.of(
                new VclusterPki.Entry(
                    "ccr", p, p, p, p, p, "K", "P", new AdminCredentials("C", "K", "A")))));
    final ClusterSeedScenario.When when = new ClusterSeedScenario.When();
    when.workingCellar = cellar;
    when.parcel = parcel;
    return when.vclusterAccesses().size();
  }

  @Test
  void ccr_has_a_context_only_once_its_url_is_projected() {
    assertEquals(1, contexts(Map.of("vclusterApiServer", "https://172.16.18.67:443")));
    assertEquals(0, contexts(Map.of("vipHost", "10.80.47.10")));
  }
}
