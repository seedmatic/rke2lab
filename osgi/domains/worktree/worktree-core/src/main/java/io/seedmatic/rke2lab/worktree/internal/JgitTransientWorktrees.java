package io.seedmatic.rke2lab.worktree.internal;

import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TransientWorktrees;
import io.seedmatic.rke2lab.worktree.Worktree;
import java.time.Duration;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link TransientWorktrees} of the seat: a head is opened in the seat's repository (its common
 * dir), where the branches already live, so no second repository and no checkout exist. The fetch
 * is the seat checkout's, with the caller's explicit credential.
 */
@Component(service = TransientWorktrees.class)
public final class JgitTransientWorktrees implements TransientWorktrees {

  // An IDLE bound, as the push's: a stalled fetch fails rather than blocks the seed.
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

  private final Worktree seat;

  @Activate
  public JgitTransientWorktrees(@Reference Worktree seat) {
    this.seat = seat;
  }

  @Override
  public TransientWorktree open(String branch, Optional<String> fetchToken) {
    new JgitCheckout(seat.root()).fetchBranch(branch, fetchToken, FETCH_TIMEOUT);
    return JgitTransientWorktree.open(seat.repository(), branch);
  }
}
