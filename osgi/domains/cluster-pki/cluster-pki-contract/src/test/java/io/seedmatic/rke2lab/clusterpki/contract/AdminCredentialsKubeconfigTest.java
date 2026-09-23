package io.seedmatic.rke2lab.clusterpki.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kubeconfig renderer assembles its {@code clusters}/{@code contexts} lists by appending text
 * blocks, so the risk is no longer "does it hold the right values" but "is the SHAPE right" — and a
 * broken shape surfaces only when the operator's kubectl refuses the file.
 *
 * <p>Asserted per SECTION rather than by counting substrings across the whole document: {@code -
 * name: <cluster>} occurs in both the cluster list and the context list, so a global count proves
 * nothing about either (the first draft of this test failed on exactly that ambiguity).
 */
class AdminCredentialsKubeconfigTest {

  private static final AdminCredentials CREDENTIALS =
      new AdminCredentials("CERT-PEM", "KEY-PEM", "CA-PEM");

  @Test
  void one_access_yields_one_cluster_one_context_and_is_current() {
    final String rendered =
        CREDENTIALS.kubeconfig(
            "bioskop-mgmt",
            List.of(new AdminCredentials.Access("bioskop-mgmt", "https://192.168.1.131:6443")));

    assertEquals(List.of("bioskop-mgmt"), names(section(rendered, "clusters")));
    assertEquals(List.of("bioskop-mgmt"), names(section(rendered, "contexts")));
    assertTrue(section(rendered, "clusters").contains("server: https://192.168.1.131:6443"));
    assertEquals("bioskop-mgmt", currentContext(rendered));
  }

  @Test
  void several_accesses_share_one_user_and_the_first_is_current() {
    final String rendered =
        CREDENTIALS.kubeconfig(
            "bioskop-mgmt",
            List.of(
                new AdminCredentials.Access("bioskop-mgmt", "https://192.168.1.131:6443"),
                new AdminCredentials.Access("bioskop-mgmt-vip", "https://10.80.7.10:6443")));

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
        CREDENTIALS.kubeconfig(
            "bioskop-mgmt",
            List.of(
                new AdminCredentials.Access("a", "https://a:6443"),
                new AdminCredentials.Access("b", "https://b:6443")));
    final String contexts = section(rendered, "contexts");

    // The defect this guards: a context whose `cluster:` drifted to its neighbour's — both contexts
    // would then dial one endpoint, silently.
    assertTrue(contexts.contains("cluster: a"), contexts);
    assertTrue(contexts.contains("cluster: b"), contexts);
    assertEquals(2, contexts.split("user: bioskop-mgmt-admin", -1).length - 1, contexts);
  }

  @Test
  void no_access_is_refused_rather_than_rendered_empty() {
    assertThrows(IllegalArgumentException.class, () -> CREDENTIALS.kubeconfig("c", List.of()));
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
