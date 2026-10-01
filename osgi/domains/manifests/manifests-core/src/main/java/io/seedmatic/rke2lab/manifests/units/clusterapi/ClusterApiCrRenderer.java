package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial.Pair;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The shared Cluster API DELIVERY behaviour both {@link ClusterApiWorkloadManifestsUnit} and {@link
 * ClusterApiManagementManifestsUnit} delegate to — a COLLABORATOR provided at construction, not a
 * static helper: the two units are not in an {@code is-a} relation, they SHARE the behaviour of
 * rendering a cluster's 2×2 intent + the credentials seed-incluster expands its CR-set from. Each
 * unit owns its own {@link PackageMetadataProfile}, so every method takes the profile in — the
 * renderer holds no per-unit state.
 *
 * <p>It builds the pieces the two recipes have in common: the target {@link #namespace}, the
 * cluster-level {@link #clusterIntention} + the control-node {@link #controlNodePoolIntention}
 * (identical intent for mgmt and workload — only kind, pet count and remote endpoint differ), the
 * four CAPRKE2 BYO-CA {@link #caSecrets} (generic pairs, so a workload {@code Entry} or the mgmt CA
 * set both feed it), and the CAPN {@link #identitySecret}. The CAPI/CAPN/CAPRKE2 CR-set itself
 * (Cluster/LXCCluster/RKE2ControlPlane + the owned Machines) is NOT rendered here: the in-cluster
 * {@code seed-incluster} controller materialises it from the {@code ClusterIntention} + {@code
 * PoolIntention} intent (the ownerRef UID + adopt-vs-provision decision are in-cluster facts GitOps
 * cannot pre-set).
 */
public final class ClusterApiCrRenderer {

  public ApiObject namespace(
      final Construct scope,
      final String cluster,
      final String namespace,
      final PackageMetadataProfile profile) {
    return new ApiObject(
        scope,
        "namespace-" + cluster,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(namespace)
                    .annotations(profile.packageAnnotations("|Namespace||" + namespace))
                    .build())
            .build());
  }

  /**
   * The four CAPRKE2 BYO-CA Secrets CAPRKE2 looks up by name ({@code <cluster>-{ca,cca,etcd,
   * peer-etcd}}) to skip generating its own CA — type {@code cluster.x-k8s.io/secret} + the
   * cluster-name label, data {@code tls.crt}/{@code tls.key}. On the BRANCH, sops-encrypted (the
   * git sops clean filter encrypts {@code tls.key} at commit; Flux decrypts). Generic pairs, so a
   * workload {@code Entry} or the mgmt CA set both feed it. See the caprke2-byo-ca-secret-contract
   * memory.
   */
  public void caSecrets(
      final Construct scope,
      final String cluster,
      final String namespace,
      final Pair serverCa,
      final Pair clientCa,
      final Pair etcdServerCa,
      final Pair etcdPeerCa,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    caSecret(scope, cluster, namespace, "ca", serverCa, profile, branchNamespace);
    caSecret(scope, cluster, namespace, "cca", clientCa, profile, branchNamespace);
    // Naming inversion is CAPRKE2's: EtcdServerCA → suffix "etcd", EtcdCA (peer) → "peer-etcd".
    caSecret(scope, cluster, namespace, "etcd", etcdServerCa, profile, branchNamespace);
    caSecret(scope, cluster, namespace, "peer-etcd", etcdPeerCa, profile, branchNamespace);
  }

  private void caSecret(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String purpose,
      final Pair pair,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final String name = cluster + "-" + purpose;
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-ca-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .labels(Map.of("cluster.x-k8s.io/cluster-name", cluster))
                        .annotations(
                            profile.packageAnnotations(
                                "|Secret|" + namespace + "|" + name, Map.of()))
                        .build())
                .build());
    secret.addDependency(branchNamespace);
    secret.addJsonPatch(JsonPatch.add("/type", "cluster.x-k8s.io/secret"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "tls.crt", base64(pair.certChainPem()),
                "tls.key", base64(pair.keyPem()))));
  }

  /**
   * The {@code <cluster>-server-manifests} Secret carrying a workload target's node-side bootstrap
   * bundle — the multi-doc YAML that target's OWN render pass carved out of its branch. {@code
   * seed-incluster} references it from {@code RKE2ControlPlane.spec.files[].contentFrom.secret}
   * (key {@code rke2lab-bootstrap.yaml}), so cloud-init writes it into RKE2's auto-deploy directory
   * before {@code rke2-server} starts and the greenfielded node brings up OUR cilium instead of
   * RKE2's default chart. A reference, never an inlined spec value.
   *
   * <p>On the BRANCH, sops-encrypted, beside the four BYO-CA Secrets it is gated with (the
   * reconciler waits on all five before it stamps the control plane). The branch — not the {@code
   * NODE_BOOTSTRAP} lane the MANAGEMENT cluster's own bundle rides: that lane is applied by the
   * manager node's rke2 auto-deploy at PROVISIONING time only, so a target appearing between two
   * grows would never get its Secret, while Flux delivers this one on the next reconcile.
   * Committing it is safe because sops encrypts to the age RECIPIENT: the repository alone never
   * decrypts it.
   */
  public void serverManifestsSecret(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String bundle,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final String name = cluster + "-server-manifests";
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-server-manifests-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .labels(Map.of("cluster.x-k8s.io/cluster-name", cluster))
                        .annotations(
                            profile.packageAnnotations(
                                "|Secret|" + namespace + "|" + name, Map.of()))
                        .build())
                .build());
    secret.addDependency(branchNamespace);
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(JsonPatch.add("/data", Map.of("rke2lab-bootstrap.yaml", base64(bundle))));
  }

  /**
   * The per-remote CAPN identity Secret. {@code remoteEndpoint} is the address CAPN must DIAL for
   * THIS cluster — the same value the cluster's {@code ClusterIntention.spec.remote.endpoint}
   * states, passed in rather than taken from {@code material}.
   *
   * <p>⚠️ It used to be {@code material.serverAddress()}, which is the address of the host that
   * MINTED the material — right for the root by accident (it minted its own) and wrong for every
   * child. Measured 2026-09-30 on a live cold start: {@code nikopol-incus-identity} carried {@code
   * server: https://nixos.bioskop:8443}, byte-identical to bioskop's own Secret, so nikopol-mgmt's
   * CAPN dialled 172.16.0.1 and timed out on every LXCCluster/LXCMachine reconcile — its
   * self-adoption stuck in {@code Adopting}, {@code 0/1 present, 1 pending}, while its PARENT saw
   * the very same instance {@code Running}.
   *
   * <p>★ That is the fourth instance of one defect family, and the sharpest: {@code 9e4f1b131} had
   * already fixed the endpoint on the INTENTION, which is the value nothing dials, while the value
   * CAPN actually reads — this field — stayed wrong. A fix aimed at the right shape but the wrong
   * reader. So the endpoint is now computed ONCE per target and given to BOTH objects; they can no
   * longer disagree.
   *
   * <h2>Why there is no {@code server-crt}</h2>
   *
   * <p>There used to be, carrying the Incus listener's LEAF, and that is what made the listener
   * certificate unreplaceable: CAPN hands {@code server-crt} to the incus client's {@code
   * TLSServerCert}, the pinned remote certificate, so every regeneration invalidated every pinned
   * copy at once — and a cold start does one. Worse, the pin also SKIPS name verification, which
   * hid for months that the cluster certificate named only one of the two members.
   *
   * <p>Empty, the path is the one the incus client documents — <i>"unless the remote server is
   * trusted by the system CA, the remote certificate must be provided"</i>. Read in incus 7.4:
   * {@code GetTLSConfigMem} leaves {@code RootCAs} nil when the PEM is empty, so Go uses the system
   * pool, and the client transport then runs {@code VerifyHostname(config.ServerName)} because
   * {@code insecure-skip-verify} is false. Three things therefore have to hold, and all three were
   * measured before this field was dropped: the listener leaf is signed by {@code
   * mammoth-skate-tls}, its SAN names EVERY member (one certificate serves the whole cluster), and
   * {@code remoteEndpoint} is a NAME the SAN carries — {@code https://nixos.&lt;host&gt;:8443}.
   *
   * <p>The authority itself reaches the provider pod through {@code
   * ClusterApiOperatorManifestsUnit}; the guard below refuses to emit an identity when this render
   * carries no CA, because an unpinned Secret with no trust anchor verifies nothing.
   */
  public ApiObject identitySecret(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String identitySecret,
      final IncusIdentityMaterial material,
      final String incusProject,
      final String remoteEndpoint,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    if (remoteEndpoint == null || remoteEndpoint.isBlank()) {
      throw new IllegalArgumentException(
          "remoteEndpoint is blank for cluster "
              + cluster
              + " — CAPN would fall back to the minter's address and dial the wrong host");
    }
    // ★ The Secret no longer carries `server-crt`, so CAPN verifies the listener against the SYSTEM
    // pool — which only works if this render also delivers the authority into the provider pod. The
    // two are rendered by sibling units of the same domain, so they travel together; this asserts
    // it
    // rather than trusting it, because the failure it prevents is silent at render time and total
    // at
    // runtime (every LXCCluster reconcile failing an unverifiable handshake).
    if (ManifestSynthesisContext.current().tlsAuthorityCa().isEmpty()) {
      throw new IllegalStateException(
          "no TLS authority CA for cluster "
              + cluster
              + " — the identity Secret pins nothing, so CAPN would have to trust the Incus listener"
              + " by system CA, and this render delivers no CA into capn-system. Refusing to emit an"
              + " identity that cannot verify anything.");
    }
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-incus-identity-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(identitySecret)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "|Secret|" + namespace + "|" + identitySecret, Map.of()))
                        .build())
                .build());
    secret.addDependency(branchNamespace);
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                // The TARGET's endpoint, not the minter's — see this method's javadoc.
                "server", base64(remoteEndpoint),
                // ★ NO `server-crt`. Absent, CAPN leaves `RootCAs` nil and Go falls back to the
                // system pool, then verifies the NAME (`GetTLSConfigMem` + the transport's
                // `VerifyHostname`, incus 7.4). Present, it would pin the leaf and skip both.
                "client-crt", base64(material.clientCert()),
                "client-key", base64(material.clientKey()),
                // Single project (foundation 4 dropped) — the same project the node-base image
                // lives in.
                "project", base64(incusProject))));
    return secret;
  }

  /**
   * The pool identity of the control-plane pool — its CAPI treatment derives from role, not this.
   */
  private static final String CONTROL_NODE_POOL = "control-node";

  /**
   * The cluster-level {@code ClusterIntention} — the Flux-owned intent seed-incluster reconciles
   * adopt-first into the Cluster + LXCCluster. Cluster-scoped facts only (VIP, CIDRs, remote +
   * identity, federated kind); the per-pool roster + template live in the {@code PoolIntention}
   * children built by {@link #controlNodePoolIntention}.
   *
   * <p>{@code remoteEndpoint} is REQUIRED and must name the Incus engine of the host the cluster's
   * instances live on. It used to be allowed empty for the management case, meaning "the local
   * engine, resolve it from the identity Secret's {@code server}" — and that resolution is written
   * from the viewpoint of whoever MINTED the Secret, not of the cluster the intention describes.
   * Measured 2026-09-29: it sent {@code nikopol-mgmt}'s own CAPN to {@code nixos.bioskop} (i/o
   * timeout) while its own engine answered. So the endpoint is stated by every caller and a blank
   * is refused here, at the frontier, rather than travelling as a silent default.
   */
  public ApiObject clusterIntention(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String kind,
      final String vip,
      final int port,
      final List<String> podCidrs,
      final List<String> serviceCidrs,
      final String remoteEndpoint,
      final String identitySecret,
      final String adoptedBy,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    if (adoptedBy == null || adoptedBy.isBlank()) {
      throw new IllegalArgumentException(
          "adoptedBy is blank for cluster "
              + cluster
              + " — an intention must NAME its single adopter (the root names itself). Without it a"
              + " plane falls back to recognising its own name, which is what gave a sub-plane TWO"
              + " adopters: its parent and itself.");
    }
    if (remoteEndpoint == null || remoteEndpoint.isBlank()) {
      throw new IllegalArgumentException(
          "remoteEndpoint is blank for cluster "
              + cluster
              + " — it must NAME the Incus engine the cluster's instances live on. A blank used to"
              + " mean 'resolve it from the identity Secret's server', which answers with the host"
              + " that MINTED the Secret, not the cluster's own: that sent nikopol-mgmt's CAPN to"
              + " nixos.bioskop and it timed out.");
    }
    final ApiObject intention =
        new ApiObject(
            scope,
            "clusterintention-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("ClusterIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "cluster.seedmatic.io|ClusterIntention|"
                                    + namespace
                                    + "|"
                                    + cluster))
                        .build())
                .build());
    intention.addDependency(branchNamespace);
    intention.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterName",
                cluster,
                "namespace",
                namespace,
                "kind",
                kind,
                // The single adopter. Every plane carries the same federation view; this is what
                // lets
                // each derive its ROLE from its POSITION — a plane reconciles an intention iff this
                // names it. See ClusterFleet.adopterOf.
                "adoptedBy",
                adoptedBy,
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", port),
                "clusterNetwork",
                Map.of(
                    "podCIDRs", podCidrs,
                    "serviceCIDRs", serviceCidrs,
                    "serviceDomain", "cluster.local"),
                "remote",
                Map.of("endpoint", remoteEndpoint, "identitySecretName", identitySecret))));
    return intention;
  }

  /**
   * The {@code NodeImage} — the realised node-base image described to the cluster that boots on it:
   * its identity, where it lives, the RKE2 it bakes, and the runtime contract an instance must
   * carry to run it. One per cluster namespace, because every cluster receives the description of
   * the resources it depends on.
   *
   * <p>Named by the image ALIAS, which is what a {@code PoolIntention} references. Rendered from a
   * typed {@link NodeImageCr} rather than an inline map — see that record for why, and for what it
   * still does not guarantee.
   *
   * <p>⚠️ The construct id carries the CLUSTER, the object name does not. Every cluster gets its
   * own NodeImage describing the same artifact, so the metadata name repeats legitimately — it is
   * scoped by namespace — while cdk8s construct ids must be unique within the scope, and the
   * workload unit renders every target into ONE scope. Deriving the id from the alias alone
   * collided on the second target ("There is already a Construct with name 'nodeimage-node-base'"),
   * exactly as its siblings avoid by keying on {@code cluster} / {@code poolName}.
   */
  public ApiObject nodeImage(
      final Construct scope,
      final String cluster,
      final String namespace,
      final ImageState image,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final String name = nodeImageName(cluster, image);
    final ApiObject nodeImage =
        new ApiObject(
            scope,
            "nodeimage-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("NodeImage")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "cluster.seedmatic.io|NodeImage|" + namespace + "|" + name))
                        .build())
                .build());
    nodeImage.addDependency(branchNamespace);
    nodeImage.addJsonPatch(JsonPatch.add("/spec", NodeImageCr.of(image).toSpec()));
    return nodeImage;
  }

  /**
   * The NodeImage's object name — ONE definition, so the CR and the {@code PoolIntention} that
   * references it cannot disagree.
   *
   * <p>⚠️ Cluster-SCOPED, and that is not decoration. Every target renders into the SAME {@code
   * cluster-api/cluster-api-workload} cell, and the exploder names each file by kind + object name
   * — so two NodeImages both called {@code node-base} (legitimate in Kubernetes, being in different
   * namespaces) collapse onto ONE file and the second silently overwrites the first. Measured
   * 2026-09-28: the workload cell held a single {@code 02-nodeimage-node-base.yml} carrying {@code
   * rke2lab-nikopol-mgmt}, so {@code bioskop-wrkld} had no NodeImage at all and its pool sat at
   * {@code ImageMissing} — no machines, no master node.
   *
   * <p>It is also simply the convention its siblings follow ({@code clusterintention-<cluster>},
   * {@code poolintention-<cluster>-control-node}): an object rendered per cluster into a shared
   * cell carries the cluster in its name.
   */
  public static String nodeImageName(final String cluster, final ImageState image) {
    return cluster + "-" + image.imageAlias();
  }

  /**
   * The control-node {@code PoolIntention} — the pool-level intent seed-incluster reconciles into
   * the RKE2ControlPlane + LXCMachineTemplate + the owned per-pet Machine/LXCMachine. {@code role:
   * control-plane} derives the CAPI treatment (etcd members, the object the Cluster references).
   * Its pets are the deterministic control-plane names ({@code <cluster>-master[, -peer1,
   * -peer2]}). No ownerRef to the {@code ClusterIntention}: both ride the branch and are
   * Flux-pruned; the CAPI CR-set under the PoolAdoption is the ownerRef-GC chain. Worker pools are
   * a follow-up (not emitted yet — the coding scope is control-node only).
   */
  public ApiObject controlNodePoolIntention(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final int port,
      final String rke2Version,
      final String imageName,
      final List<String> devices,
      final List<String> petNames,
      final String incusMember,
      final String adoptedBy,
      final PackageMetadataProfile profile,
      final ApiObject clusterIntention) {
    if (adoptedBy == null || adoptedBy.isBlank()) {
      throw new IllegalArgumentException(
          "adoptedBy is blank for pool of " + cluster + " — it decides the pool's NATURE");
    }
    final String poolName = cluster + "-" + CONTROL_NODE_POOL;
    final List<Object> nodes =
        petNames.stream().map(name -> (Object) Map.of("name", name)).toList();
    final ApiObject poolIntention =
        new ApiObject(
            scope,
            "poolintention-" + poolName,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("PoolIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(poolName)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "cluster.seedmatic.io|PoolIntention|" + namespace + "|" + poolName))
                        .build())
                .build());
    poolIntention.addDependency(clusterIntention);
    // A LinkedHashMap, not Map.of: the spec passed ten pairs (Map.of's limit) and — more usefully —
    // an insertion-ordered map makes the rendered YAML diff as a CHANGE rather than a reshuffle.
    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("clusterRef", cluster);
    spec.put("namespace", namespace);
    spec.put("pool", CONTROL_NODE_POOL);
    spec.put("role", "control-plane");
    // NAMES the NodeImage in this namespace — the pool no longer embeds a fingerprint, so the
    // controller derives the image pin AND the runtime contract from that one object.
    spec.put("image", Map.of("name", imageName));
    spec.put("rke2Version", rke2Version);
    spec.put("controlPlaneEndpoint", Map.of("host", vip, "port", port));
    spec.put("nodes", nodes);
    // WHO NAMES these instances — the triad's Q2, declared instead of read off the presence of a
    // PoolReflection file (a runtime accident: the same pool answered "declaration" before its
    // first
    // reflection existed and "provisioner" after).
    //
    // `pet` iff the cluster ADOPTS ITSELF — which is the root, and the root alone, because it is
    // the
    // one plane grown out-of-band by a host `grow` that POSED its instance and its name. Every
    // other
    // cluster is birthed by a parent through CAPRKE2, which mints the name.
    //
    // ⚠️ NOT derivable from the role, and that is why it must be stated: nikopol-mgmt is
    // `kind: management` and CATTLE (its node is …-control-plane-9f5kb, not the declared …-master).
    spec.put("nature", cluster.equals(adoptedBy) ? "pet" : "cattle");
    // The adopter, at the POOL level too. Gating only the ClusterIntention left this one open: a
    // plane
    // carried a pool it does not adopt and acted on it, stopped only by material it happens not to
    // render — a safe accident, not a rule. The gate must sit at every level the render reaches.
    spec.put("adoptedBy", adoptedBy);
    // The DECLARED size — what the control plane is sized from. The controller used to take
    // len(roster), and the roster is OBSERVED, so a pool that had run at N was re-declared N for
    // ever: bioskop-wrkld declared three pets and stood at one across two cold starts, because a
    // one-node PoolReflection survives in git. The count is intent; the roster answers only which
    // NAMES. Derived from the pet list because that IS the declared shape here — one expression, so
    // the two cannot disagree.
    spec.put("replicas", nodes.size());
    spec.put("nodeLabels", poolNodeLabels());
    // The node's Incus devices, posed INLINE on the LXCMachine by CAPN. They replaced the
    // `node-base` + `node-<cluster>` profiles the host grow used to create for clusters it does not
    // otherwise know about — see NodeDeviceSet.
    //
    // ⚠️ The FULL set, including `root`: with no profile named, nothing else supplies a disk.
    // Incus's
    // own `default` profile carries one, but it is not applied when the profile list is empty — and
    // ours (`node-base`) is exactly what is being removed. Measured on the live daemon 2026-09-28.
    spec.put("devices", List.copyOf(devices));
    // WHERE this pool's instances are created — seed-incluster poses it on
    // LXCMachineTemplate.spec.target, and through it CAPN's placement. It must be stated: Incus
    // otherwise picks the member with the fewest instances and breaks ties AT RANDOM, so a node can
    // be born on a bare-metal whose dnsmasq holds no reservation for it and whose subnet does not
    // address this cluster. Harmless while exactly one member is eligible — which is what ndh
    // arranges today, and exactly what stops being true for a cluster on the second bare-metal.
    spec.put("target", incusMember);
    poolIntention.addJsonPatch(JsonPatch.add("/spec", spec));
    return poolIntention;
  }

  /**
   * The kubelet node labels every node of a pool registers with. seed-incluster poses them on the
   * RKE2ControlPlane's {@code agentConfig.nodeLabels} — CAPRKE2's own field for this, which is why
   * they are declared as pool intent rather than smuggled in as a {@code config.yaml.d} fragment.
   *
   * <p>It has to be declared HERE because the nixos {@code rke2lab-node-labels} oneshot that poses
   * the same label on a host-grown node is gated on {@code /var/lib/rke2lab/node.env}, which a
   * CAPN-provisioned node does not have. Measured 2026-09-23 on {@code bioskop-wrkld}: without the
   * flox-runtime label the flox-controller DaemonSet's {@code nodeSelector} matched nothing there,
   * so no flox environment was GC-rooted under {@code /nix/var/nix/gcroots/flox-runtime/env} and
   * the NRI plugin refused every flox-carrier container — headscale, kdns and seed-incluster all
   * stalled on that one missing label.
   *
   * <p>Fleet-wide today (every node runs flox), so it takes no parameter; the constant is the
   * single source the flox DaemonSet's {@code nodeSelector} also reads.
   */
  private static List<Object> poolNodeLabels() {
    return List.of(ManifestAnnotation.NODE_FLOX_RUNTIME_LABEL.key() + "=true");
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
