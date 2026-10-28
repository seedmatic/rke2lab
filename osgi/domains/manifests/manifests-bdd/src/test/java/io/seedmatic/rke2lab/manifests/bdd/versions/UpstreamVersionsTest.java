package io.seedmatic.rke2lab.manifests.bdd.versions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The upstream readers on canned answers, no network: what each source kind calls stable. */
class UpstreamVersionsTest {

  // openebs.github.io/zfs-localpv/index.yaml's real shape (2026-10-28): the list markers sit at
  // the chart key's indent, each entry's keys two deeper, a dependency's version deeper still
  // (which is not the chart's); trimmed to the keys that matter.
  private static final String HELM_INDEX =
      """
      apiVersion: v1
      entries:
        zfs-localpv:
        - apiVersion: v2
          name: zfs-localpv
          version: 2.12.0-develop
        - apiVersion: v2
          name: zfs-localpv
          version: 2.11.3-prerelease
        - apiVersion: v2
          dependencies:
          - name: crds
            version: 9.9.9
          name: zfs-localpv
          version: 2.11.2
        - apiVersion: v2
          name: zfs-localpv
          version: "2.8.0"
        other-chart:
        - apiVersion: v2
          name: other-chart
          version: 9.9.9
      generated: "2026-10-28T00:00:00Z"
      """;

  @Test
  void aHelmIndexYieldsTheChartsStableVersionsOnly() {
    assertEquals(
        List.of("2.11.2", "2.8.0"), new UpstreamVersions().helmIndex(HELM_INDEX, "zfs-localpv"));
  }

  // pkgs.tailscale.com/helmcharts/index.yaml's real shape (2026-10-28): the chart key at four
  // spaces, the list markers at eight, an entry's keys at ten; `appVersion` and a `urls` list
  // beside the chart's own version.
  private static final String DEEPER_HELM_INDEX =
      """
      apiVersion: v2
      entries:
          tailscale-operator:
              - apiVersion: v2
                name: tailscale-operator
                version: 1.104.2
                appVersion: v1.104.4
                urls:
                  - tailscale-operator-1.104.2.tgz
              - apiVersion: v2
                name: tailscale-operator
                version: 1.104.1
                appVersion: v1.104.1
      generated: "2026-10-28T00:00:00Z"
      """;

  @Test
  void aDeeperIndentedHelmIndexYieldsTheSameReading() {
    assertEquals(
        List.of("1.104.2", "1.104.1"),
        new UpstreamVersions().helmIndex(DEEPER_HELM_INDEX, "tailscale-operator"));
  }

  @Test
  void anOciTagsListYieldsItsStableTags() {
    assertEquals(
        List.of("1.5.1", "1.6.0", "1.6.1"),
        new UpstreamVersions()
            .ociTags(
                "{\"name\":\"x\",\"tags\":[\"1.5.1\",\"1.6.0-rc.1\",\"1.6.0\",\"1.6.1\",\"latest\"]}"));
  }

  @Test
  void aPreReleaseSuffixIsNeverStable() {
    final UpstreamVersions upstream = new UpstreamVersions();
    for (final String tag :
        List.of("2.12.0-develop", "1.0.0-rc.1", "0.38.0-alpha.7", "1.0.0-beta")) {
      assertEquals(false, upstream.isStable(tag), tag);
    }
    for (final String tag : List.of("2.11.2", "v1.36.4", "0.37.4")) {
      assertEquals(true, upstream.isStable(tag), tag);
    }
  }
}
