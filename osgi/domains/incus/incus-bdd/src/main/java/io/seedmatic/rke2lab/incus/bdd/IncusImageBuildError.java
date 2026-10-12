package io.seedmatic.rke2lab.incus.bdd;

import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.doctor.contract.Symptomatic;
import io.seedmatic.rke2lab.incus.contract.ImageBuildRequest;
import java.util.Map;

/**
 * The node-base image build (nix realise of a committed revision) failed. {@link Symptomatic}:
 * carries the {@link ImageBuildRequest} that failed as a typed member and the edge exception as the
 * cause, so a consumer sees which revision and artifact dir were targeted and the underlying reason
 * without parsing the message.
 */
public final class IncusImageBuildError extends AssertionError implements Symptomatic {

  private final transient ImageBuildRequest request;

  public IncusImageBuildError(ImageBuildRequest request, Throwable cause) {
    super("incus image build failed: " + cause.getMessage(), cause);
    this.request = request;
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.IMAGE_BUILD_FAILED;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of("flakeRef", request.flakeRef(), "artifactDir", request.artifactDir());
  }

  public ImageBuildRequest request() {
    return request;
  }
}
