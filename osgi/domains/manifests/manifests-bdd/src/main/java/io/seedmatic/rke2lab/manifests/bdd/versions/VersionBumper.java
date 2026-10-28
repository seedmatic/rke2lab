package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.ingress.ComponentSource;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.codec.WireCodec;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>{@link #apply} resolves its targets under a {@code worktreeRoot} (the {@code DurableWorktree}
 * the scion injects), rewrites the {@code "<slug>", "<pin>"} literal of the matching {@link
 * Component} constant in {@code Component.java} in place, refreshes the vendored {@code
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

  /** The whole apply: the per-component outcomes AND the edit the scion commits. */
  public record BumpApplication(List<AppliedBump> bumps, TreeEdit.Overlay edit) {

    public boolean anyChanged() {
      return !edit.isEmpty();
    }
  }

  private final BumpLevel level;
  private final Optional<String> githubToken;
  private final HttpClient http;
  private final WireCodec wire;
  private final UpstreamVersions upstreamVersions = new UpstreamVersions();

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
   * Compute the bump against {@code worktreeRoot}: the pin literal rewritten in {@code
   * Component.java} and the vendored assets refreshed under {@code manifests-core}, as an edit —
   * nothing is written here; the worktree applies the edit when it commits. When {@code
   * onlyComponent} is present, only that component is touched; otherwise every source-bearing
   * component is. Returns the per-component outcomes and the edit.
   */
  public BumpApplication apply(final Optional<Component> onlyComponent, final Path worktreeRoot) {
    final Path componentSource = worktreeRoot.resolve(COMPONENT_SOURCE).normalize();
    final Path resourcesRoot = worktreeRoot.resolve(CORE_RESOURCES).normalize();
    final List<AppliedBump> bumps = new ArrayList<>();
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final LinkedHashSet<String> removed = new LinkedHashSet<>();
    boolean sourceChanged = false;

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
        bumps.add(unchanged(component, oldPin, "no upstream source"));
        continue;
      }
      final Optional<SemanticVersion> current = SemanticVersion.parse(oldPin);
      if (current.isEmpty()) {
        bumps.add(unchanged(component, oldPin, "current pin not semver"));
        continue;
      }
      final Optional<Release> target;
      try {
        target = highestAllowed(fetchStable(source.get().upstream()), current);
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
          refreshVendoredAsset(
              source.get(),
              target.get().tag(),
              oldPin,
              newPin,
              resourcesRoot.resolve(source.get().vendoredResourceDir().orElseThrow()),
              worktreeRoot,
              files,
              removed);
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
      sourceChanged = true;
      bumps.add(new AppliedBump(component, oldPin, Optional.of(newPin), assetRefreshed, ""));
    }

    if (sourceChanged) {
      files.put(
          relative(worktreeRoot, componentSource), sourceText.getBytes(StandardCharsets.UTF_8));
    }
    return new BumpApplication(
        List.copyOf(bumps), new TreeEdit.Overlay(files, List.copyOf(removed)));
  }

  private static AppliedBump unchanged(
      final Component component, final String pin, final String why) {
    return new AppliedBump(component, pin, Optional.empty(), false, why);
  }

  private VersionReport reportFor(final Component component) {
    final String pin = component.defaultVersion();
    final Optional<ComponentSource> source = component.source();
    if (source.isEmpty()) {
      return VersionReport.manual(component, pin, "no upstream source");
    }
    try {
      final List<Release> releases = fetchStable(source.get().upstream());
      final Optional<Release> newest =
          releases.stream().max(Comparator.comparing(Release::version));
      final Optional<SemanticVersion> latest = newest.map(Release::version);
      final Optional<String> latestRaw = newest.map(Release::tag);
      final Optional<SemanticVersion> current = SemanticVersion.parse(pin);
      if (latest.isEmpty()) {
        return VersionReport.manual(component, pin, "no stable release found upstream");
      }
      if (current.isEmpty()) {
        return new VersionReport(
            component, pin, latest, Optional.empty(), "current pin not semver", latestRaw);
      }
      final Optional<SemanticVersion> allowed =
          highestAllowed(releases, current).map(Release::version);
      return new VersionReport(component, pin, latest, allowed, "", latestRaw);
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

  /** The stable releases an upstream publishes, whatever its kind. */
  private List<Release> fetchStable(final ComponentSource.Upstream upstream)
      throws IOException, InterruptedException {
    return switch (upstream) {
      case ComponentSource.Upstream.GitHub github -> fetchStableReleases(github.repo());
      case ComponentSource.Upstream.HelmIndex helm ->
          releases(
              upstreamVersions.helmIndex(
                  getText(stripSlash(helm.repoUrl()) + "/index.yaml"), helm.chart()));
      case ComponentSource.Upstream.Oci oci ->
          releases(upstreamVersions.ociTags(getOciTags(oci.registry(), oci.repository())));
    };
  }

  private static List<Release> releases(final List<String> tags) {
    final List<Release> releases = new ArrayList<>();
    for (final String tag : tags) {
      SemanticVersion.parse(tag).ifPresent(version -> releases.add(new Release(version, tag)));
    }
    return releases;
  }

  private static String stripSlash(final String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * An OCI registry's tags list, with the anonymous pull token the registry hands out (ghcr's
   * {@code /token?scope=repository:<repo>:pull}): no account involved.
   */
  private String getOciTags(final String registry, final String repository)
      throws IOException, InterruptedException {
    final String token =
        wire.readJson(
                getText("https://" + registry + "/token?scope=repository:" + repository + ":pull"))
            .path("token")
            .asText();
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://" + registry + "/v2/" + repository + "/tags/list"))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
    }
    return response.body();
  }

  /** A plain GET, no GitHub headers nor token: the Helm and OCI hosts are not GitHub's API. */
  private String getText(final String url) throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
    final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " from " + url);
    }
    return response.body();
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
   * Fetch the release asset for {@code tag} into the edit as {@code release-<newPin>.yaml} under
   * the component's vendored {@code dir}, and mark the {@code release-<oldPin>.yaml} it replaces as
   * removed. A failed fetch leaves the edit untouched.
   */
  private void refreshVendoredAsset(
      final ComponentSource source,
      final String tag,
      final String oldPin,
      final String newPin,
      final Path dir,
      final Path worktreeRoot,
      final Map<String, byte[]> files,
      final Set<String> removed)
      throws IOException, InterruptedException {
    // A vendored asset is a GitHub release asset: no other upstream kind carries one.
    if (!(source.upstream() instanceof ComponentSource.Upstream.GitHub github)) {
      throw new IOException("a vendored asset needs a GitHub upstream, not " + source.upstream());
    }
    final byte[] asset =
        fetchReleaseAsset(github.repo(), tag, source.releaseAssetName().orElseThrow());
    final Path newFile = dir.resolve("release-" + newPin + ".yaml");
    files.put(relative(worktreeRoot, newFile), asset);
    final Path oldFile = dir.resolve("release-" + oldPin + ".yaml");
    if (!oldFile.equals(newFile)) {
      removed.add(relative(worktreeRoot, oldFile));
    }
  }

  private static String relative(final Path worktreeRoot, final Path file) {
    return worktreeRoot.relativize(file).toString().replace('\\', '/');
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
