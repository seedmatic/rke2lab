package io.seedmatic.rke2lab.worktree;

import java.util.List;

/**
 * The working state of a worktree — whether it is {@code clean}, and the {@code uncommittedPaths}
 * when it is not (added, changed, modified, removed, missing, untracked, conflicting). The entry
 * gate reads this to decide whether the ground is fit to sow; the worktree owns the fact, the gate
 * owns the policy (which paths matter). Dual-realm: returned by the {@code DurableWorktree} service
 * OSGi-side, and carried inside {@link WorktreeFacts} across the broker for the flat host.
 */
public record WorkingState(boolean clean, List<String> uncommittedPaths) {

  public WorkingState {
    uncommittedPaths = List.copyOf(uncommittedPaths);
  }
}
