package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.manifests.ingress.PacWebhookFunnel;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code ghapp} operator CLI — the standalone driver of the one org-owned GitHub App's
 * declaration ceremony, run as a subcommand of {@link io.seedmatic.rke2lab.controlplane.Main}
 * BEFORE the Pulumi envelope. It lives OUTSIDE the grow on purpose: {@code seed-master} runs under
 * {@code pulumi up}, whose gRPC engine captures the console and makes a mid-run browser/prompt
 * unusable, so the whole human ceremony (which GitHub gates behind a browser) is driven here on the
 * real console instead. The in-container {@code GithubAppScenario} only rehydrates what this CLI
 * has sealed into {@code .secrets}.
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code ghapp create} — open GitHub's PRE-FILLED "New GitHub App" form (a top-level GET that
 *       carries the operator's session; a loopback manifest POST cannot, by GitHub's {@code
 *       SameSite} hardening). The form carries the webhook already ACTIVE with its URL + event
 *       subscriptions filled, so the operator's only irreducible clicks are Create + Generate a
 *       private key (PEM → {@code ~/Downloads}); the webhook SECRET (the one field GitHub forbids
 *       as a URL param) is set later by the grow's webhook scion, AS the App — never on the UI.
 *   <li>{@code ghapp install} — open the App's install page; the operator installs it on the org.
 *   <li>{@code ghapp seed <appId> [pemPath]} — forge the App JWT from the PEM, resolve the
 *       installation id via {@code GET /app/installations}, and write the {@code githubApp} block
 *       into {@code .secrets} ({@code privateKeyPem} marked {@code # sops:encrypted}). {@code
 *       pemPath} defaults to the newest {@code *.pem} in {@code ~/Downloads}.
 * </ul>
 */
public final class GithubAppCli {

  private static final String ORG = "seedmatic";
  private static final String APP_NAME = "seedmatic-rke2lab";
  private static final URI API = URI.create("https://api.github.com");
  private static final String SECRETS_KEY = "githubApp";
  private static final String API_VERSION = "2022-11-28";

  /**
   * GitHub's PRE-FILLED "New GitHub App" form for the {@code seedmatic} org — a top-level GET so
   * the operator's {@code SameSite} session cookie rides along and they land authenticated, with
   * the App's shape ALREADY filled: name, homepage, permissions, AND the webhook (active + URL +
   * event subscriptions). Pre-filling the webhook is what removes the operator's manual GitHub-UI
   * steps — the events cannot be set through any API (only this form or the UI), and the webhook
   * URL/secret are otherwise hand-entered. The one field GitHub forbids as a URL param is the
   * webhook SECRET; the grow's ghapp webhook scion sets it (and re-points the URL on rename) AS the
   * App, so the operator never touches the webhook page. Webhook URL = the PaC funnel FQDN, built
   * from the shared {@link PacWebhookFunnel} leaf + the default tailnet (the same endpoint the grow
   * reconciles to). Events = the Pipelines-as-Code set (check_run/check_suite/commit_comment/
   * issue_comment/pull_request/push); {@code events[]} is percent-encoded so {@link URI#create}
   * accepts the brackets.
   */
  private static final URI REGISTRATION_URL =
      URI.create(
          "https://github.com/organizations/seedmatic/settings/apps/new"
              + "?name=seedmatic-rke2lab"
              + "&url=https://github.com/seedmatic/rke2lab"
              + "&webhook_active=true"
              + "&webhook_url="
              + new PacWebhookFunnel(BootstrapConfig.DEFAULT_TAILNET).url()
              + "&events%5B%5D=check_run"
              + "&events%5B%5D=check_suite"
              + "&events%5B%5D=commit_comment"
              + "&events%5B%5D=issue_comment"
              + "&events%5B%5D=pull_request"
              + "&events%5B%5D=push"
              + "&contents=write&statuses=write&pull_requests=write&checks=write&metadata=read"
              // administration:write — repo webhook management (per-cluster PaC/Tekton webhooks,
              // POST/PATCH /repos/{repo}/hooks), reconciled by the ghapp repo-webhook scion.
              + "&administration=write");

  private static final URI INSTALL_URL =
      URI.create(
          "https://github.com/organizations/seedmatic/settings/apps/seedmatic-rke2lab/installations");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

  private GithubAppCli() {}

  /** Entry from {@code Main}'s dispatch: {@code args = ["ghapp", <sub>, …]}. */
  public static void run(String[] args) {
    final String sub = args.length >= 2 ? args[1] : "";
    switch (sub) {
      case "create" -> create();
      case "install" -> install();
      case "seed" -> seed(args);
      default ->
          throw new IllegalArgumentException(
              "usage: ghapp <create|install|seed>\n"
                  + "  create              open GitHub's pre-filled New-App form (then generate a"
                  + " private key)\n"
                  + "  install             open the App's install page (install on the seedmatic"
                  + " org)\n"
                  + "  seed <appId> [pem]  resolve the installation id and write the githubApp block"
                  + " into .secrets");
    }
  }

  private static void create() {
    System.out.println(
        "ghapp create: opening GitHub's pre-filled New GitHub App form for org " + ORG + " …");
    System.out.println("  " + REGISTRATION_URL);
    browse(REGISTRATION_URL);
    System.out.println(
        "Next: Create GitHub App -> Generate a private key (PEM lands in ~/Downloads), then:");
    System.out.println("  ghapp install        # install it on the org");
    System.out.println("  ghapp seed <appId>   # write the githubApp block into .secrets");
  }

  private static void install() {
    System.out.println("ghapp install: opening the install page for " + APP_NAME + " …");
    System.out.println("  " + INSTALL_URL);
    browse(INSTALL_URL);
    System.out.println(
        "Install on the "
            + ORG
            + " org (select seedmatic/rke2lab or All repositories), then: ghapp seed <appId>");
  }

  private static void seed(String[] args) {
    if (args.length < 3 || args[2].isBlank()) {
      throw new IllegalArgumentException(
          "usage: ghapp seed <appId> [pemPath]  (pemPath defaults to the newest *.pem in"
              + " ~/Downloads)");
    }
    final String appId = args[2].trim();
    final Path pemPath = args.length >= 4 ? Path.of(args[3]) : newestDownloadedPem();
    final String pem = readPem(pemPath);

    final String jwt = issueJwt(appId, pem);
    final JsonNode app = getJson("/app", jwt);
    final JsonNode installation = pickInstallation(getJson("/app/installations", jwt));
    final String installationId = installation.path("id").asText();
    final String account = installation.path("account").path("login").asText();
    final String repos = installation.path("repository_selection").asText();

    final LinkedHashMap<String, String> leaves = new LinkedHashMap<>();
    leaves.put("appId", appId);
    leaves.put("installationId", installationId);
    leaves.put("privateKeyPem", pem.stripTrailing() + "\n");
    DotSecretsWriter.upsert(Path.of(".secrets"), SECRETS_KEY, leaves, Set.of("privateKeyPem"));

    System.out.println(
        "ghapp seed: App "
            + appId
            + " (slug "
            + app.path("slug").asText()
            + ", owner "
            + app.path("owner").path("login").asText()
            + ") — key verified");
    System.out.println(
        "ghapp seed: installation "
            + installationId
            + " on "
            + account
            + " (repository_selection="
            + repos
            + ")");
    System.out.println(
        "ghapp seed: wrote the githubApp block into .secrets (privateKeyPem # sops:encrypted)");
    if (!"all".equals(repos)) {
      System.out.println(
          "ghapp seed: NOTE selection is '"
              + repos
              + "' — ensure seedmatic/rke2lab is in the installed repos, else Flux cannot pull.");
    }
  }

  private static void browse(URI url) {
    try {
      if (!GraphicsEnvironment.isHeadless()
          && Desktop.isDesktopSupported()
          && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(url);
        return;
      }
    } catch (IOException | UnsupportedOperationException e) {
      // fall through to the printed URL
    }
    System.out.println("(no desktop browser — open it manually)");
  }

  private static Path newestDownloadedPem() {
    final Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
    try (Stream<Path> files = Files.list(downloads)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".pem"))
          .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "no *.pem in "
                          + downloads
                          + " — pass the PEM path explicitly: ghapp seed <appId> <pemPath>"));
    } catch (IOException e) {
      throw new UncheckedIOException("could not list " + downloads, e);
    }
  }

  private static String readPem(Path pemPath) {
    if (!Files.isReadable(pemPath)) {
      throw new IllegalStateException("PEM not readable: " + pemPath);
    }
    try {
      return Files.readString(pemPath, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the PEM " + pemPath, e);
    }
  }

  private static JsonNode pickInstallation(JsonNode installations) {
    if (!installations.isArray() || installations.isEmpty()) {
      throw new IllegalStateException(
          "the App has no installations yet — run 'ghapp install' and install it on the "
              + ORG
              + " org, then re-run 'ghapp seed'");
    }
    for (final JsonNode installation : installations) {
      if (ORG.equalsIgnoreCase(installation.path("account").path("login").asText())) {
        return installation;
      }
    }
    return installations.get(0);
  }

  private static JsonNode getJson(String path, String jwt) {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(API.resolve(path))
            .header("Authorization", "Bearer " + jwt)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .GET()
            .build();
    try {
      final HttpResponse<String> response =
          HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "GitHub " + path + " -> HTTP " + response.statusCode() + " " + response.body());
      }
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("GitHub " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub " + path + " interrupted", e);
    }
  }

  private static String issueJwt(String appId, String pem) {
    final PrivateKey key = readPrivateKey(pem);
    final Instant now = Instant.now();
    final ObjectNode header = MAPPER.createObjectNode().put("alg", "RS256").put("typ", "JWT");
    final ObjectNode payload =
        MAPPER
            .createObjectNode()
            .put("iat", now.getEpochSecond() - 60)
            .put("exp", now.getEpochSecond() + 540)
            .put("iss", appId);
    try {
      final String signingInput =
          enc(MAPPER.writeValueAsBytes(header)) + "." + enc(MAPPER.writeValueAsBytes(payload));
      final Signature rsa = Signature.getInstance("SHA256withRSA");
      rsa.initSign(key);
      rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + enc(rsa.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not sign the App JWT", e);
    } catch (IOException e) {
      throw new UncheckedIOException("could not serialise the App JWT", e);
    }
  }

  /**
   * Parse the App private key, JDK-only (no BouncyCastle on the host compile classpath). GitHub
   * generates PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), which {@code KeyFactory} does not accept
   * directly; wrap it in a PKCS#8 {@code PrivateKeyInfo} first. A PKCS#8 PEM ({@code BEGIN PRIVATE
   * KEY}) is used as-is.
   */
  private static PrivateKey readPrivateKey(String pem) {
    final boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    final String body = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
    final byte[] der = Base64.getDecoder().decode(body);
    try {
      final byte[] pkcs8 = pkcs1 ? wrapPkcs1AsPkcs8(der) : der;
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not parse the App private key", e);
    }
  }

  /** Wrap a PKCS#1 {@code RSAPrivateKey} DER in a PKCS#8 {@code PrivateKeyInfo} (rsaEncryption). */
  private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
    final byte[] version = {0x02, 0x01, 0x00};
    final byte[] rsaAlgorithm = {
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00
    };
    final byte[] privateKey = derTlv(0x04, pkcs1);
    return derTlv(0x30, concat(version, rsaAlgorithm, privateKey));
  }

  private static byte[] derTlv(int tag, byte[] value) {
    final byte[] length = derLength(value.length);
    final byte[] out = new byte[1 + length.length + value.length];
    out[0] = (byte) tag;
    System.arraycopy(length, 0, out, 1, length.length);
    System.arraycopy(value, 0, out, 1 + length.length, value.length);
    return out;
  }

  private static byte[] derLength(int length) {
    if (length < 0x80) {
      return new byte[] {(byte) length};
    }
    int count = 0;
    for (int probe = length; probe > 0; probe >>= 8) {
      count++;
    }
    final byte[] out = new byte[1 + count];
    out[0] = (byte) (0x80 | count);
    int remaining = length;
    for (int i = count; i >= 1; i--) {
      out[i] = (byte) (remaining & 0xff);
      remaining >>= 8;
    }
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (final byte[] part : parts) {
      total += part.length;
    }
    final byte[] out = new byte[total];
    int offset = 0;
    for (final byte[] part : parts) {
      System.arraycopy(part, 0, out, offset, part.length);
      offset += part.length;
    }
    return out;
  }

  private static String enc(byte[] bytes) {
    return URL64.encodeToString(bytes);
  }
}
