package io.seedmatic.rke2lab.ndh.contract;

/**
 * The cluster's age identity, {@code rke2lab-cluster-age-<n>}: what sops opens the cluster's
 * secrets with — the node bundle on the node, the {@code sops-age} Secret Flux and the in-cluster
 * render decrypt with. It lives in the operator's keyring ({@code ndh-keyring cluster-age <n>}),
 * not in {@code keys.yaml}, and reaches the seed on a file descriptor the operator's shell opens on
 * the grow's command line — never a file under a checkout, and never stdin, which does not reach a
 * Pulumi program.
 *
 * <p>OPERATOR only: in-cluster nothing opens it, the cluster already holds it in its {@code
 * sops-age} Secret. {@link #present()} is the fail-soft gate; {@link #identity()} is fail-fast and
 * never echoes what it read.
 */
public interface ClusterAgeIdentity {

  /** Whether the operator handed the identity to this run (fail-soft gate). */
  boolean present();

  /** The {@code AGE-SECRET-KEY-1…} identity, read once; a defect raises without quoting it. */
  String identity();
}
