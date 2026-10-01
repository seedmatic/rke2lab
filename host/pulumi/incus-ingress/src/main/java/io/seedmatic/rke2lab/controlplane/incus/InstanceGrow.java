package io.seedmatic.rke2lab.controlplane.incus;

import com.pulumi.command.local.Command;
import com.pulumi.command.local.CommandArgs;
import com.pulumi.core.Output;
import com.pulumi.incus.Certificate;
import com.pulumi.incus.CertificateArgs;
import com.pulumi.incus.Image;
import com.pulumi.incus.ImageArgs;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Network;
import com.pulumi.incus.NetworkArgs;
import com.pulumi.incus.Project;
import com.pulumi.incus.ProjectArgs;
import com.pulumi.incus.inputs.ImageSourceFileArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowImageView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.seedmatic.rke2lab.incus.ingress.IngressConfig;
import io.seedmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import io.seedmatic.rke2lab.incus.ingress.NodeDeviceSet;
import io.seedmatic.rke2lab.incus.ingress.NodeRuntimeContract;
import io.seedmatic.rke2lab.incus.ingress.SplitImageFingerprint;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pure-host GROW beat — the {@code com.pulumi} graph that actualises the {@link
 * InstanceGrowPlan} the incus scion projected (§ host-cellar § the-grow-anatomy, the
 * scion-projects/host-actualises rule). It runs OUTSIDE Felix (the Pulumi graph cannot enter it),
 * so it is NOT a scion; it computes NOTHING of the domain — it fetches the plan, adopts
 * pre-existing project/network via provider invokes ({@link IncusImportLookup}), and declares
 * Project→{Network,Profile,Image}→Instance from the plan's own flat values (network, image, and the
 * per-node identity posed as {@code user.rke2lab.node-*} keys). The NixOS node-base substrate bakes
 * the node's config, so there are no host disk mounts and no cloud-init seed.
 *
 * <p>Instance-passing: it holds the run's {@link IngressConfig} (the ingress vocabulary the run
 * fills — it names no seed-outcluster type), the {@link IncusProviderContext} it builds once, the
 * {@link IncusImportLookup} riding that context's invoke options, and a log sink. Its one act is
 * {@link #grow(InstanceGrowPlan)}.
 */
public final class InstanceGrow {

  /** The trust-store entry name for the in-cluster CAPN provider's identity. */
  private static final String CAPN_TRUST_ENTRY = "capn-provider";

  private final IngressConfig config;
  private final IncusProviderContext providerContext;
  private final IncusImportLookup importLookup;
  private final Consumer<String> log;

  public InstanceGrow(IngressConfig config, Consumer<String> log) {
    this.config = config;
    this.providerContext = IncusProviderContext.forBootstrap("seed-incus-provider", config);
    this.importLookup = new IncusImportLookup(providerContext, log);
    this.log = log;
  }

  /** Declare the whole instance graph from the plan; the Pulumi engine schedules it. */
  public void grow(InstanceGrowPlan plan) {
    grow(plan, NodeBootstrapMaterial.none());
  }

  /**
   * Grow, laying the per-node bootstrap material into the guest through the UNIFORM cloud-init
   * channel: the GROW renders the per-node identity ({@code plan.identity()}) + the revealed {@link
   * NodeBootstrapMaterial} (the sops-sealed CA bundle + age identity the PKI seal scion filed, and
   * the node-side bootstrap manifests the synthesis scion carved) into ONE {@code
   * cloud-init.user-data} cloud-config posed on the Instance — a {@code write_files} set the NixOS
   * node-base consumes at boot (see {@link #mgmtCloudConfig}). The values are opaque here — the
   * scenario fetched + revealed them. This is the SAME channel CAPN/CAPRKE2 use for a workload
   * node, so there is one delivery mechanism across standalone and in-cluster grows.
   */
  public void grow(InstanceGrowPlan plan, NodeBootstrapMaterial material) {
    final Project project = ensureProject();
    ensureCapnTrust();
    ensureNetworks(project, plan.network());
    // NO profiles. A profile carried only config + devices, and the instance now poses BOTH inline
    // from the very definitions the render publishes for CAPN nodes — NodeRuntimeContract and
    // NodeDeviceSet. One fewer Incus object per cluster, no `node-<cluster>` naming convention
    // derived on both sides of the Java/Go boundary, and no profile-vs-instance precedence to
    // reason
    // about: both paths now assert the contract at instance level.
    final Output<String> imageFingerprint = ensureImage(plan.image(), project);
    final Instance instance = createInstance(plan, project, imageFingerprint, material);
    poseNodeBaseAliasAndGcImages(imageFingerprint, instance);
  }

  private Project ensureProject() {
    final CustomResourceOptions.Builder options =
        CustomResourceOptions.builder().provider(providerContext.provider()).retainOnDelete(true);
    importLookup.existingProjectId(config.incusProject()).ifPresent(options::importId);

    return new Project(
        "seed-project",
        ProjectArgs.builder()
            .name(config.incusProject())
            // No `features.networks`: it is what made incus refuse the project on a fresh daemon
            // ("OVN is required for projects with features.networks enabled"), and it was asked for
            // backwards. The flag gives the project its OWN network set — empty here, since
            // ensureNetworks() declares every bridge in the DEFAULT project. Inheriting the default
            // project's networks is what we want, and that is what the flag being absent does. It
            // is moot for the instances either way: their NICs are `nictype=bridged` with a
            // `parent`, which incus resolves as a host interface name without consulting its
            // network list at all.
            .build(),
        options.build());
  }

  /**
   * The capn-provider trust entry. The daemon MUST trust the certificate the IN-CLUSTER CAPN
   * provider authenticates with, or CAPN answers `not authorized` on every reconcile — a failure
   * that surfaces only as a Cluster API health-check timeout several layers up, with the real cause
   * visible on no CR at all. It is declared here because the trust store is DAEMON STATE:
   * re-minting a node's Incus certificates, or re-materialising the node, drops the entry and
   * nothing else brings it back.
   *
   * <p>NOT adopted by {@code importId}, though it was at first — to take over the entry an operator
   * had added by hand to unblock CAPN. That adoption happened once; leaving the import declaration
   * on a resource Pulumi now holds in state made every run plan a replacement with NO property diff
   * (observed 2026-09-21: {@code importing replacement} → {@code replacing[retain]}), the same
   * defect the profile workaround above documents. So the declaration goes: from here the resource
   * is state-managed and diffs normally, and a hand-added entry on a virgin host collides once,
   * loudly, rather than churning forever.
   */
  private void ensureCapnTrust() {
    final String pem = config.capnProviderCertPem();
    if (pem == null || pem.isBlank()) {
      log.accept("incus capn trust: no capn-provider certificate in the ingress config; skipping");
      return;
    }
    // Adopt BY OMISSION when the daemon already trusts this exact certificate — the same discipline
    // as ensureImage / ensureNetwork, and this was the ONE resource here that had
    // neither it nor an importId.
    //
    // ⚠️ Its javadoc above accepted a loud one-time collision, on the premise that a pre-existing
    // entry means an operator added one by hand to a virgin host. That premise no longer holds: the
    // trust store is DAEMON state and outlives the Pulumi stack, so a cold start on a FRESH stack
    // meets the entry a previous run created — every time, not rarely. Measured 2026-09-28: the
    // stack held 18 resources and no Certificate while the daemon was trusting one, and the grow
    // died on "Certificate already in trust store". `pulumi refresh` cannot close that gap — it
    // reconciles resources already IN the state and never discovers ones absent from it, which is
    // why `seed-project` (which DOES importId) was adopted and this alone was not.
    //
    // Addressed by CONTENT: a rotated certificate has a different fingerprint, so it reads as
    // absent
    // and is created. Nothing has to be corrected in place, and no entry has to be renamed.
    final String fingerprint = certificateFingerprint(pem);
    if (importLookup.certificateTrusted(fingerprint)) {
      log.accept(
          "incus capn trust: the daemon already trusts "
              + fingerprint
              + "; adopting by omission (no Certificate declared)");
      return;
    }
    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .build();

    // ★ RESTRICTED, scoped to this run's project — least privilege, and checked against incus 7.4's
    // own authorisation driver (internal/server/auth/driver_tls.go) rather than hoped for, because
    // an
    // over-restricted identity fails as an opaque 403 at the first reconcile:
    //
    //   * `ObjectTypeServer` + `CanView` is granted to a restricted certificate, which is what
    //     targeting a member needs — `GET /1.0/cluster` and `/1.0/cluster/members` are exactly that
    //     entitlement (api_cluster.go). The `CanEdit` half, which would let it reshape the cluster,
    //     is refused.
    //   * instances, volumes and networks resolve by PROJECT, and this project is the one listed.
    //   * images are readable by explicit inheritance from `default`, so a node-base living there
    //     stays visible.
    //   * storage POOLS grant `CanView` only — enough, since the pool is ndh's to create, never
    // ours.
    //
    // ★ And the property that makes this a boundary rather than a convention:
    // `ObjectTypeCertificate`
    // grants a restricted certificate `CanView` ALONE. CAPN therefore cannot enrol a certificate at
    // all — it can never widen its own trust. That asymmetry is the point of registering it from
    // HERE:
    // seed-outcluster holds the operator's unrestricted identity, so it is the only party that CAN
    // grant,
    // and what it grants cannot grant further.
    //
    // ⚠️ Left unrestricted, this entry is full incus admin for a pod. That is what a hand-added
    // entry
    // gives you, and it is why this is declared rather than remembered.
    new Certificate(
        "seed-capn-provider-trust",
        CertificateArgs.builder()
            .name(CAPN_TRUST_ENTRY)
            .type("client")
            .certificate(pem)
            .restricted(true)
            .projects(List.of(config.incusProject()))
            .description("rke2lab: the in-cluster CAPN provider's identity")
            .build(),
        options);
  }

  /**
   * How incus names a trust entry: the hex sha256 of the certificate's DER encoding. Parsed through
   * {@code CertificateFactory} rather than by stripping the PEM armour by hand, so a stray newline
   * or a bundle with trailing whitespace cannot yield a fingerprint that silently matches nothing.
   */
  private static String certificateFingerprint(String pem) {
    try {
      final var certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(
                      new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(
          "the capn-provider certificate in the ingress config is not a readable X.509 PEM", ex);
    }
  }

  /**
   * Ensure EVERY vmnet bridge the host carries — one per cluster co-located on it (vmnet is
   * isolated per-cluster), so a workload cluster's bridge + dnsmasq reservations exist for CAPN to
   * DHCP-provision it in-cluster even though only the management node grows standalone. The scion
   * assembled each bridge's config OSGi-side from the netplan blueprint; the host only poses it.
   */
  private void ensureNetworks(Resource projectDependency, GrowNetworkView view) {
    view.clusterBridges()
        .values()
        .forEach(cb -> ensureNetwork(cb.bridgeName(), cb.config(), projectDependency));
  }

  /**
   * Ensure one vmnet bridge from its resolved config. Skips the canonical host-provided LAN bridge
   * and any bridge the provider reports UNMANAGED (a provider invoke, not ssh).
   *
   * <p>DECLARED, always — never adopted by omission, the same correction the (now deleted) node
   * profiles took on 2026-09-21 for the same reason. The guard this replaces ("an already-existing
   * vmnet bridge is adopted, not re-declared, its config is host-owned") made the bridge config
   * WRITE-ONCE: the two live bridges were absent from Pulumi state entirely, so `pulumi preview`
   * planned no network at all and every {@code ipv4.*}/{@code ipv6.*}/{@code raw.dnsmasq} change
   * since their creation reached nothing. It cost a full investigation: {@code raw.dnsmasq} needed
   * {@code no-hosts} (a pod was resolving a host name to its own loopback) and the code change was
   * inert until an operator ran {@code incus network set} by hand.
   *
   * <p>{@code config} is therefore NOT ignored — reconciling it is the point. The ignored keys are
   * the ones whose diff would be SPURIOUS rather than real: the provider reads {@code project} back
   * null (and it is ForceNew), and the provider models {@code devices} as an ordered list that
   * churns — the defect that made the node profiles impossible to correct, and one reason they are
   * gone. Ignoring them suppresses a phantom replacement, never a genuine change. Recreating a
   * bridge is NOT what that list guards against — the operator has ruled (2026-09-23) that a
   * recreate is fine, the instances being re-growable.
   *
   * <p>⚠️ MIGRATION, once: a bridge in the daemon but not in state fails the create loudly with
   * "already exists". The straight path is to DELETE the instances and then the bridges, and let
   * the next {@code up} create both from this code — state matches the code by construction, and
   * nothing durable rides a bridge (leases are re-derived from the {@code dhcp-host} reservations,
   * the persist datasets are ZFS and outlive both). {@code pulumi import} is the no-downtime
   * alternative but needs the provider passed explicitly: the CLI otherwise resolves "latest"
   * against {@code pulumi/pulumi-incus}, which is not an org repo, and fails 404.
   */
  private void ensureNetwork(
      String networkName, Map<String, String> bridgeConfig, Resource projectDependency) {
    if (networkName.equals(config.fabricBridgeParent())) {
      log.accept(
          "incus network ensure: skipping canonical host-provided bridge (" + networkName + ")");
      return;
    }
    // vmnet bridges live in the default project (only OVN networks are allowed in non-default
    // projects).
    final String networkProject = "default";
    if (importLookup.isUnmanagedNetwork(networkName, networkProject)) {
      log.accept("incus network ensure: skipping unmanaged bridge (" + networkName + ")");
      return;
    }

    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("name", "project", "type", "description"))
            .build();

    new Network(
        "seed-network-" + networkName,
        NetworkArgs.builder()
            .name(networkName)
            .type("bridge")
            .project("default")
            .config(bridgeConfig)
            .build(),
        options);
  }

  /**
   * Declare the seed image as a provider {@code Image} resource sourcing the edge-built artifacts,
   * and return its fingerprint {@link Output} for the instance. The edge {@code ImageBuilder} now
   * only BUILDS the artifacts (nix → metadata.tar.xz + rootfs.squashfs); the IMPORT is the
   * provider's — so the engine orders {@code Project → Image → Instance} in one graph, with no
   * out-of-graph CLI import that would require the project to pre-exist (the defect when the
   * operator recreates the incus project).
   *
   * <p>The resource NAME carries the {@code buildChecksum} (the content key the scion computes): an
   * unchanged tree keeps the same name → the same {@code Image}, a no-op with no redundant upload;
   * a content change is a NEW resource → a fresh upload and a new daemon-computed fingerprint,
   * which arms the instance's {@code replaceOnChanges} on {@code user.rke2lab.imageBuildChecksum}
   * to recreate it. No declared {@code aliases}: the instance references the image by the
   * fingerprint {@link Output} returned here, and a stable alias carried as an {@code Image}
   * attribute would collide on the daemon's per-project alias uniqueness while the old and new
   * images coexist at replace time (they DO coexist: the old image is {@code retainOnDelete}, held
   * until the instance stops cloning it). The {@code node-base} alias is posed imperatively AFTER
   * the instance is (re)created — see {@link #poseNodeBaseAliasAndGcImages}, which also GCs the
   * leaked old images once the replaced instance no longer clones them.
   */
  private Output<String> ensureImage(GrowImageView view, Resource projectDependency) {
    final Path metadata = Path.of(view.metadataPath());
    final Path rootfs = Path.of(view.dataPath());
    // ★ NO ARTEFACTS IS NOT NO IMAGE. A surveying run is handed the SurveyingImageBuilder, which
    // plans the build and shells nothing, so the two files never appear — but the image itself is
    // still identifiable, because the DAEMON holds the one a previous grow posed the node-base
    // alias
    // on. A fresh worktree empties the Pulumi stack, not the daemon.
    //
    // ⚠️ Measured 2026-09-28 on a fresh worktree: computing the fingerprint from the files as the
    // FIRST act died on NoSuchFileException before declaring anything. It had only ever appeared to
    // work because earlier REAL runs left incus.tar.xz + rootfs.squashfs lying in the old
    // checkout's
    // .local.d — the step was reading leftovers, not state.
    //
    // So resolve, never invent and never silently skip: an invented fingerprint would make a
    // preview
    // claim an image that does not exist, and dropping the instance would make a survey describe a
    // cluster nobody asked for. If no source answers, that IS an error and it says which sources
    // were
    // tried (see host-cellar-realisation-spec: the cellar is the DESIGNED third source, and
    // reaching
    // it from here is the unwired part of that spec — named in the message rather than faked).
    if (!Files.isRegularFile(metadata) || !Files.isRegularFile(rootfs)) {
      final String recovered =
          importLookup
              .nodeBaseFingerprint(config.incusProject())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "no node-base image can be identified: no built artifacts at "
                              + metadata
                              + " + "
                              + rootfs
                              + " (a surveying run builds none) and the daemon holds no '"
                              + IncusImportLookup.NODE_BASE_ALIAS
                              + "' alias in project "
                              + config.incusProject()
                              + ". Mint it with a live grow, or fetch the image harvest from the"
                              + " cellar once that path is wired."));
      log.accept(
          "incus image ensure: no built artifacts — recovered the image from the daemon's "
              + IncusImportLookup.NODE_BASE_ALIAS
              + " alias ("
              + recovered
              + "); adopting by omission");
      return Output.of(recovered);
    }
    // Content-addressed: incus derives a SPLIT image's fingerprint as sha256(metadata.tar.xz ++
    // rootfs.squashfs), metadata first (verified against the live daemon). Compute it host-side to
    // decide whether the daemon already holds this exact content before deciding to upload.
    final String fingerprint = SplitImageFingerprint.of(metadata, rootfs);
    // Adopt BY OMISSION when the daemon already holds it (a prior run, or the retired CLI-import
    // era): reference the fingerprint, declare NO Image — re-uploading identical bytes is rejected
    // as
    // a duplicate. Self-healing against an out-of-graph image, and idempotent (an unchanged build
    // hashes to the same fingerprint), mirroring ensureNetwork's adopt-by-omission.
    if (importLookup.imageExists(fingerprint, config.incusProject())) {
      return Output.of(fingerprint);
    }
    // Absent → the provider uploads it, ordered AFTER the project. retainOnDelete so the next run's
    // adopt-by-omission (which no longer declares this resource) drops it from state WITHOUT
    // deleting
    // the daemon image the instance now runs on.
    final Image image =
        new Image(
            "seed-image-" + fingerprint,
            ImageArgs.builder()
                .project(config.incusProject())
                .sourceFile(
                    ImageSourceFileArgs.builder()
                        .metadataPath(view.metadataPath())
                        .dataPath(view.dataPath())
                        .build())
                .build(),
            CustomResourceOptions.builder()
                .provider(providerContext.provider())
                .retainOnDelete(true)
                .dependsOn(List.of(projectDependency))
                .build());
    return image.fingerprint();
  }

  private Instance createInstance(
      InstanceGrowPlan plan,
      Resource projectDependency,
      Output<String> imageFingerprint,
      NodeBootstrapMaterial material) {
    // The privileged-container config (raw.lxc, security.*, kernel_modules) is posed HERE, at
    // instance level, from the one definition both paths read — the same NodeRuntimeContract the
    // render publishes so CAPN poses it on LXCMachine.spec.config. It used to ride the `node-base`
    // profile.
    final Map<String, String> instanceConfig =
        new LinkedHashMap<>(NodeRuntimeContract.nodeBase().toIncusConfig());
    // The image-build checksum arms replaceOnChanges — a rebuilt node-base image (new fingerprint,
    // new checksum) recreates the instance onto it. A host-side trigger, never read by the guest.
    instanceConfig.put("user.rke2lab.imageBuildChecksum", plan.image().buildChecksum());
    // The per-node identity + revealed bootstrap material, delivered through the UNIFORM cloud-init
    // channel: ONE cloud-config write_files the node-base consumes at boot (node.env → hostname +
    // rke2 dual-stack/tls-san/node-labels drop-ins; sops age key + CA bundle →
    // sops-install-secrets;
    // server-manifests → rke2 server/manifests). The same channel CAPN/CAPRKE2 use for a workload
    // node — no devlxd user.rke2lab.* identity keys anymore.
    instanceConfig.put("cloud-init.user-data", mgmtCloudConfig(plan.identity(), material));

    // The image FINGERPRINT is the artifact's content hash (sha of metadata ++ rootfs). Fold it
    // into config so replaceOnChanges("config.*") recreates the instance EXACTLY when the built
    // image content changes — the source-digest buildChecksum above is a proxy that can miss a
    // rebuild, and `image` drift is deliberately ignored (adopt-by-omission), so without this the
    // instance stays on a stale image after a node-base rebuild. The fingerprint is an Output, so
    // fold the map through it.
    final Output<Map<String, String>> configWithFingerprint =
        imageFingerprint.applyValue(
            fingerprint -> {
              final Map<String, String> merged = new LinkedHashMap<>(instanceConfig);
              merged.put("user.rke2lab.imageFingerprint", fingerprint);
              return merged;
            });

    return new Instance(
        "seed-instance",
        InstanceArgs.builder()
            // nodeHostname = the FULL <cluster>-<node> (bioskop-mgmt-master), NOT nodeName (the
            // short
            // blueprint role `master`): it must (1) be globally unique in the shared `rke2lab`
            // incus
            // project (two clusters would otherwise both name their control node `master` →
            // collision),
            // and (2) match the k8s node name — the node sets its hostname DIRECTLY from
            // RKE2LAB_NODE_HOSTNAME = identity.nodeHostname() (nixos/identity.nix), so CAPN adopts
            // by
            // name: GetInstanceName() = the LXCMachine name = <cluster>-<node> = this instance
            // name.
            .name(plan.identity().nodeHostname())
            .project(config.incusProject())
            .image(imageFingerprint)
            .config(configWithFingerprint)
            .running(true)
            .devices(seedInstanceDevices(plan))
            .build(),
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .deleteBeforeReplace(true)
            // Replace on the IMAGE BUILD CHECKSUM only — a new node-base closure means a new node.
            // This used to watch all of `config`/`config.*`, far wider than that stated intent, and
            // the whole cloud-init rides in `config`: the node's github token is minted fresh every
            // run (Persistence.TRANSIENT by design), so every run saw `~config` and planned
            // `replace` + `delete original` — with no retainOnDelete, a kill-and-recreate of the
            // control-plane node on each up. Watching the one key that warrants it keeps the
            // re-provision trigger and drops the collateral. The rest of `config` still DIFFS; it
            // just no longer forces a replacement.
            .replaceOnChanges(List.of("config[\"user.rke2lab.imageBuildChecksum\"]"))
            // Ignore drift on `image` (the fingerprint is adopted, not managed) AND `devices`:
            // incus
            // stores devices as a MAP (keyed by name, unordered), but the provider models them
            // as
            // an
            // ORDERED List, so a refresh returns them in the daemon's order — never our
            // declared
            // order — and Pulumi reads the whole list as changed, replacing the instance every
            // run
            // (kill + recreate → the node never stays up long enough to become ready). No
            // declared
            // order can win against the daemon's map; the device SET is fixed and applied at
            // create,
            // so ignoring post-create drift is correct.
            .ignoreChanges(List.of("image", "devices"))
            .build());
  }

  /**
   * Render the mgmt node's {@code cloud-init.user-data}: a {@code write_files} cloud-config the
   * NixOS node-base consumes at boot. {@code node.env} carries the four per-node identity scalars
   * (the hostname + per-node rke2 drop-in oneshots — node-labels, provider-id — read it); the
   * revealed {@link NodeBootstrapMaterial} rides as the sops age key, the cluster-CA bundle, and
   * the rke2 server-manifests — each only when present (a producer that did not file leaves it out;
   * the guest units are tolerant). The per-cluster rke2 config (CIDRs, tls-san, node-ip, …) is NOT
   * here: it comes from the rendered {@code manifests/<cluster>} branch via {@code nix run
   * <branch>#install-rke2-config} at boot. Every file's content is base64 ({@code encoding: b64})
   * so arbitrary YAML/PEM never trips cloud-init's YAML indentation. This is the standalone twin of
   * a CAPRKE2 workload node's cloud-config — one channel.
   */
  private String mgmtCloudConfig(GrowIdentityView identity, NodeBootstrapMaterial material) {
    final String nodeEnv =
        String.join(
                "\n",
                // RKE2LAB_NODE_NAME keeps its node.env name (a nixos contract; the broader
                // nodeName→nodeRef terminology pass renames the env + its nixos consumers) —
                // sourced
                // from nodeRef (the short ordinal). The FULL name is RKE2LAB_NODE_HOSTNAME below.
                "RKE2LAB_NODE_NAME=" + identity.nodeRef(),
                "RKE2LAB_NODE_HOSTNAME=" + identity.nodeHostname(),
                "RKE2LAB_NODE_KIND=" + identity.nodeKind(),
                "RKE2LAB_NODE_ID=" + identity.nodeId())
            + "\n";
    final StringBuilder cloudConfig = new StringBuilder("#cloud-config\nwrite_files:\n");
    // DURABLE set → /var/lib/rke2lab (persists across reboots): node.env (identity/hostname read
    // EVERY boot), the sops age key + cluster-CA bundle (sops-nix reads them every boot). /run is
    // tmpfs and cloud-init does not re-run write_files on reboot, so these MUST NOT live there or a
    // reboot loses the hostname + breaks sops.
    appendWriteFile(cloudConfig, "/var/lib/rke2lab/node.env", "0644", nodeEnv);
    material
        .sopsAgeKey()
        .ifPresent(v -> appendWriteFile(cloudConfig, "/var/lib/rke2lab/sops-age.key", "0400", v));
    material
        .clusterCaBundle()
        .ifPresent(
            v ->
                appendWriteFile(cloudConfig, "/var/lib/rke2lab/cluster-ca-bundle.yaml", "0400", v));
    material
        .serverManifests()
        .ifPresent(
            v ->
                appendWriteFile(
                    cloudConfig,
                    "/var/lib/rancher/rke2/server/manifests/rke2lab-bootstrap.yaml",
                    "0600",
                    v));
    // The two inputs the rke2lab-rke2-config oneshot reads (nixos/rke2.nix): the manifests branch
    // to
    // fetch (non-secret; also GATES the oneshot via ConditionPathExists), and a nix.conf snippet
    // carrying the fresh github token as an `access-tokens` line (root-only). nix reads the latter
    // via
    // NIX_CONFIG="!include", so the token VALUE stays in the file, never in an env.
    material
        .manifestsBranchRef()
        .ifPresent(
            ref ->
                appendWriteFile(
                    cloudConfig,
                    "/run/rke2lab/rke2-config.env",
                    "0644",
                    "RKE2LAB_MANIFESTS_REF=" + ref + "\n"));
    material
        .githubAccessToken()
        .ifPresent(
            token ->
                appendWriteFile(
                    cloudConfig,
                    "/run/rke2lab/nix-github.conf",
                    "0400",
                    "access-tokens = github.com=" + token + "\n"));
    return cloudConfig.toString();
  }

  /** Append one base64-encoded {@code write_files} entry — single-line content, no indent traps. */
  private void appendWriteFile(
      StringBuilder cloudConfig, String path, String perms, String content) {
    final String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    cloudConfig
        .append("- path: ")
        .append(path)
        .append("\n  encoding: b64\n  permissions: '")
        .append(perms)
        .append("'\n  content: ")
        .append(b64)
        .append("\n");
  }

  /**
   * The post-instance beat: pose the stable {@code node-base} alias on the current image and GC the
   * leaked old node-base images. It runs a {@code local.Command} (the incus CLI is a runtimeInput
   * the provider already shells out to, authenticating through the same {@code configDir}) that
   * {@code dependsOn} the instance, so the engine schedules it AFTER the (re)created instance —
   * meaning the {@code deleteBeforeReplace} already tore down the old instance that cloned the old
   * image, which is now deletable (a live image cannot be deleted while an instance clones it,
   * which is why the old {@code Image} resource is {@code retainOnDelete}: Pulumi drops it from
   * state WITHOUT deleting the daemon image, and this beat reaps it once the clone is gone).
   *
   * <p>Retention is CURRENT-ONLY (no rollback): every project image but the freshly-grown
   * fingerprint is pruned. The alias is set delete-then-create (an idempotent upsert), safe now
   * that the GC guarantees no stale image lingers to collide on per-project alias uniqueness.
   * Best-effort throughout ({@code exit 0}): a GC or alias hiccup logs but never fails the grow —
   * the leak is slow and the alias is a convenience. {@code triggers} on the fingerprint re-runs it
   * on every renew (a new fingerprint), which is exactly when an old image starts leaking.
   */
  private void poseNodeBaseAliasAndGcImages(
      Output<String> imageFingerprint, Resource instanceDependency) {
    final String remote = config.incusDefaultRemote();
    final String project = config.incusProject();
    final Output<String> script =
        imageFingerprint.applyValue(
            fingerprint -> imageGcAndAliasScript(remote, project, fingerprint));

    final CommandArgs.Builder args =
        CommandArgs.builder()
            .create(script)
            .update(script)
            .triggers(imageFingerprint.applyValue(fingerprint -> List.<Object>of(fingerprint)));
    if (config.incusConfigDir() != null && !config.incusConfigDir().isBlank()) {
      args.environment(Map.of("INCUS_CONF", config.incusConfigDir()));
    }

    new Command(
        "seed-image-gc-alias",
        args.build(),
        CustomResourceOptions.builder().dependsOn(List.of(instanceDependency)).build());
  }

  /**
   * The sh script the {@code node-base} beat runs against the incus CLI: prune every project image
   * but {@code KEEP}, then upsert the {@code node-base} alias onto it. Fingerprints come from
   * {@code incus query} (the CLI's raw REST passthrough) so no jq/yq is needed — {@code tr}/{@code
   * sed} (coreutils) carve the 64-hex fingerprints out of the {@code /1.0/images/<fp>} array. The
   * config-derived {@code remote}/{@code project} are single-quoted (never user input).
   */
  private String imageGcAndAliasScript(String remote, String project, String fingerprint) {
    return String.join(
        "\n",
        "set -u",
        "REMOTE='" + remote + "'",
        "PROJECT='" + project + "'",
        "ALIAS='" + IncusImportLookup.NODE_BASE_ALIAS + "'",
        "KEEP='" + fingerprint + "'",
        "echo \"incus image gc: keeping $KEEP in project $PROJECT\"",
        "incus query \"$REMOTE:/1.0/images?project=$PROJECT\" | tr ',' '\\n' \\",
        "  | sed -n 's#.*/1.0/images/\\([0-9a-f]\\{64\\}\\).*#\\1#p' \\",
        "  | while IFS= read -r fp; do",
        "      if [ \"$fp\" != \"$KEEP\" ]; then",
        "        echo \"incus image gc: deleting $fp\"",
        "        incus image delete \"$REMOTE:$fp\" --project \"$PROJECT\" \\",
        "          || echo \"incus image gc: could not delete $fp (still referenced?)\"",
        "      fi",
        "    done",
        "echo \"incus image alias: node-base -> $KEEP\"",
        "incus image alias delete \"$REMOTE:$ALIAS\" --project \"$PROJECT\" 2>/dev/null || true",
        "incus image alias create \"$REMOTE:$ALIAS\" \"$KEEP\" --project \"$PROJECT\" \\",
        "  || echo \"incus image alias: create failed\"",
        "exit 0");
  }

  /**
   * The standalone node's 2 NICs, each with the blueprint's DETERMINISTIC hwaddr (so its reserved
   * dnsmasq lease resolves) — {@code fabric0} on the canonical fabric bridge, {@code vmnet0} on its
   * cluster's bridge. The kmsg/zfs unix-char devices + root disk ride the {@code node} profile now
   * (shared with CAPN); the NixOS {@code node-base} substrate bakes the node's config, so there are
   * no host disk mounts. CAPN nodes instead get DYNAMIC NICs from the {@code node-<cluster>}
   * profile.
   */
  private List<InstanceDeviceArgs> seedInstanceDevices(InstanceGrowPlan plan) {
    final GrowNetworkView network = plan.network();
    final Map<String, String> hwaddrByDevice =
        Map.of("fabric0", network.fabricHwaddr(), "vmnet0", network.wanHwaddr());
    final List<InstanceDeviceArgs> devices = new ArrayList<>();
    for (final NodeDeviceSet.Device device :
        NodeDeviceSet.forCluster(config.fabricBridgeParent(), network.nodeBridgeName()).devices()) {
      final Map<String, String> properties = new LinkedHashMap<>(device.properties());
      // The standalone master is the ONE node whose NICs carry the blueprint's DETERMINISTIC
      // hwaddr:
      // its dnsmasq reservation is what makes its name resolve. A CAPN-provisioned node takes a
      // dynamic MAC and its address from the bridge's ipv4.dhcp.ranges, so the published set leaves
      // hwaddr out and only this path adds it.
      final String hwaddr = hwaddrByDevice.get(device.name());
      if (hwaddr != null) {
        properties.put("hwaddr", hwaddr);
      }
      devices.add(
          InstanceDeviceArgs.builder()
              .name(device.name())
              .type(device.type())
              .properties(properties)
              .build());
    }
    return List.copyOf(devices);
  }
}
