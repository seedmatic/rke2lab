package io.seedmatic.rke2lab.manifests.profiles;

import java.util.List;
import java.util.Map;

/**
 * The taints a fleet pod that must run on every fleet node tolerates: the control plane's, and the
 * ones a node carries while it boots. NAMED, never {@code operator: Exists}: a bare Exists
 * tolerates every taint, a tenant's {@code project=<tenant>:NoSchedule} included, and the pod then
 * lands on the tenant's node with what it mounts. The exclusion rests on the taint because a
 * kubelet cannot change its own taints (NodeRestriction), while it can change an unprefixed label.
 */
public final class FleetTolerationsProfile {

  public FleetTolerationsProfile() {}

  public List<Object> tolerations() {
    return List.of(
        Map.of("key", "node-role.kubernetes.io/control-plane", "operator", "Exists"),
        Map.of("key", "node-role.kubernetes.io/master", "operator", "Exists"),
        Map.of("key", "node-role.kubernetes.io/etcd", "operator", "Exists"),
        Map.of("key", "CriticalAddonsOnly", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/not-ready", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/unreachable", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/disk-pressure", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/memory-pressure", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/pid-pressure", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/network-unavailable", "operator", "Exists"),
        Map.of("key", "node.kubernetes.io/unschedulable", "operator", "Exists"),
        Map.of("key", "node.cloudprovider.kubernetes.io/uninitialized", "operator", "Exists"),
        Map.of("key", "node.cluster.x-k8s.io/uninitialized", "operator", "Exists"),
        Map.of("key", "node.cilium.io/agent-not-ready", "operator", "Exists"));
  }
}
