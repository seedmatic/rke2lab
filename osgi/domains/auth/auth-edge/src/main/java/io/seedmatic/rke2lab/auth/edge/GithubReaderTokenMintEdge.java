package io.seedmatic.rke2lab.auth.edge;

import io.seedmatic.rke2lab.auth.contract.GithubReaderTokenMint;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppMinter;
import io.seedmatic.rke2lab.ghapp.contract.TokenScope;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The realised on-demand read-token edge: mints a FRESH {@code READER} ({@code contents:read})
 * installation token from the App credentials the consumer hands it, delegating to the ghapp {@link
 * GithubAppMinter}. The least-privilege twin of {@link GithubWriterTokenMintEdge} — same seam (auth
 * is the one place that delegates GitHub sourcing to ghapp), narrower scope, for a consumer that
 * only reads (a node fetching its rendered branch).
 *
 * <p>The mandatory {@link Reference} to {@link GithubAppMinter} (a {@code cultivating}-gated
 * {@code @Component}) means this edge only activates when the minter is present, and it is itself
 * tagged {@code rke2lab.gardening=cultivating}: under a survey/preview frontier the consumer's
 * {@code @OsgiService} filter resolves it empty, so no token is fabricated.
 */
@Component(service = GithubReaderTokenMint.class, property = "rke2lab.gardening=cultivating")
public final class GithubReaderTokenMintEdge implements GithubReaderTokenMint {

  private final GithubAppMinter minter;

  @Activate
  public GithubReaderTokenMintEdge(@Reference GithubAppMinter minter) {
    this.minter = minter;
  }

  @Override
  public Optional<String> mint(String appId, String installationId, String privateKeyPem) {
    return Optional.of(
        minter
            .mint(new GithubAppCredentials(appId, installationId, privateKeyPem), TokenScope.READER)
            .token());
  }
}
