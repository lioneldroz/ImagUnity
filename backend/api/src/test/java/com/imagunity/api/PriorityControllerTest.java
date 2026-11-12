package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.dicom.DicomCondition;
import com.imagunity.dicom.PriorityRule;
import com.imagunity.dicom.PriorityRules;
import com.imagunity.dicom.PrioritySource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Règles de priorité en vigueur, en lecture seule. */
class PriorityControllerTest {

    @Test
    void showsTheRulesInForceAndTheirSource() {
        Instant loaded = Instant.parse("2026-11-12T10:00:00Z");
        PrioritySource source = () -> new PrioritySource.Snapshot(new PriorityRules(4, List.of(
                new PriorityRule("scanner-thorax", 1, new DicomCondition(Map.of("Modality", "CT"),
                        Map.of("SeriesDescription", "*THORAX*|*POUMON*"))))), "fichier /config/priorite.json",
                loaded, "dernier fichier refusé");
        PriorityController.PriorityView view = new PriorityController(Providers.of(PrioritySource.class, source))
                .priority();

        assertThat(view.source()).isEqualTo("fichier /config/priorite.json");
        assertThat(view.loadedAt()).isEqualTo(loaded);
        assertThat(view.error()).isEqualTo("dernier fichier refusé");
        assertThat(view.defaultPriority()).isEqualTo(4);
        assertThat(view.rules()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("scanner-thorax");
            assertThat(r.priority()).isEqualTo(1);
            assertThat(r.and()).containsEntry("Modality", "CT");
            assertThat(r.or()).containsEntry("SeriesDescription", "*THORAX*|*POUMON*");
        });
    }

    @Test
    void withoutRulesEverySeriesHasTheDefaultPriority() {
        PriorityController.PriorityView view = new PriorityController(Providers.none(PrioritySource.class))
                .priority();
        assertThat(view.source()).isEqualTo("aucune règle");
        assertThat(view.rules()).isEmpty();
        assertThat(view.defaultPriority()).isEqualTo(5);
    }
}
