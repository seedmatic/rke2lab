package io.seedmatic.rke2lab.incus.ingress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The devices a node instance carries — root disk, the two unix-char passthroughs, and the two
 * NICs. A pure leaf SHARED by the two parties that must agree on them, exactly like {@link
 * SplitImageFingerprint}: the host GROW poses them on the standalone master's Incus instance, and
 * the render publishes them for CAPN to pose on every other node.
 *
 * <p>★ They used to live in two Incus PROFILES ({@code node-base} + {@code node-<cluster>}) that
 * the host created as Pulumi resources for clusters it does not otherwise know about. The profiles
 * are gone: a profile carried nothing but config and devices, the config is already posed inline,
 * and the provider could CREATE a profile's devices but never CORRECT them — so any change to a
 * NIC's parent or name reached an existing profile only if an operator deleted it by hand. Inlining
 * them deletes the resources, the defect, and the {@code node-<cluster>} naming convention that was
 * derived on both sides of the Java/Go boundary.
 *
 * <p>Definition here, ADAPTATION at each consumer: this module cannot name a Pulumi type, and the
 * CR wants CAPN's flat {@code <device>,<key>=<value>} strings. So the leaf owns WHAT the devices
 * are and each side renders them its own way — {@link #toCapnSpecs()} for the CR, {@link
 * Device#properties()} for the provider's device args.
 */
public record NodeDeviceSet(List<Device> devices) {

  /** The root storage pool every node's disk comes from. */
  private static final String ROOT_POOL = "default";

  public NodeDeviceSet {
    devices = List.copyOf(Objects.requireNonNull(devices, "devices"));
  }

  /**
   * One Incus device. {@code properties} is ordered so a rendered CR diffs as a change rather than
   * a reshuffle.
   */
  public record Device(String name, String type, Map<String, String> properties) {
    public Device {
      name = Objects.requireNonNull(name, "name");
      type = Objects.requireNonNull(type, "type");
      // NOT Map.copyOf: it returns an UNORDERED map, and the rendered device string is
      // `<device>,<key>=<value>,…` — so a lost order makes the CR churn between runs on nothing but
      // hash iteration. The same bug class YamlMapper documents for Map.of.
      properties =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(properties, "properties")));
    }
  }

  /**
   * The set a node of {@code vmnetBridgeName}'s cluster needs, attached to the host's {@code
   * fabricBridgeParent}.
   *
   * <p>The NICs carry NO hwaddr here: a CAPN-provisioned node takes a dynamic MAC and the vmnet
   * bridge's {@code ipv4.dhcp.ranges} hands out its address (avahi/mDNS is IP-agnostic, so no
   * reservation is needed). The standalone master overrides both NICs with the blueprint's
   * DETERMINISTIC hwaddrs, because its dnsmasq reservation is what makes its name resolve —
   * instance devices win over anything else, so that override is the one that decides.
   */
  public static NodeDeviceSet forCluster(
      final String fabricBridgeParent, final String vmnetBridgeName) {
    Objects.requireNonNull(fabricBridgeParent, "fabricBridgeParent");
    Objects.requireNonNull(vmnetBridgeName, "vmnetBridgeName");
    // No emptiness test here, deliberately: absence belongs to the TYPE, not to a value this would
    // have to recognise. The facet carries Optional<NetworkFacet> and its consumer resolves it, so
    // a
    // blank parent never reaches this point and nothing downstream must remember to check for one.
    // The alternative was measured on 2026-09-28: a sentinel blank meaning "unamended" travelled
    // through unchecked and the render published `parent=` for every node, a tree Flux would have
    // applied over a correct live value.
    final List<Device> devices = new ArrayList<>();
    devices.add(new Device("root", "disk", ordered("path", "/", "pool", ROOT_POOL)));
    devices.add(unixChar("kmsg.dev", "/dev/kmsg"));
    devices.add(unixChar("zfs.dev", "/dev/zfs"));
    devices.add(nic("fabric0", fabricBridgeParent));
    devices.add(nic("vmnet0", vmnetBridgeName));
    return new NodeDeviceSet(devices);
  }

  /**
   * CAPN's device syntax — {@code <device>,<key>=<value>} per entry, which it parses back into
   * Incus device properties. What {@code LXCMachine.spec.devices} takes.
   */
  public List<String> toCapnSpecs() {
    final List<String> specs = new ArrayList<>(devices.size());
    for (final Device device : devices) {
      final StringBuilder spec =
          new StringBuilder(device.name()).append(",type=").append(device.type());
      device
          .properties()
          .forEach((key, value) -> spec.append(',').append(key).append('=').append(value));
      specs.add(spec.toString());
    }
    return List.copyOf(specs);
  }

  private static Device unixChar(final String name, final String path) {
    return new Device(name, "unix-char", ordered("source", path, "path", path));
  }

  private static Device nic(final String name, final String parent) {
    // `name` doubles as the in-guest interface name: the NixOS substrate's networkd matches on
    // fabric0 / vmnet0, so the device name and the link name must be the same string.
    return new Device(name, "nic", ordered("name", name, "nictype", "bridged", "parent", parent));
  }

  private static Map<String, String> ordered(final String... keyValues) {
    final Map<String, String> properties = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      properties.put(keyValues[i], keyValues[i + 1]);
    }
    return properties;
  }
}
