package io.seedmatic.rke2lab.incus.ingress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The container contract a node instance must carry to run a Kubernetes node — the {@code
 * linux.kernel_modules}, {@code raw.lxc} and {@code security.*} keys, as ONE value with ONE
 * definition.
 *
 * <p>★ It lives here because two parties must agree on it and they sit on opposite sides of the
 * host↔cluster seam: the host GROW stamps it onto the {@code node-base} incus profile, and the
 * incus scion forwards it in the {@code IMAGE_STATE} amendment so the rendered {@code NodeImage} CR
 * hands it to the in-cluster controller, which poses it on {@code LXCMachine.spec.config}. Instance
 * config WINS over profile config, so the in-cluster copy is the one that decides — which is
 * exactly why it must be derived from this one value rather than restated.
 *
 * <p>⚠️ It was restated, and it diverged: the in-cluster copy carried nine modules where the
 * profile carried sixteen, missing every entry the cilium block below was measured for. Same shape
 * as {@link SplitImageFingerprint} — an agreement between two parties, so the definition lives
 * once.
 */
public record NodeRuntimeContract(
    List<String> kernelModules,
    String rawLxc,
    boolean privileged,
    boolean nesting,
    boolean interceptBpf,
    boolean interceptBpfDevices) {

  public NodeRuntimeContract {
    kernelModules = List.copyOf(kernelModules);
  }

  /**
   * The contract our nix-built node-base needs on the nftables-only kernel-6.18 substrate: the CAPN
   * default kernel set MINUS the legacy iptables trio it drops (ip_tables/ip6_tables/iptable_raw
   * FATAL-modprobe), PLUS what cilium needs given that choice.
   *
   * <p>Every cilium entry was established by loading it and watching the agent, never by guessing:
   *
   * <ul>
   *   <li>{@code xfrm_user} — the agent's route reconciler calls {@code
   *       safenetlink.NewHandle(nil)}, and a handle with no family list opens a socket for EVERY
   *       supported family (NETLINK_ROUTE, NETLINK_XFRM, NETLINK_NETFILTER). Without it the XFRM
   *       socket returns EPROTONOSUPPORT and the start hook dies with "protocol not supported", so
   *       the agent never runs at all.
   *   <li>{@code nft_compat} + the {@code xt_*} extensions — cilium ships iptables v1.8.8 on the
   *       nf_tables backend, which realises {@code -m mark}, {@code -m comment}, {@code -j CT} and
   *       {@code -j TPROXY} through nft_compat. Absent, every such rule fails ("Extension mark
   *       revision 0 not supported") and only 10 of its 34 rules land. The consequence is subtle
   *       and total: the missing rules are the ones stamping MARK_MAGIC_HOST on host-originated
   *       traffic, so {@code inherit_identity_from_host()} falls to its else-branch and returns
   *       WORLD_ID, and {@code resolve_srcid_ipv4()} then DELIBERATELY refuses to promote it back
   *       (under ingress SNAT a world packet also carries the host's source IP). Every host→pod
   *       packet reads as {@code world-ipv4}, so any pod carrying a policy denies the kubelet's
   *       health probes — flux's controllers sat 0/1 forever with the ipcache and the policy map
   *       both saying {@code reserved:host} and zero packets matched. Loading these took the rule
   *       count 10 → 34.
   *   <li>{@code xt_comment}/{@code xt_conntrack} are in the same rules; they happen to be live on
   *       bioskop-nixos from its own configuration, and are named so a node does not depend on
   *       that.
   * </ul>
   *
   * <p>This belongs to rke2lab rather than to the hypervisor's NixOS config: a kernel need of
   * rke2lab's nodes is rke2lab's to declare, and stated here it travels to whatever host runs the
   * container, including a CAPN-grown node on another machine. A container cannot modprobe for
   * itself in any case — it has neither kernel nor module tree, so incus doing it on the host is
   * the only mechanism there is. (ndh once carried an orphan {@code cilium-kernel-modules.nix} that
   * nothing imported and that listed everything except what mattered.)
   */
  public static NodeRuntimeContract nodeBase() {
    return new NodeRuntimeContract(
        List.of(
            "ip_vs",
            "ip_vs_rr",
            "ip_vs_wrr",
            "ip_vs_sh",
            "netlink_diag",
            "nf_nat",
            "overlay",
            "br_netfilter",
            "xt_socket",
            "xfrm_user",
            "nft_compat",
            "xt_mark",
            "xt_CT",
            "xt_TPROXY",
            "xt_comment",
            "xt_conntrack"),
        String.join(
            "\n",
            "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
            "lxc.apparmor.profile = unconfined",
            "lxc.cap.drop ="),
        true,
        true,
        true,
        true);
  }

  /**
   * The incus config keys this contract becomes — the profile form the host GROW declares and the
   * instance form the in-cluster controller poses, which must agree key for key. Ordered so a diff
   * reads the same on both sides.
   */
  public Map<String, String> toIncusConfig() {
    final Map<String, String> config = new LinkedHashMap<>();
    config.put("raw.lxc", rawLxc);
    config.put("security.privileged", Boolean.toString(privileged));
    config.put("security.nesting", Boolean.toString(nesting));
    config.put("security.syscalls.intercept.bpf", Boolean.toString(interceptBpf));
    config.put("security.syscalls.intercept.bpf.devices", Boolean.toString(interceptBpfDevices));
    config.put("linux.kernel_modules", String.join(",", kernelModules));
    return config;
  }
}
