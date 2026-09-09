package io.seedmatic.rke2lab.controlplane.incus;

import com.pulumi.command.local.Command;
import com.pulumi.command.local.CommandArgs;
import com.pulumi.core.Output;
import com.pulumi.incus.Image;
import com.pulumi.incus.ImageArgs;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Network;
import com.pulumi.incus.NetworkArgs;
import com.pulumi.incus.Profile;
import com.pulumi.incus.ProfileArgs;
import com.pulumi.incus.Project;
import com.pulumi.incus.ProjectArgs;
import com.pulumi.incus.inputs.ImageSourceFileArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProfileDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.seedmatic.rke2lab.incus.ingress.GrowIdentityView;
import io.seedmatic.rke2lab.incus.ingress.GrowImageView;
import io.seedmatic.rke2lab.incus.ingress.GrowNetworkView;
import io.seedmatic.rke2lab.incus.ingress.IngressConfig;
import io.seedmatic.rke2lab.incus.ingress.InstanceGrowPlan;
import io.seedmatic.rke2lab.incus.ingress.SplitImageFingerprint;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * fills — it names no seed-master type), the {@link IncusProviderContext} it builds once, the
 * {@link IncusImportLookup} riding that context's invoke options, and a log sink. Its one act is
 * {@link #grow(InstanceGrowPlan)}.
 */
public final class InstanceGrow {

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
    grow(plan, Map.of());
  }

  /**
   * Grow with EXTRA devlxd config keys merged onto the instance — the host GROW poses the cluster
   * PKI the seal scion filed (the sops-sealed CA bundle + the age identity, under {@code
   * user.rke2lab.cluster-ca-bundle} / {@code user.rke2lab.sops-age-key}) alongside the per-node
   * identity, all read by the guest over devlxd. The values are opaque to the GROW — the scenario
   * fetched + revealed them and hands them here as a flat {@code key -> value} map.
   */
  public void grow(InstanceGrowPlan plan, Map<String, String> extraDevlxdConfig) {
    final Project project = ensureProject();
    ensureNetwork(config.vmnetNetworkName(), project, plan.network());
    final Output<String> profileName = ensureProfile(project);
    final Output<String> imageFingerprint = ensureImage(plan.image(), project);
    final Instance instance =
        createInstance(plan, project, profileName, imageFingerprint, extraDevlxdConfig);
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
            // Per-project network namespacing so instance NIC parent references resolve under this
            // project, even though the actual bridges live in the default project (incus only
            // allows
            // OVN networks in non-default projects).
            .config(Map.of("features.networks", "true"))
            .build(),
        options.build());
  }

  /**
   * Ensure the per-cluster {@code vmnet} bridge from the plan's flat network view. Skips the
   * canonical host-provided LAN bridge and any bridge the provider reports UNMANAGED (a provider
   * invoke, not ssh). The vmnet network's config map is the plan's — the scion assembled it
   * OSGi-side from the netplan blueprint; the host only poses it.
   */
  private void ensureNetwork(String networkName, Resource projectDependency, GrowNetworkView view) {
    if (networkName.equals(config.lanBridgeParent())) {
      log.accept(
          "incus network ensure: skipping canonical host-provided bridge (" + networkName + ")");
      return;
    }
    // vmnet-br lives in the default project (only OVN networks are allowed in non-default
    // projects).
    final String networkProject = "default";
    if (importLookup.isUnmanagedNetwork(networkName, networkProject)) {
      log.accept("incus network ensure: skipping unmanaged bridge (" + networkName + ")");
      return;
    }
    // An already-existing vmnet bridge is adopted, not re-declared (its config is host-owned).
    if (importLookup.existingNetworkId(networkName, networkProject).isPresent()) {
      return;
    }

    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("project"))
            .build();

    new Network(
        "seed-network-" + networkName,
        NetworkArgs.builder()
            .name(networkName)
            .type("bridge")
            .project("default")
            .config(view.dnsmasqConfig())
            .build(),
        options);
  }

  private Output<String> ensureProfile(Resource projectDependency) {
    // A profile the host pre-created is ADOPTED BY OMISSION — referenced by name, never re-declared
    // (its devices/config are host-owned), mirroring the vmnet bridge. Importing it instead churns:
    // incus reads a profile's project back as null, and project is ForceNew, so an imported profile
    // is perpetually flagged for a replacement the import declaration then forbids. Only a virgin
    // host (no such profile) gets a fresh Pulumi-managed one.
    if (importLookup.existingProfileId(config.profileName(), config.incusProject()).isPresent()) {
      return Output.of(config.profileName());
    }

    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("name", "project", "devices", "config", "description"))
            .build();

    final Profile profile =
        new Profile(
            "seed-profile",
            ProfileArgs.builder()
                .name(config.profileName())
                .project(config.incusProject())
                .devices(
                    ProfileDeviceArgs.builder()
                        .name("root")
                        .type("disk")
                        .properties(Map.of("path", "/", "pool", "default"))
                        .build())
                .build(),
            options);

    return profile.name();
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
    // Content-addressed: incus derives a SPLIT image's fingerprint as sha256(metadata.tar.xz ++
    // rootfs.squashfs), metadata first (verified against the live daemon). Compute it host-side to
    // decide whether the daemon already holds this exact content before deciding to upload.
    final String fingerprint =
        SplitImageFingerprint.of(Path.of(view.metadataPath()), Path.of(view.dataPath()));
    // Adopt BY OMISSION when the daemon already holds it (a prior run, or the retired CLI-import
    // era): reference the fingerprint, declare NO Image — re-uploading identical bytes is rejected
    // as
    // a duplicate. Self-healing against an out-of-graph image, and idempotent (an unchanged build
    // hashes to the same fingerprint), mirroring ensureProfile/ensureNetwork's adopt-by-omission.
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
      Output<String> profileName,
      Output<String> imageFingerprint,
      Map<String, String> extraDevlxdConfig) {
    final Map<String, String> instanceConfig = new LinkedHashMap<>();
    instanceConfig.put(
        "raw.lxc",
        String.join(
            "\n",
            "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
            "lxc.apparmor.profile = unconfined",
            "lxc.cap.drop ="));
    instanceConfig.put("security.privileged", "true");
    instanceConfig.put("security.nesting", "true");
    instanceConfig.put("security.syscalls.intercept.bpf", "true");
    instanceConfig.put("security.syscalls.intercept.bpf.devices", "true");
    // The image-build checksum arms replaceOnChanges — a rebuilt node-base image (new fingerprint,
    // new checksum) recreates the instance onto it.
    instanceConfig.put("user.rke2lab.imageBuildChecksum", plan.image().buildChecksum());
    // The per-node identity the homogeneous node-base guest reads back over devlxd
    // (/dev/incus/sock) at boot: it resolves its hostname (mDNS <cluster>-<node>.local), its zfs
    // dataset (control-nodes/<node-name>/containerd) and its rke2 role from these four scalars. The
    // scion projected them from the netplan blueprint — the host only poses them.
    final GrowIdentityView identity = plan.identity();
    instanceConfig.put("user.rke2lab.node-name", identity.nodeName());
    instanceConfig.put("user.rke2lab.node-hostname", identity.nodeHostname());
    instanceConfig.put("user.rke2lab.node-kind", identity.nodeKind());
    instanceConfig.put("user.rke2lab.node-id", String.valueOf(identity.nodeId()));
    // The per-cluster dual-stack pod/service CIDRs — the homogeneous image cannot bake a static
    // cluster-cidr (it differs per cluster on the shared host), so the guest reads these back over
    // devlxd and writes rke2's 10-dualstack.yaml at boot (nixos/rke2.nix rke2lab-dualstack).
    instanceConfig.put("user.rke2lab.cluster-pod-cidr", identity.clusterPodCidr());
    instanceConfig.put("user.rke2lab.cluster-service-cidr", identity.clusterServiceCidr());
    // The host GROW poses whatever extra devlxd keys the caller resolved — the cluster PKI the seal
    // scion filed (the sops CA bundle + the age identity). Opaque here: the scenario fetched them.
    instanceConfig.putAll(extraDevlxdConfig);

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
            .name(config.nodeName())
            .project(config.incusProject())
            .image(imageFingerprint)
            .profiles(profileName.applyValue(List::of))
            .config(configWithFingerprint)
            .running(true)
            .devices(seedInstanceDevices(plan))
            .build(),
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .deleteBeforeReplace(true)
            .replaceOnChanges(List.of("config", "config.*"))
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
   * The 4 instance devices — 2 NICs (hwaddrs from the plan's network view) and 2 unix-char (fixed:
   * {@code /dev/kmsg}, {@code /dev/zfs} for the in-guest zfs snapshotter mount). The NixOS {@code
   * node-base} substrate bakes the node's config, so there are NO host disk mounts: the former
   * {@code /srv/host} delivery is dissolved.
   */
  private List<InstanceDeviceArgs> seedInstanceDevices(InstanceGrowPlan plan) {
    final GrowNetworkView network = plan.network();
    final List<InstanceDeviceArgs> devices = new ArrayList<>();
    devices.add(nic("lan0", network.lanHwaddr(), "lan0", "bridged", config.lanBridgeParent()));
    devices.add(nic("vmnet0", network.wanHwaddr(), "vmnet0", "bridged", config.vmnetNetworkName()));
    devices.add(unixChar("kmsg.dev", "/dev/kmsg", "/dev/kmsg"));
    devices.add(unixChar("zfs.dev", "/dev/zfs", "/dev/zfs"));
    return List.copyOf(devices);
  }

  private InstanceDeviceArgs nic(
      String name, String hwaddr, String ifName, String nictype, String parent) {
    return device(
        name,
        "nic",
        Map.of("hwaddr", hwaddr, "name", ifName, "nictype", nictype, "parent", parent));
  }

  private InstanceDeviceArgs unixChar(String name, String source, String path) {
    return device(name, "unix-char", Map.of("source", source, "path", path));
  }

  private InstanceDeviceArgs device(String name, String type, Map<String, String> properties) {
    return InstanceDeviceArgs.builder().name(name).type(type).properties(properties).build();
  }
}
