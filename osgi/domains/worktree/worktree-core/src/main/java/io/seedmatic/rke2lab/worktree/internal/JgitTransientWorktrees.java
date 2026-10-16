package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.DurableWorktree;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TransientWorktrees;
import java.time.Duration;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link TransientWorktrees} of the durable worktree: a head is opened in the worktree's
 * repository (its common dir), where the branches already live, so no second repository and no
 * checkout exist. The fetch is the durable worktree's checkout, with the caller's explicit
 * credential.
 */
@Component(service = TransientWorktrees.class)
public final class JgitTransientWorktrees implements TransientWorktrees {

  // An IDLE bound, as the push's: a stalled fetch fails rather than blocks the seed.
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

  private final DurableWorktree worktree;

  @Activate
  public JgitTransientWorktrees(@Reference DurableWorktree worktree) {
    this.worktree = worktree;
  }

  @Override
  public TransientWorktree open(String branch, Optional<String> fetchToken) {
    new JgitCheckout(worktree.root()).fetchBranch(branch, fetchToken, FETCH_TIMEOUT);
    return JgitTransientWorktree.open(worktree.repository(), branch);
  }
}
