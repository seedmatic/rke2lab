package io.seedmatic.rke2lab.controlplane.incus;

import com.pulumi.deployment.Deployment;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.inputs.GetImagePlainArgs;
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProfilePlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The bridge of first contact between the NixOS-prepopulated Incus host and a virgin Pulumi state:
 * a PROVIDER invoke ({@code getProjectPlain}/{@code getNetworkPlain} through the run's {@link
 * InvokeOptions}, NOT ssh) that discovers a pre-existing project/network so the GROW ADOPTS it via
 * {@code importId} instead of trying to create a duplicate. Host-pure — it computes nothing of the
 * domain, it only reads what the Incus host already reports.
 *
 * <p>Instance-passing: it holds the {@link IncusProviderContext} whose {@code invokeOptions} every
 * lookup rides, and a {@code log} sink; a caller constructs one and asks it for import ids. The
 * former nested-static {@code INSTANCE} + {@code BootstrapContext} coupling is gone.
 */
public final class IncusImportLookup {

  private static final long PREVIEW_INVOKE_TIMEOUT_SECONDS = 10;
  private static final long APPLY_INVOKE_TIMEOUT_SECONDS = 20;

  /**
   * The stable image alias the GROW poses on the current node-base image after every instance (see
   * {@code InstanceGrow.poseNodeBaseAliasAndGcImages}) — the reliable key for {@link #imageExists}:
   * an alias lookup does NOT trip the provider's fingerprint-query SIGSEGV.
   */
  public static final String NODE_BASE_ALIAS = "node-base";

  private enum LookupState {
    FOUND,
    NOT_FOUND,
    FAILED
  }

  private record LookupResult(
      Optional<String> importId, LookupState state, Optional<Boolean> managed) {

    private static LookupResult found(Optional<String> importId, @Nullable Boolean managed) {
      return new LookupResult(importId, LookupState.FOUND, Optional.ofNullable(managed));
    }

    private static LookupResult notFound() {
      return new LookupResult(Optional.empty(), LookupState.NOT_FOUND, Optional.empty());
    }

    private static LookupResult failed() {
      return new LookupResult(Optional.empty(), LookupState.FAILED, Optional.empty());
    }
  }

  private final IncusProviderContext context;
  private final Consumer<String> log;

  public IncusImportLookup(IncusProviderContext context, Consumer<String> log) {
    this.context = context;
    this.log = log;
  }

