package io.seedmatic.rke2lab.worktree;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The worktree snapshot the worktree soil harvests into the cellar and the flat host fetches back —
 * the {@code DurableWorktree} service's facts flattened onto the wire: the {@code root} (as a
 * String path; the host re-hydrates a {@link java.nio.file.Path}), the HEAD {@link Provenance}, and
 * the {@link WorkingState}. The worktree {@code @Component} holds the live service OSGi-side; its
 * soil scenario packs THIS record and stores it at {@link WorktreeCoordinate#FACTS}, so no jgit
 * type and no service reference crosses the seam — only the serialized payload, which the host
 * decodes into its OWN copy of this dual-realm record.
 *
 * <p>{@link SeedContract} binds it to the {@code worktree-facts} coordinate for the codec's decode
 * guard; the wire-record type never crosses the seam, only the serialized payload.
 *
 * <p>{@code flakeLockCoherent} is the second git fact the soil harvests at the same pass: whether
 * the latest commit changed a {@code flake.nix} {@code inputs} block without its {@code flake.lock}
 * — consumed by the entry gate when its {@code flakeLock} toggle is armed.
 */
@SeedContract("worktree-facts")
public record WorktreeFacts(
    String root, Provenance provenance, WorkingState workingState, boolean flakeLockCoherent) {}
