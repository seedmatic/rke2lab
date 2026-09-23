package io.seedmatic.rke2lab.clusterpki.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.clusterpki.contract.OperatorKubeconfig.Access;
import io.seedmatic.rke2lab.clusterpki.contract.OperatorKubeconfig.ClusterAccess;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kubeconfig renderer assembles its {@code clusters}/{@code users}/{@code contexts} lists by
 * appending text blocks, so the risk is no longer "does it hold the right values" but "is the SHAPE
 * right" — and a broken shape surfaces only when the operator's kubectl refuses the file, or worse,
 * accepts it and dials the wrong cluster.
 *
 * <p>Asserted per SECTION rather than by counting substrings across the whole document: {@code -
 * name: <cluster>} occurs in both the cluster list and the context list, so a global count proves
 * nothing about either (the first draft of this test failed on exactly that ambiguity).
 */
class OperatorKubeconfigTest {

  private static final AdminCredentials MGMT =
      new AdminCredentials("MGMT-CERT", "MGMT-KEY", "MGMT-CA");

  private static final AdminCredentials WRKLD =
      new AdminCredentials("WRKLD-CERT", "WRKLD-KEY", "WRKLD-CA");

  @Test
  void one_access_yields_one_cluster_one_context_and_is_current() {
    final String rendered =
        new OperatorKubeconfig(
                List.of(
                    new ClusterAccess(
                        "bioskop-mgmt",
                        MGMT,
                        List.of(new Access("bioskop-mgmt", "https://192.168.1.131:6443")))))
            .render();

    assertEquals(List.of("bioskop-mgmt"), names(section(rendered, "clusters")));
    assertEquals(List.of("bioskop-mgmt"), names(section(rendered, "contexts")));
    assertTrue(section(rendered, "clusters").contains("server: https://192.168.1.131:6443"));
    assertEquals("bioskop-mgmt", currentContext(rendered));
  }

  @Test
  void several_accesses_on_one_cluster_share_one_user_and_the_first_is_current() {
    final String rendered =
        new OperatorKubeconfig(
                List.of(
                    new ClusterAccess(
                        "bioskop-mgmt",
                        MGMT,
                        List.of(
                            new Access("bioskop-mgmt", "https://192.168.1.131:6443"),
                            new Access("bioskop-mgmt-vip", "https://10.80.7.10:6443")))))
            .render();

    assertEquals(List.of("bioskop-mgmt", "bioskop-mgmt-vip"), names(section(rendered, "clusters")));
    assertEquals(List.of("bioskop-mgmt", "bioskop-mgmt-vip"), names(section(rendered, "contexts")));
    // ONE user for both: the credentials are endpoint-independent, which is the whole reason
    // several
    // contexts can share them.
    assertEquals(List.of("bioskop-mgmt-admin"), names(section(rendered, "users")));
    // The LAN context leads — it answers fastest and keeps answering through a cold start, while
    // the
    // VIP rides the tailnet Connector.
    assertEquals("bioskop-mgmt", currentContext(rendered));
  }

  @Test
  void every_context_points_at_its_own_cluster_and_the_shared_user() {
    final String rendered =
        new OperatorKubeconfig(
                List.of(
                    new ClusterAccess(
                        "bioskop-mgmt",
                        MGMT,
                        List.of(
                            new Access("a", "https://a:6443"), new Access("b", "https://b:6443")))))
            .render();
    final String contexts = section(rendered, "contexts");

    // The defect this guards: a context whose `cluster:` drifted to its neighbour's — both contexts
    // would then dial one endpoint, silently.
    assertTrue(contexts.contains("cluster: a"), contexts);
    assertTrue(contexts.contains("cluster: b"), contexts);
    assertEquals(2, contexts.split("user: bioskop-mgmt-admin", -1).length - 1, contexts);
  }

  @Test
  void two_clusters_keep_their_own_credentials_apart() {
    final String rendered =
        new OperatorKubeconfig(
                List.of(
                    new ClusterAccess(
                        "bioskop-mgmt",
                        MGMT,
                        List.of(new Access("bioskop-mgmt", "https://192.168.1.131:6443"))),
                    new ClusterAccess(
                        "bioskop-wrkld",
                        WRKLD,
                        List.of(new Access("bioskop-wrkld-vip", "https://10.80.15.10:6443")))))
            .render();

    // A user PER cluster, never one shared: a workload cluster's CA hierarchy is a SIBLING of the
    // management one, so authenticating to it with the management admin certificate gets a 401 —
    // and it would be a 401 the operator has no way to read as "wrong credential for this cluster".
    assertEquals(
        List.of("bioskop-mgmt-admin", "bioskop-wrkld-admin"), names(section(rendered, "users")));
    assertTrue(section(rendered, "users").contains(base64("MGMT-CERT")));
    assertTrue(section(rendered, "users").contains(base64("WRKLD-CERT")));

    // …and each cluster's trust anchor is its OWN. The defect this guards is the one a single
    // shared
    // `users`/`ca-data` block would produce: a context that verifies the workload apiserver against
    // the management CA and fails at the handshake.
    final String clusters = section(rendered, "clusters");
    assertTrue(clusters.contains(base64("MGMT-CA")), clusters);
    assertTrue(clusters.contains(base64("WRKLD-CA")), clusters);

    // The context names are the caller's, and the management one still leads.
    assertEquals(
        List.of("bioskop-mgmt", "bioskop-wrkld-vip"), names(section(rendered, "contexts")));
    assertEquals("bioskop-mgmt", currentContext(rendered));
  }

  @Test
  void a_cluster_with_no_way_in_is_refused_rather_than_rendered_empty() {
    assertThrows(
        IllegalArgumentException.class, () -> new ClusterAccess("bioskop-wrkld", WRKLD, List.of()));
  }

  @Test
  void a_document_with_no_cluster_is_refused() {
    assertThrows(IllegalArgumentException.class, () -> new OperatorKubeconfig(List.of()));
  }

  private static String base64(final String plain) {
    return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  /** The block under a top-level key, up to the next column-zero key. */
  private static String section(final String kubeconfig, final String key) {
    final int from = kubeconfig.indexOf("\n" + key + ":\n");
    assertTrue(from >= 0, "no section " + key + " in:\n" + kubeconfig);
    final int body = from + key.length() + 3;
    final int to =
        kubeconfig.indexOf("\n", body) < 0 ? kubeconfig.length() : nextKey(kubeconfig, body);
    return kubeconfig.substring(body, to);
  }

  private static int nextKey(final String kubeconfig, final int from) {
    for (int at = from; at < kubeconfig.length(); at = kubeconfig.indexOf('\n', at) + 1) {
      if (at > from && at < kubeconfig.length() && !Character.isWhitespace(kubeconfig.charAt(at))) {
        return at;
      }
      if (kubeconfig.indexOf('\n', at) < 0) {
        break;
      }
    }
    return kubeconfig.length();
  }

  /** The `- name:` entries of a section, in order. */
  private static List<String> names(final String section) {
    return section
        .lines()
        .filter(line -> line.trim().startsWith("- name:"))
        .map(line -> line.substring(line.indexOf("- name:") + 7).trim())
        .toList();
  }

  private static String currentContext(final String kubeconfig) {
    return kubeconfig
        .lines()
        .filter(line -> line.startsWith("current-context:"))
        .map(line -> line.substring("current-context:".length()).trim())
        .findFirst()
        .orElseThrow();
  }
}
