package io.seedmatic.rke2lab.manifests.bdd.versions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * The run's own {@code GITHUB_TOKEN}: read when set, sent to GitHub's API and nowhere else, and
 * never written to a report row, stdout, stderr or a log record. No network: every request is
 * recorded and answered 403, the quota's own answer.
 */
class GithubTokenTest {

  private static final String SENTINEL = "ghp_sentinel_never_printed_0123456789";

  @Test
  void the_variable_is_read_when_set_and_ignored_when_blank() {
    assertEquals(
        Optional.of(SENTINEL),
        new EnvironmentToken(Map.of(EnvironmentToken.VARIABLE, SENTINEL)::get).githubToken());
    assertEquals(
        Optional.empty(),
        new EnvironmentToken(Map.of(EnvironmentToken.VARIABLE, "  ")::get).githubToken());
    assertEquals(
        Optional.empty(), new EnvironmentToken(Map.<String, String>of()::get).githubToken());
    assertFalse(
        new EnvironmentToken(Map.of(EnvironmentToken.VARIABLE, SENTINEL)::get)
            .toString()
            .contains(SENTINEL));
  }

  @Test
  void the_token_goes_to_the_github_api_and_nowhere_else() {
    final RecordingClient http = new RecordingClient();
    new VersionBumper(BumpLevel.MAJOR, Optional.of(SENTINEL), http).report();
    final List<HttpRequest> github =
        http.requests.stream().filter(r -> "api.github.com".equals(r.uri().getHost())).toList();
    assertFalse(github.isEmpty(), "no GitHub query was made");
    for (final HttpRequest request : http.requests) {
      final Optional<String> authorization = request.headers().firstValue("Authorization");
      if ("api.github.com".equals(request.uri().getHost())) {
        assertEquals(Optional.of("Bearer " + SENTINEL), authorization, request.uri().toString());
      } else {
        assertFalse(authorization.orElse("").contains(SENTINEL), "token sent to " + request.uri());
      }
    }
  }

  @Test
  void the_token_reaches_no_report_row_stream_or_log() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<String> logged = new ArrayList<>();
    final Handler handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            logged.add(String.valueOf(record.getMessage()) + " " + record.getThrown());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final Logger root = Logger.getLogger("");
    final PrintStream stdout = System.out;
    final PrintStream stderr = System.err;
    final List<VersionReport> rows;
    root.addHandler(handler);
    try (PrintStream capture = new PrintStream(out, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      System.setErr(capture);
      rows =
          new VersionBumper(BumpLevel.MAJOR, Optional.of(SENTINEL), new RecordingClient()).report();
    } finally {
      System.setOut(stdout);
      System.setErr(stderr);
      root.removeHandler(handler);
    }
    assertFalse(rows.isEmpty());
    for (final VersionReport row : rows) {
      assertFalse(row.note().contains(SENTINEL), row.note());
      assertFalse(row.jsonLine().contains(SENTINEL), row.jsonLine());
    }
    assertTrue(rows.stream().anyMatch(r -> r.note().contains("HTTP 403")), "the 403 was not seen");
    assertFalse(out.toString(StandardCharsets.UTF_8).contains(SENTINEL));
    assertTrue(logged.stream().noneMatch(line -> line.contains(SENTINEL)), logged.toString());
  }

  @Test
  void a_release_asset_is_downloaded_without_the_token() throws Exception {
    final String asset = "https://github.com/o/r/releases/download/v1.0.0/release.yaml";
    final RecordingClient http =
        new RecordingClient(
            Map.of(
                "https://api.github.com/repos/o/r/releases/tags/v1.0.0",
                "{\"assets\":[{\"name\":\"release.yaml\",\"browser_download_url\":\""
                    + asset
                    + "\"}]}",
                asset,
                "kind: List"));
    final byte[] bytes =
        new VersionBumper(BumpLevel.MAJOR, Optional.of(SENTINEL), http)
            .fetchReleaseAsset("o/r", "v1.0.0", "release.yaml");
    assertEquals("kind: List", new String(bytes, StandardCharsets.UTF_8));
    final HttpRequest download =
        http.requests.stream()
            .filter(r -> r.uri().toString().equals(asset))
            .findFirst()
            .orElseThrow();
    assertEquals(Optional.empty(), download.headers().firstValue("Authorization"));
  }

  /**
   * Records every request; answers a known URL 200 with its body (bytes when the request accepts
   * octet-stream), any other with a bodiless 403.
   */
  private static final class RecordingClient extends HttpClient {

    final List<HttpRequest> requests = new ArrayList<>();
    private final Map<String, String> answers;

    RecordingClient() {
      this(Map.of());
    }

    RecordingClient(final Map<String, String> answers) {
      this.answers = answers;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(
        final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
      requests.add(request);
      final String answer = answers.get(request.uri().toString());
      final boolean bytes =
          request.headers().firstValue("Accept").orElse("").equals("application/octet-stream");
      return new HttpResponse<>() {
        @Override
        public int statusCode() {
          return answer == null ? 403 : 200;
        }

        @Override
        public HttpRequest request() {
          return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
          return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
          return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        @Override
        public T body() {
          if (answer == null) {
            return null;
          }
          return (T) (bytes ? answer.getBytes(StandardCharsets.UTF_8) : answer);
        }

        @Override
        public Optional<SSLSession> sslSession() {
          return Optional.empty();
        }

        @Override
        public URI uri() {
          return request.uri();
        }

        @Override
        public HttpClient.Version version() {
          return HttpClient.Version.HTTP_1_1;
        }
      };
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
      return CompletableFuture.completedFuture(send(request, handler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        final HttpRequest request,
        final HttpResponse.BodyHandler<T> handler,
        final HttpResponse.PushPromiseHandler<T> push) {
      return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
}