  /** The import id to adopt an existing project, or empty when none is found. */
  public Optional<String> existingProjectId(String projectName) {
    log.accept("incus lookup getProject: start name=" + projectName);
    try {
      final var project =
          IncusFunctions.getProjectPlain(
                  GetProjectPlainArgs.builder().name(projectName).build(), context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      if (project == null) {
        return Optional.empty();
      }
      return normalizeImportId(project.id()).or(() -> normalizeImportId(project.name()));
    } catch (Exception ex) {
      log.accept("incus lookup getProject: failed (" + summarizeLookupFailure(ex) + ")");
      return Optional.empty();
    }
  }

  /** The import id to adopt an existing (project-scoped) profile, or empty when none found. */
  public Optional<String> existingProfileId(String profileName, String incusProject) {
    log.accept("incus lookup getProfile: start name=" + profileName + " project=" + incusProject);
    try {
      final var profile =
          IncusFunctions.getProfilePlain(
                  GetProfilePlainArgs.builder().name(profileName).project(incusProject).build(),
                  context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      if (profile == null) {
        return Optional.empty();
      }
      return normalizeImportId(profile.id()).or(() -> normalizeImportId(profile.name()));
    } catch (Exception ex) {
      log.accept("incus lookup getProfile: failed (" + summarizeLookupFailure(ex) + ")");
      return Optional.empty();
    }
  }

  /**
   * Whether the daemon already holds this content {@code fingerprint} in the project. The GROW then
   * references it (adopt by omission) instead of re-declaring an {@code Image} that would re-upload
   * identical bytes and be rejected as a duplicate. Content-addressed: an unchanged build resolves
   * to the same fingerprint (present → adopt), a changed build to a new one (absent → upload).
   *
   * <p>Resolved through the stable {@link #NODE_BASE_ALIAS} the GROW poses after every instance —
   * NOT by a {@code fingerprint} query, which SIGSEGVs terraform-provider-incus v1.1.1 (nil deref,
   * datasource_image.go:207 — the panic kills the plugin and poisons every later RPC as
   * "ValidateResourceConfig ... connection refused"). An alias lookup does not trip it, so the
   * daemon holds THIS exact content iff the alias resolves to our fingerprint. A rebuild leaves the
   * alias on the OLD image (mismatch → absent → the GROW uploads the new content, then the
   * post-instance beat moves the alias); a virgin/not-yet-aliased daemon also returns absent → the
   * create path, both correct. A miss throws, caught as absent.
   */
  public boolean imageExists(String fingerprint, String incusProject) {
    log.accept(
        "incus lookup getImage: start alias="
            + NODE_BASE_ALIAS
            + " fingerprint="
            + fingerprint
            + " project="
            + incusProject);
    try {
      final var image =
          IncusFunctions.getImagePlain(
                  GetImagePlainArgs.builder().name(NODE_BASE_ALIAS).project(incusProject).build(),
                  context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      return image != null && fingerprint.equals(image.fingerprint());
    } catch (Exception ex) {
      log.accept("incus lookup getImage: absent (" + summarizeLookupFailure(ex) + ")");
      return false;
    }
  }

  /** The import id to adopt an existing network, or empty when none is found. */
  public Optional<String> existingNetworkId(String networkName, String incusProject) {
    final LookupResult projectScoped =
        resolveNetworkImportId(
            GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
    if (projectScoped.state() == LookupState.FOUND) {
      return projectScoped.importId();
    }
    if (projectScoped.state() == LookupState.FAILED) {
      final Optional<String> fallbackImportId = normalizeImportId(networkName);
      log.accept(
          "incus lookup getNetwork: deterministic fallback import id after scoped lookup failure"
              + " (name="
              + networkName
              + ", fallbackImportId="
              + fallbackImportId.orElse("")
              + ")");
      return fallbackImportId;
    }
    return resolveNetworkImportId(GetNetworkPlainArgs.builder().name(networkName).build())
        .importId();
  }

  /** Whether the Incus host reports this network as UNMANAGED — the GROW must skip it. */
  public boolean isUnmanagedNetwork(String networkName, String incusProject) {
    final LookupResult projectScoped =
        resolveNetworkImportId(
            GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
    if (projectScoped.state() == LookupState.FOUND) {
      return projectScoped.managed().map(Boolean.FALSE::equals).orElse(false);
    }
    if (projectScoped.state() == LookupState.FAILED) {
      return false;
    }
    final LookupResult unscoped =
        resolveNetworkImportId(GetNetworkPlainArgs.builder().name(networkName).build());
    if (unscoped.state() == LookupState.FOUND) {
      return unscoped.managed().map(Boolean.FALSE::equals).orElse(false);
    }
    return false;
  }

  private Optional<String> normalizeImportId(@Nullable String value) {
    if (value == null) {
      return Optional.empty();
    }
    final String trimmed = value.trim();
    return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
  }

  private long invokeTimeoutSeconds() {
    try {
      return Deployment.getInstance().isDryRun()
          ? PREVIEW_INVOKE_TIMEOUT_SECONDS
          : APPLY_INVOKE_TIMEOUT_SECONDS;
    } catch (Exception ignored) {
      return APPLY_INVOKE_TIMEOUT_SECONDS;
    }
  }

  private LookupResult resolveNetworkImportId(GetNetworkPlainArgs args) {
    log.accept("incus lookup getNetwork: start name=" + args.name());
    try {
      final var network =
          IncusFunctions.getNetworkPlain(args, context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      if (network == null) {
        return LookupResult.notFound();
      }
      final Boolean managed = network.managed();
      return LookupResult.found(
          normalizeImportId(network.id()).or(() -> normalizeImportId(network.name())), managed);
    } catch (Exception ex) {
      final String summary = summarizeLookupFailure(ex);
      if (isNotFoundFailure(summary)) {
        return LookupResult.notFound();
      }
      log.accept("incus lookup getNetwork: failed (" + summary + ")");
      return LookupResult.failed();
    }
  }

  private boolean isNotFoundFailure(String summary) {
    return summary != null && summary.toLowerCase(Locale.ROOT).contains("not found");
  }

  private String summarizeLookupFailure(Exception ex) {
    if (ex == null) {
      return "unknown";
    }
    Throwable root = ex;
    while (root.getCause() != null
        && (root instanceof CompletionException || root instanceof ExecutionException)) {
      root = root.getCause();
    }
    final String type = root.getClass().getSimpleName();
    final String message = root.getMessage() == null ? "" : root.getMessage().trim();
    return message.isBlank() ? type : type + ": " + message;
  }
}
