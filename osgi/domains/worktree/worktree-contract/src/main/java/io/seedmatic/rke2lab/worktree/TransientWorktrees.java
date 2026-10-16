package io.seedmatic.rke2lab.worktree;

import java.util.Optional;

/**
 * Opens the heads the seed commits onto, in the worktree's repository: fetches {@code branch} from
 * origin with the caller's EXPLICIT credential ({@code fetchToken}, else none — never a credential
 * the machine holds), so an edit accretes on the branch's real, pushed history.
 */
public interface TransientWorktrees {

  TransientWorktree open(String branch, Optional<String> fetchToken);
}
