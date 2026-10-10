package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.ingress.ComponentSource;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.codec.WireCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code manifests versions} bump engine, run OSGi-side inside {@code VersionBumpScenario}. The
 * single source of truth for what a component IS is the {@link Component} enum ({@link
 * Component#values()}, {@link Component#defaultVersion()}, {@link Component#source()}); this engine
 * diffs each component's pin against its upstream GitHub releases and {@link #report() reports} or
 * {@link #apply applies} the highest bump reachable within the authorised {@link BumpLevel}.
 *
 * <p>The level is a CEILING, not a requirement: report/apply pick the HIGHEST release the level
 * admits, so {@code major} takes the newest release outright ("degrading" to a minor/micro when
 * that is all that is newer), {@code minor} stays within the current major, {@code micro} within
 * the current major.minor.
 *
 * <p>{@link #apply} resolves its targets under a {@code worktreeRoot} (the {@code Worktree} the
 * scion injects), rewrites the {@code "<slug>", "<pin>"} literal of the matching {@link Component}
 * constant in {@code Component.java} in place, refreshes the vendored {@code
 * release-<version>.yaml} for the manifest-vendoring components, and RETURNS the paths it touched
 * so the scion stages exactly them (asset FIRST, so a failed fetch never leaves a pin pointing at a
 * missing asset).
 */
public final class VersionBumper {

  /** The {@link Component} enum source, relative to the worktree root — where the pins live. */
  private static final String COMPONENT_SOURCE =
      "osgi/domains/manifests/manifests-ingress-contract/src/main/java/io/seedmatic/rke2lab/"
          + "manifests/ingress/Component.java";

  private static final String CORE_RESOURCES =
      "osgi/domains/manifests/manifests-core/src/main/resources";

  /** An upstream release: the parsed semver AND the raw tag (needed verbatim for asset URLs). */
  public record Release(SemanticVersion version, String tag) {}

  /**
   * One component's apply outcome — the narration a scion step binds into the runbook. {@code
   * newPin} is present only when the pin was actually changed; {@code note} carries the reason it
   * was left unchanged otherwise.
   */
  public record AppliedBump(
      Component component,
      String oldPin,
      Optional<String> newPin,
      boolean assetRefreshed,
      String note) {}

  /** The whole apply: the per-component outcomes AND the deduped paths the scion must stage. */
  public record BumpApplication(List<AppliedBump> bumps, List<Path> changedPaths) {

    public boolean anyChanged() {
      return !changedPaths.isEmpty();
    }
  }

  private final BumpLevel level;
  private final Optional<String> githubToken;
  private final HttpClient http;
  private final WireCodec wire;

  public VersionBumper(final BumpLevel level, final Optional<String> githubToken) {
    this.level = level;
    this.githubToken = githubToken;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.wire = new SeedCodec().wire();
  }

  public BumpLevel level() {
    return level;
  }

  /** One {@link VersionReport} per component, in {@link Component} declaration order. */
  public List<VersionReport> report() {
    final List<VersionReport> rows = new ArrayList<>();
    for (final Component component : Component.values()) {
      rows.add(reportFor(component));
    }
    return rows;
  }

  /**
   * Apply the bump in place under {@code worktreeRoot}: rewrite the pin literal in {@code
   * Component.java} and refresh the vendored assets under {@code manifests-core}. When {@code
   * onlyComponent} is present, only that component is touched; otherwise every source-bearing
   * component is. Returns the per-component outcomes and the deduped set of paths changed.
   */
  public BumpApplication apply(final Optional<Component> onlyComponent, final Path worktreeRoot) {
    final Path componentSource = worktreeRoot.resolve(COMPONENT_SOURCE).normalize();
    final Path resourcesRoot = worktreeRoot.resolve(CORE_RESOURCES).normalize();
    final List<AppliedBump> bumps = new ArrayList<>();
    final LinkedHashSet<Path> changed = new LinkedHashSet<>();

    String sourceText;
    try {
      sourceText = Files.readString(componentSource);
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read " + componentSource, failed);
    }

    for (final Component component : Component.values()) {
      if (onlyComponent.isPresent() && onlyComponent.get() != component) {
        continue;
      }
      final String oldPin = component.defaultVersion();
      final Optional<ComponentSource> source = component.source();
      if (source.isEmpty()) {
        bumps.add(unchanged(component, oldPin, "non-GitHub source — bump manually"));
        continue;
      }
      final Optional<SemanticVersion> current = SemanticVersion.parse(oldPin);
      if (current.isEmpty()) {
        bumps.add(unchanged(component, oldPin, "current pin not semver"));
        continue;
      }
      final Optional<Release> target;
      try {
        target = highestAllowed(fetchStableReleases(source.get().githubRepo()), current);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        bumps.add(unchanged(component, oldPin, "interrupted"));
        continue;
      } catch (final IOException unreachable) {
        bumps.add(unchanged(component, oldPin, "unreachable: " + unreachable.getMessage()));
        continue;
      }
      if (target.isEmpty()) {
        bumps.add(
            unchanged(component, oldPin, "already current under the " + level.slug() + " gate"));
        continue;
      }

      final String newPin = withPinStyle(oldPin, target.get().version());
      // The pin literal is {@code "<slug>", "<oldPin>"} — but the vendored (5-arg) constants are
      // wrapped across lines by the formatter, so tolerate ANY whitespace between the two arguments
      // ($1 captures {@code "<slug>",<ws>"} verbatim, $2 the closing quote — the layout is kept).
      final Matcher pin =
          Pattern.compile(
                  "(\""
                      + Pattern.quote(component.slug())
                      + "\",\\s*\")"
                      + Pattern.quote(oldPin)
                      + "(\")")
              .matcher(sourceText);
      if (!pin.find()) {
        bumps.add(
            unchanged(
                component,
                oldPin,
                "pin literal for " + component.slug() + " not found — NOT changed"));
        continue;
      }
      // Needle confirmed BEFORE any asset work, so a miss never leaves an orphan refreshed asset
      // (the pin and its vendored release-<pin>.yaml stay in lock-step).
      boolean assetRefreshed = false;
      if (source.get().hasUpstreamYaml()) {
        try {
          changed.addAll(
              refreshVendoredAsset(
                  source.get(), target.get().tag(), oldPin, newPin, resourcesRoot));
          assetRefreshed = true;
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          bumps.add(unchanged(component, oldPin, "pin NOT changed — asset refresh interrupted"));
          continue;
        } catch (final IOException failed) {
          bumps.add(
              unchanged(
                  component,
                  oldPin,
                  "pin NOT changed — asset refresh failed: " + failed.getMessage()));
          continue;
        }
      }
      sourceText = pin.replaceFirst("$1" + Matcher.quoteReplacement(newPin) + "$2");
      changed.add(componentSource);
      bumps.add(new AppliedBump(component, oldPin, Optional.of(newPin), assetRefreshed, ""));
    }

    try {
      Files.writeString(componentSource, sourceText);
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot write " + componentSource, failed);
    }
    return new BumpApplication(List.copyOf(bumps), List.copyOf(changed));
  }

  private static AppliedBump unchanged(
      final Component component, final String pin, final String why) {
    return new AppliedBump(component, pin, Optional.empty(), false, why);
  }

  private VersionReport reportFor(final Component component) {
    final String pin = component.defaultVersion();
    final Optional<ComponentSource> source = component.source();
    if (source.isEmpty()) {
      return VersionReport.manual(
          component, pin, "non-GitHub source (chart / container tag) — bump manually");
    }
    try {
      final List<Release> releases = fetchStableReleases(source.get().githubRepo());
      final Optional<SemanticVersion> latest =
          releases.stream().map(Release::version).max(Comparator.naturalOrder());
      final Optional<SemanticVersion> current = SemanticVersion.parse(pin);
      if (latest.isEmpty()) {
        return VersionReport.manual(component, pin, "no stable release found upstream");
      }
      if (current.isEmpty()) {
        return new VersionReport(
            component, pin, latest, Optional.empty(), "current pin not semver");
      }
      final Optional<SemanticVersion> allowed =
          highestAllowed(releases, current).map(Release::version);
      return new VersionReport(component, pin, latest, allowed, "");
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return VersionReport.manual(component, pin, "interrupted");
    } catch (final IOException failed) {
      return VersionReport.manual(component, pin, "unreachable: " + failed.getMessage());
    }
  }

  /** The highest release the level gate admits above {@code current}, empty if none. */
  private Optional<Release> highestAllowed(
      final List<Release> releases, final Optional<SemanticVersion> current) {
    if (current.isEmpty()) {
      return Optional.empty();
    }
    return releases.stream()
        .filter(release -> current.get().allows(release.version(), level))
        .max(Comparator.comparing(Release::version));
  }

  private List<Release> fetchStableReleases(final String repo)
      throws IOException, InterruptedException {
    final JsonNode releases =
        getJson("https://api.github.com/repos/" + repo + "/releases?per_page=100");
    final List<Release> stable = new ArrayList<>();
    for (final JsonNode release : releases) {
      if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
        continue;
      }
      final String tag = release.path("tag_name").asText(null);
      SemanticVersion.parse(tag).ifPresent(version -> stable.add(new Release(version, tag)));
    }
    return stable;
  }

  /**
   * Fetch the release asset for {@code tag} and drop it as {@code release-<newPin>.yaml} under the
   * component's vendored dir, then remove the {@code release-<oldPin>.yaml} it replaces. Returns
   * the paths touched (the new file, and the old one when it differs). Fetch + write happen before
   * the old file is removed, so a failure leaves the working tree intact.
   */
  private List<Path> refreshVendoredAsset(
      final ComponentSource source,
      final String tag,
      final String oldPin,
      final String newPin,
      final Path resourcesRoot)
      throws IOException, InterruptedException {
    final byte[] asset =
        fetchReleaseAsset(source.githubRepo(), tag, source.releaseAssetName().orElseThrow());
    final Path dir = resourcesRoot.resolve(source.vendoredResourceDir().orElseThrow());
    Files.createDirectories(dir);
    final Path newFile = dir.resolve("release-" + newPin + ".yaml");
    Files.write(newFile, asset);
    final Path oldFile = dir.resolve("release-" + oldPin + ".yaml");
    final List<Path> touched = new ArrayList<>();
    touched.add(newFile);
    if (!oldFile.equals(newFile)) {
      Files.deleteIfExists(oldFile);
      touched.add(oldFile);
    }
    return touched;
  }

  /** The bytes of {@code assetName} on the {@code tag} release of {@code repo}. */
  private byte[] fetchReleaseAsset(final String repo, final String tag, final String assetName)
      throws IOException, InterruptedException {
    final JsonNode release =
        getJson("https://api.github.com/repos/" + repo + "/releases/tags/" + tag);
    for (final JsonNode asset : release.path("assets")) {
      if (assetName.equals(asset.path("name").asText(null))) {
        final String url = asset.path("browser_download_url").asText(null);
        if (url == null) {
          throw new IOException(
              "asset " + assetName + " has no download url on " + repo + "@" + tag);
        }
        return getBytes(url);
      }
    }
    throw new IOException("asset " + assetName + " not found on " + repo + "@" + tag);
  }

  private JsonNode getJson(final String url) throws IOException, InterruptedException {
    final HttpResponse<String> response =
        send(url, "application/vnd.github+json", HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + url);
    }
    return wire.readJson(response.body());
  }

  private byte[] getBytes(final String url) throws IOException, InterruptedException {
    final HttpResponse<byte[]> response =
        send(url, "application/octet-stream", HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + url);
    }
    return response.body();
  }

  private <T> HttpResponse<T> send(
      final String url, final String accept, final HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(30))
            .GET();
    githubToken.ifPresent(token -> request.header("Authorization", "Bearer " + token));
    return http.send(request.build(), handler);
  }

  /**
   * Keep the pin's leading-{@code v} convention ({@code capiCore} uses {@code v1.9.4}; {@code
   * tailscale} {@code 1.82.0}).
   */
  private static String withPinStyle(final String oldPin, final SemanticVersion version) {
    return (oldPin.startsWith("v") ? "v" : "") + version;
  }
}
