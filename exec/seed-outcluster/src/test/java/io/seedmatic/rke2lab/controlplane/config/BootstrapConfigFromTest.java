package io.seedmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.seed.broker.port.ReadinessOverrides;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BootstrapConfigFromTest {

  @Test
  void mandatory_values_pass_through() {
    final BootstrapConfig boot = OperatorConfiguration.mandatory().asBootstrapConfig();
    assertEquals(Path.of("/Users/nxmatic/.config/incus"), boot.incusConfigFolder());
  }

  @Test
  void omitted_optionals_get_defaults() {
    final BootstrapConfig boot = OperatorConfiguration.mandatory().asBootstrapConfig();
    assertEquals("bioskop", boot.host());
    assertEquals("mgmt", boot.role());
    assertEquals("bioskop-mgmt", boot.clusterName());
    assertEquals("master", boot.nodeName());
    assertEquals("rke2lab", boot.incusProject());
    // The remote LABEL stays the bare name — a pure label, never resolved. Every RESOLVABLE form is
    // `nixos.<host>`, the one name we declare: measured with getaddrinfo, `<host>-nixos` is
    // captured
    // by the home LAN through the `lan` search domain and `.local` stalls ~5s on macOS, so the two
    // forms this used to assert both named something that is not ours.
    assertEquals("bioskop-nixos", boot.incusDefaultRemote());
    assertEquals(URI.create("https://nixos.bioskop:8443"), boot.incusRemoteAddress());
    assertEquals("node-base", boot.imageAlias());
    assertEquals("nixos.bioskop", boot.imageBuilderHost());
    // ★ And it now agrees with the in-cluster form by construction rather than by coincidence: one
    // daemon, one name, every audience.
    assertEquals(boot.incusRemoteAddress(), boot.incusClusterAddress());
    assertEquals("fabric-br", boot.fabricBridgeParent());
    assertEquals("mammoth-skate.ts.net", boot.tailnet());
    // The automount root routes over the tailscale MagicDNS FQDN, not the LAN mDNS .local.
    assertEquals("/net/bioskop.local", boot.netPrefix());
    assertEquals(URI.create("https://10.66.106.10:6443"), boot.apiEndpoint());
    assertEquals(true, boot.automount());
    // The master container is reached over mDNS (avahi/.local), not the tailnet — so the default
    // dbus host carries the .local FQDN; a bare <cluster>-<node> would not resolve from the host.
    assertEquals("bioskop-mgmt-master.local", boot.systemdAdapterDbusHost());
    assertEquals(12434, boot.systemdAdapterDbusPort());
    assertEquals(3, boot.hostAssetRotationRetentionCount());
    // No readiness config ⇒ no overrides; every deadline stays the scenario's @ReadinessDeadlines
    // annotation default (the host defaults nothing here anymore).
    assertEquals(ReadinessOverrides.NONE, boot.readinessOverrides());
  }

  @Test
  void kubeconfig_ref_defaults_to_flat_state_dir_path() {
    final BootstrapConfig boot = OperatorConfiguration.mandatory().asBootstrapConfig();
    assertEquals(Path.of(".local.d/kubeconfig.yaml"), boot.kubeconfigRef());
  }
}
