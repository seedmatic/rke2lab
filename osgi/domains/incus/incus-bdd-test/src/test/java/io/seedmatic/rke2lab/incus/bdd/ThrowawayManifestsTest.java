package io.seedmatic.rke2lab.incus.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.jgiven.CurrentStep;
import com.tngtech.jgiven.attachment.Attachment;
import io.seedmatic.rke2lab.incus.contract.IncusRunbookInput.Facet;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A throwaway cluster's provision consults no manifests: its render would carry the fleet's sealed
 * Secrets, the tailnet resources and the push to the fleet's repo. The step is played with no
 * broker at all, so the fleet's consult fails where the throwaway's returns.
 */
class ThrowawayManifestsTest {

  private static final class NamedStep implements CurrentStep {
    String name = "";

    @Override
    public void addAttachment(Attachment attachment) {}

    @Override
    public void setExtendedDescription(String description) {}

    @Override
    public void setName(String name) {
      this.name = name;
    }

    @Override
    public void setComment(String comment) {}
  }

  private static String cultivate(boolean throwaway) {
    final NamedStep step = new NamedStep();
    final IncusProvisionScenario.When when = new IncusProvisionScenario.When();
    when.currentStep = step;
    when.soil = Optional.empty();
    when.the_manifests_are_cultivated(
        null,
        null,
        Optional.of(new Facet("nikopol-mgmt", "master", "throwaway-nikopol", throwaway)),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
    return step.name;
  }

  @Test
  void a_throwaway_cluster_consults_no_manifests() {
    assertEquals("the manifests are not rendered: a throwaway cluster takes none", cultivate(true));
  }

  @Test
  void a_fleet_cluster_consults_the_manifests_through_the_broker() {
    assertThrows(NoSuchElementException.class, () -> cultivate(false));
  }
}
