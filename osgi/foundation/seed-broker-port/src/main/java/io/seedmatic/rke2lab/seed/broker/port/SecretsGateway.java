package io.seedmatic.rke2lab.seed.broker.port;

import java.util.Optional;

/**
 * The operator-secrets membrane, published ONCE by the host as a service the whole run shares — the
 * READ door onto {@code .secrets} (sops-governed), owned by the flat host that already reads it
 * (the {@code ConfigLoader} family) and grows the framework. Like {@link RunGate}, it lives in the
 * neutral seam both worlds see: the host registers its realisation into the framework it booted,
 * and an in-container scion resolves it by service lookup and calls back into host code — same JVM
 * (Felix embedded), only the interface is shared.
 *
 * <p>It speaks JSON only (a {@code String} subtree), never a domain type — the seam law. A consumer
 * maps the JSON to its own record in-container. So {@code .secrets} I/O stays 100% host-side, while
 * the scion orchestrates when to rehydrate an anchor. Writing {@code .secrets} is the operator's
 * hand — the one App key is seeded by hand once — so this seam is read-only.
 */
public interface SecretsGateway {

  /**
   * The JSON of the {@code .secrets} subtree at {@code dottedPath} (e.g. {@code "github"}), or
   * empty when the block is absent. The worktree copy is smudged plaintext, so the JSON is
   * readable.
   */
  Optional<String> read(String dottedPath);
}
