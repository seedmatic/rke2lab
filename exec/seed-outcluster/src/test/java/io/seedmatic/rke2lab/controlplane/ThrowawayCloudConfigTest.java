package io.seedmatic.rke2lab.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.controlplane.incus.NodeBootstrapMaterial;
import io.seedmatic.rke2lab.controlplane.incus.NodeCloudConfig;
import io.seedmatic.rke2lab.controlplane.incus.NodeCloudConfig.WriteFile;
import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A throwaway node's cloud-config holds node.env and its one rke2 drop-in, masks sshd, and
 * authorizes no key: the one the grow builds passes, and each way out is refused on its own.
 */
class ThrowawayCloudConfigTest {

  private static final GrowIdentityView IDENTITY =
      new GrowIdentityView("master", "nikopol-mgmt-master", "control-plane", 1);
  private static final GrowNetworkView.NodeAddressing NODE =
      new GrowNetworkView.NodeAddressing(
          "10.45.0.0/16,fd00:45::/56",
          "10.46.0.0/16,fd00:46::/112",
          "172.16.17.3",
          "nikopol-mgmt-master.nikopol");

  private static NodeCloudConfig built() {
    return NodeCloudConfig.throwaway(IDENTITY, NODE);
  }

  private static NodeCloudConfig withWrite(String path) {
    final List<WriteFile> files = new ArrayList<>(built().writeFiles());
    files.add(new WriteFile(path, "0400", "x"));
    return new NodeCloudConfig(files, built().bootcmd(), List.of());
  }

  private static String content(NodeCloudConfig config, String path) {
    return config.writeFiles().stream()
        .filter(file -> file.path().equals(path))
        .map(WriteFile::content)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void the_throwaway_cloud_config_the_grow_builds_is_allowed() {
    final NodeCloudConfig config = built();
    assertEquals(config, ThrowawayCloudConfig.checked(config));
    assertEquals(
        List.of(NodeCloudConfig.NODE_ENV, NodeCloudConfig.THROWAWAY_RKE2_CONFIG),
        config.writeFiles().stream().map(WriteFile::path).toList());
  }

  @Test
  void its_rke2_drop_in_matches_the_dual_stack_node_ip_and_names_the_fabric() {
    final String dropIn = content(built(), NodeCloudConfig.THROWAWAY_RKE2_CONFIG);
    assertTrue(dropIn.contains("cluster-cidr: \"10.45.0.0/16,fd00:45::/56\""), dropIn);
    assertTrue(dropIn.contains("service-cidr: \"10.46.0.0/16,fd00:46::/112\""), dropIn);
    assertTrue(dropIn.contains("  - \"nikopol-mgmt-master.nikopol\""), dropIn);
    assertTrue(dropIn.contains("  - \"172.16.17.3\""), dropIn);
    assertTrue(dropIn.contains("write-kubeconfig-mode: \"0600\""), dropIn);
    assertFalse(dropIn.contains("cni"), "the image sets cilium; rke2 concatenates cni lists");
  }

  @Test
  void the_rendered_document_carries_the_mask_and_no_key() {
    final String rendered = built().render();
    assertTrue(
        rendered.contains("bootcmd:\n- \"" + NodeCloudConfig.SSHD_RUNTIME_MASK + "\"\n"), rendered);
    assertFalse(rendered.contains("ssh_authorized_keys"), rendered);
    assertFalse(rendered.contains("users:"), rendered);
    final String b64 =
        Base64.getEncoder()
            .encodeToString(
                content(built(), NodeCloudConfig.NODE_ENV).getBytes(StandardCharsets.UTF_8));
    assertTrue(rendered.contains("- path: /var/lib/rke2lab/node.env\n"), rendered);
    assertTrue(rendered.contains("  content: " + b64 + "\n"), rendered);
  }

  @Test
  void a_cloud_config_without_the_sshd_mask_is_refused() {
    final NodeCloudConfig unmasked =
        new NodeCloudConfig(built().writeFiles(), List.of(), List.of());
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> ThrowawayCloudConfig.checked(unmasked));
    assertTrue(refused.getMessage().contains("does not mask sshd"), refused.getMessage());
  }

  @Test
  void a_mask_that_leaves_one_sshd_unit_out_is_refused() {
    final NodeCloudConfig partial =
        new NodeCloudConfig(
            built().writeFiles(),
            List.of("systemctl mask --runtime --now sshd.socket sshd@.service sshd-keygen.service"),
            List.of());
    assertThrows(IllegalStateException.class, () -> ThrowawayCloudConfig.checked(partial));
  }

