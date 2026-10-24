package io.seedmatic.rke2lab.controlplane;

import io.seedmatic.rke2lab.controlplane.config.BootstrapConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The stack outputs a throwaway grow exports, which the installed {@code rke2lab-throwaway} reads
 * to fetch the cluster's kubeconfig and to know what its reset may delete. Every name is DERIVED
 * from the stack and its committed config, never free, and the reset checks each against the same
 * rule:
 *
 * <ul>
 *   <li>{@value #INCUS_PROJECT}: {@code throwaway-<stack>}. Every deletion of the reset runs inside
 *       it, so it is refused when it would be the fleet's project ({@code rke2lab}) or Incus's own
 *       ({@code default}).
 *   <li>{@value #NODE}: {@code <host>-mgmt-master}, the blueprint's node name. It is the node's
 *       hostname, and its fabric address is the dnsmasq reservation keyed on that name.
 *   <li>{@value #API_SERVER}: {@code https://<host>-mgmt-master.<host>:6443}, the fabric name the
 *       operator seat resolves, which the throwaway's tls-san drop-in carries.
 * </ul>
 *
 * <p>The fleet never exports these: a fleet stack's outputs are none of the reset's business.
 */
final class ThrowawayOutputs {

  static final String INCUS_PROJECT = "throwawayIncusProject";
  static final String NODE = "throwawayNode";
  static final String API_SERVER = "throwawayApiServer";

  private static final String PROJECT_PREFIX = "throwaway-";
  private static final String NODE_NAME = "master";
  private static final String ROLE = "mgmt";
  private static final Pattern STACK = Pattern.compile("[a-z][a-z0-9-]*");

  private final String stack;
  private final BootstrapConfig config;

  ThrowawayOutputs(String stack, BootstrapConfig config) {
    this.stack = stack;
    this.config = config;
  }

  /** The Incus project a throwaway stack's resources live in, refused unless it is derived. */
  static String incusProjectOf(String stack) {
    if (!STACK.matcher(stack).matches()) {
      throw new IllegalStateException(
          "a throwaway stack name is lowercase letters, digits and dashes, not '" + stack + "'");
    }
    return PROJECT_PREFIX + stack;
  }

  /**
   * The three outputs, after checking that the config the grow runs with names exactly those: a
   * mgmt cluster whose single node is {@code master}, in project {@code throwaway-<stack>}.
   */
  Map<String, String> values() {
    final String project = incusProjectOf(stack);
    if (!project.equals(config.incusProject())) {
      throw new IllegalStateException(
          "a throwaway stack grows in Incus project '"
              + project
              + "', but its config says '"
              + config.incusProject()
              + "': set rke2lab:incus:project to it");
    }
    if (!ROLE.equals(config.role()) || !NODE_NAME.equals(config.nodeName())) {
      throw new IllegalStateException(
          "a throwaway stack is a single-node "
              + ROLE
              + " cluster ("
              + NODE_NAME
              + "), not "
              + config.role()
              + "/"
              + config.nodeName());
    }
    final String node = config.clusterName() + "-" + config.nodeName();
    final Map<String, String> outputs = new LinkedHashMap<>();
    outputs.put(INCUS_PROJECT, project);
    outputs.put(NODE, node);
    outputs.put(API_SERVER, "https://" + node + "." + config.host() + ":6443");
    return outputs;
  }
}
