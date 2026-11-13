package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Configuration centralisée appliquée, en lecture seule. */
class CentralConfigurationControllerTest {

    @Test
    void showsTheAppliedVersionOrThatItIsDisabled() {
        Instant at = Instant.parse("2026-11-13T10:00:00Z");
        CentralConfigurationController.Status.View applied = new CentralConfigurationController.Status.View(true, 7,
                at, "responsable", "thorax d'abord", at, true, true, List.of("chu → box-b"), 8, "mot-clé inconnu",
                at, null);
        CentralConfigurationController.Status status = () -> applied;
        assertThat(new CentralConfigurationController(Providers.of(CentralConfigurationController.Status.class,
                status)).configuration()).isEqualTo(applied);

        CentralConfigurationController.Status.View none = new CentralConfigurationController(Providers.none(
                CentralConfigurationController.Status.class)).configuration();
        assertThat(none.enabled()).isFalse();
        assertThat(none.sequence()).isZero();
        assertThat(none.routingRules()).isEmpty();
    }
}