  @Test
  void a_cloud_config_that_authorizes_an_ssh_key_is_refused() {
    final NodeCloudConfig keyed =
        new NodeCloudConfig(
            built().writeFiles(), built().bootcmd(), List.of("ssh-ed25519 AAAA operator"));
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> ThrowawayCloudConfig.checked(keyed));
    assertTrue(refused.getMessage().contains("authorizes an ssh key"), refused.getMessage());
  }

  private static void assertWriteRefused(String path) {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> ThrowawayCloudConfig.checked(withWrite(path)));
    assertTrue(refused.getMessage().contains(path), refused.getMessage());
  }

  @Test
  void writing_the_sops_age_key_is_refused() {
    assertWriteRefused(NodeCloudConfig.SOPS_AGE_KEY);
  }

  @Test
  void writing_the_cluster_ca_bundle_is_refused() {
    assertWriteRefused(NodeCloudConfig.CLUSTER_CA_BUNDLE);
  }

  @Test
  void writing_the_nix_github_token_is_refused() {
    assertWriteRefused(NodeCloudConfig.NIX_GITHUB_CONF);
  }

  @Test
  void writing_the_rke2_config_ref_is_refused() {
    assertWriteRefused(NodeCloudConfig.RKE2_CONFIG_ENV);
  }

  @Test
  void writing_the_nix_builders_list_is_refused() {
    assertWriteRefused("/var/lib/rke2lab/nix/machines");
  }

  @Test
  void writing_the_bootstrap_manifests_is_refused() {
    assertWriteRefused(NodeCloudConfig.SERVER_MANIFESTS);
  }

  @Test
  void writing_any_other_path_is_refused() {
    assertWriteRefused("/etc/nix/nix.conf");
  }

  @Test
  void an_allowed_path_written_twice_is_refused() {
    final List<WriteFile> files = new ArrayList<>(built().writeFiles());
    files.add(new WriteFile(NodeCloudConfig.NODE_ENV, "0644", "RKE2LAB_NODE_HOSTNAME=other\n"));
    final NodeCloudConfig twice = new NodeCloudConfig(files, built().bootcmd(), List.of());
    assertThrows(IllegalStateException.class, () -> ThrowawayCloudConfig.checked(twice));
  }

  /** The document the grow posed before the cloud-config was a model, laid out by hand. */
  private static String formerFleetDocument(String... pathPermsContent) {
    final StringBuilder doc = new StringBuilder("#cloud-config\nwrite_files:\n");
    for (int i = 0; i < pathPermsContent.length; i += 3) {
      doc.append("- path: ")
          .append(pathPermsContent[i])
          .append("\n  encoding: b64\n  permissions: '")
          .append(pathPermsContent[i + 1])
          .append("'\n  content: ")
          .append(
              Base64.getEncoder()
                  .encodeToString(pathPermsContent[i + 2].getBytes(StandardCharsets.UTF_8)))
          .append("\n");
    }
    return doc.toString();
  }

  @Test
  void the_fleet_document_is_byte_for_byte_the_one_the_grow_posed_before() {
    final String nodeEnv =
        "RKE2LAB_NODE_NAME=master\nRKE2LAB_NODE_HOSTNAME=nikopol-mgmt-master\n"
            + "RKE2LAB_NODE_KIND=control-plane\nRKE2LAB_NODE_ID=1\n";
    final NodeCloudConfig fleet =
        NodeCloudConfig.fleet(
            IDENTITY,
            new NodeBootstrapMaterial(
                Optional.of("age"),
                Optional.of("ca"),
                Optional.of("manifests"),
                Optional.of("manifests/nikopol-mgmt"),
                Optional.of("token")));
    assertEquals(
        formerFleetDocument(
            "/var/lib/rke2lab/node.env",
            "0644",
            nodeEnv,
            "/var/lib/rke2lab/sops-age.key",
            "0400",
            "age",
            "/var/lib/rke2lab/cluster-ca-bundle.yaml",
            "0400",
            "ca",
            "/var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml",
            "0600",
            "manifests",
            "/run/rke2lab/rke2-config.env",
            "0644",
            "RKE2LAB_MANIFESTS_REF=manifests/nikopol-mgmt\n",
            "/run/rke2lab/nix-github.conf",
            "0400",
            "access-tokens = github.com=token\n"),
        fleet.render());
  }

  @Test
  void the_fleet_cloud_config_is_not_a_throwaway_one() {
    final NodeCloudConfig fleet =
        NodeCloudConfig.fleet(
            IDENTITY,
            new NodeBootstrapMaterial(
                Optional.of("AGE-SECRET-KEY-x"),
                Optional.empty(),
                Optional.empty(),
                Optional.of("manifests/nikopol-mgmt"),
                Optional.empty()));
    assertThrows(IllegalStateException.class, () -> ThrowawayCloudConfig.checked(fleet));
  }
}
