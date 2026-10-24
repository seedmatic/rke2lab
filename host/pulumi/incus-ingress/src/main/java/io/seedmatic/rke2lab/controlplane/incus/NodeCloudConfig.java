package io.seedmatic.rke2lab.controlplane.incus;

import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The mgmt node's {@code cloud-init.user-data}, as what it does to the node: the files it writes,
 * the {@code bootcmd} lines it runs, the ssh keys it authorizes. The grow renders it; the caller
 * builds it, so a profile that must hold only some of it can check the model before anything is
 * rendered.
 *
 * <p>Every file's content is base64 ({@code encoding: b64}) so arbitrary YAML/PEM never trips
 * cloud-init's YAML indentation.
 */
public record NodeCloudConfig(
    List<WriteFile> writeFiles, List<String> bootcmd, List<String> sshAuthorizedKeys) {

  public static final String NODE_ENV = "/var/lib/rke2lab/node.env";
  public static final String SOPS_AGE_KEY = "/var/lib/rke2lab/sops-age.key";
  public static final String CLUSTER_CA_BUNDLE = "/var/lib/rke2lab/cluster-ca-bundle.yaml";
  public static final String SERVER_MANIFESTS =
      "/var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml";
  public static final String RKE2_CONFIG_ENV = "/run/rke2lab/rke2-config.env";
  public static final String NIX_GITHUB_CONF = "/run/rke2lab/nix-github.conf";
  public static final String THROWAWAY_RKE2_CONFIG =
      "/etc/rancher/rke2/config.yaml.d/50-throwaway.yaml";

  /**
   * Masks sshd for this boot only: NixOS's {@code /etc/systemd/system} is read-only, and {@code
   * /run} is where a runtime mask lives. sshd is socket-activated, so its sockets go too.
   */
  public static final String SSHD_RUNTIME_MASK =
      "systemctl mask --runtime --now sshd.socket sshd-vsock.socket sshd@.service"
          + " sshd-keygen.service";

  public NodeCloudConfig {
    writeFiles = List.copyOf(writeFiles);
    bootcmd = List.copyOf(bootcmd);
    sshAuthorizedKeys = List.copyOf(sshAuthorizedKeys);
  }

  /** One {@code write_files} entry. */
  public record WriteFile(String path, String permissions, String content) {}

  /**
   * The fleet node's: {@code node.env} carries the four per-node identity scalars (the hostname +
   * per-node rke2 drop-in oneshots — node-labels, provider-id — read it); the revealed {@link
   * NodeBootstrapMaterial} rides as the sops age key, the cluster-CA bundle, and the rke2
   * server-manifests — each only when present (a producer that did not file leaves it out; the
   * guest units are tolerant). The per-cluster rke2 config (CIDRs, tls-san, node-ip, …) is NOT
   * here: it comes from the rendered {@code manifests/<cluster>} branch via {@code nix run
   * <branch>#install-rke2-config} at boot. This is the standalone twin of a CAPRKE2 workload node's
   * cloud-config — one channel.
   */
  public static NodeCloudConfig fleet(GrowIdentityView identity, NodeBootstrapMaterial material) {
    final List<WriteFile> files = new ArrayList<>();
    // DURABLE set → /var/lib/rke2lab (persists across reboots): node.env (identity/hostname read
    // EVERY boot), the sops age key + cluster-CA bundle (sops-nix reads them every boot). /run is
    // tmpfs and cloud-init does not re-run write_files on reboot, so these MUST NOT live there or a
    // reboot loses the hostname + breaks sops.
    files.add(new WriteFile(NODE_ENV, "0644", nodeEnv(identity)));
    material.sopsAgeKey().ifPresent(v -> files.add(new WriteFile(SOPS_AGE_KEY, "0400", v)));
    material
        .clusterCaBundle()
        .ifPresent(v -> files.add(new WriteFile(CLUSTER_CA_BUNDLE, "0400", v)));
    material
        .serverManifests()
        .ifPresent(v -> files.add(new WriteFile(SERVER_MANIFESTS, "0600", v)));
    // The two inputs the rke2lab-rke2-config oneshot reads (nixos/rke2.nix): the manifests branch
    // to fetch (non-secret; also GATES the oneshot via ConditionPathExists), and a nix.conf snippet
    // carrying the fresh github token as an `access-tokens` line (root-only). nix reads the latter
    // via NIX_CONFIG="!include", so the token VALUE stays in the file, never in an env.
    material
        .manifestsBranchRef()
        .ifPresent(
            ref ->
                files.add(
                    new WriteFile(RKE2_CONFIG_ENV, "0644", "RKE2LAB_MANIFESTS_REF=" + ref + "\n")));
    material
        .githubAccessToken()
        .ifPresent(
            token ->
                files.add(
                    new WriteFile(
                        NIX_GITHUB_CONF, "0400", "access-tokens = github.com=" + token + "\n")));
    return new NodeCloudConfig(files, List.of(), List.of());
  }

  /**
   * A throwaway node's: {@code node.env} and its one rke2 drop-in, sshd masked, no key. The drop-in
   * stands for the fleet's rendered config, which this node never fetches: the dual-stack CIDRs, so
   * they match the dual-stack node-ip the node always writes, and the fabric address and name in
   * the serving cert. No {@code cni}: rke2 concatenates list flags across its config files and the
   * image already sets cilium.
   */
  public static NodeCloudConfig throwaway(
      GrowIdentityView identity, GrowNetworkView.NodeAddressing node) {
    final String rke2Config =
        String.join(
                "\n",
                "service-cidr: \"" + node.serviceCidr() + "\"",
                "cluster-cidr: \"" + node.podCidr() + "\"",
                "kube-controller-manager-arg:",
                "  - \"node-cidr-mask-size-ipv4=24\"",
                "bind-address: \"0.0.0.0\"",
                "write-kubeconfig-mode: \"0600\"",
                "tls-san:",
                "  - \"" + node.fabricFqdn() + "\"",
                "  - \"" + node.fabricAddress() + "\"")
            + "\n";
    return new NodeCloudConfig(
        List.of(
            new WriteFile(NODE_ENV, "0644", nodeEnv(identity)),
            new WriteFile(THROWAWAY_RKE2_CONFIG, "0600", rke2Config)),
        List.of(SSHD_RUNTIME_MASK),
        List.of());
  }

  private static String nodeEnv(GrowIdentityView identity) {
    return String.join(
            "\n",
            // RKE2LAB_NODE_NAME keeps its node.env name (a nixos contract; the broader
            // nodeName→nodeRef terminology pass renames the env + its nixos consumers) — sourced
            // from nodeRef (the short ordinal). The FULL name is RKE2LAB_NODE_HOSTNAME below.
            "RKE2LAB_NODE_NAME=" + identity.nodeRef(),
            "RKE2LAB_NODE_HOSTNAME=" + identity.nodeHostname(),
            "RKE2LAB_NODE_KIND=" + identity.nodeKind(),
            "RKE2LAB_NODE_ID=" + identity.nodeId())
        + "\n";
  }

  /** The {@code #cloud-config} document. */
  public String render() {
    final StringBuilder cloudConfig = new StringBuilder("#cloud-config\n");
    if (!bootcmd.isEmpty()) {
      cloudConfig.append("bootcmd:\n");
      bootcmd.forEach(line -> cloudConfig.append("- ").append(quoted(line)).append("\n"));
    }
    if (!sshAuthorizedKeys.isEmpty()) {
      cloudConfig.append("ssh_authorized_keys:\n");
      sshAuthorizedKeys.forEach(key -> cloudConfig.append("- ").append(quoted(key)).append("\n"));
    }
    cloudConfig.append("write_files:\n");
    for (final WriteFile file : writeFiles) {
      cloudConfig
          .append("- path: ")
          .append(file.path())
          .append("\n  encoding: b64\n  permissions: '")
          .append(file.permissions())
          .append("'\n  content: ")
          .append(
              Base64.getEncoder().encodeToString(file.content().getBytes(StandardCharsets.UTF_8)))
          .append("\n");
    }
    return cloudConfig.toString();
  }

  private static String quoted(String line) {
    return "\"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
