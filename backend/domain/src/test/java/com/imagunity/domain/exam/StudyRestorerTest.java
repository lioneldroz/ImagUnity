package com.imagunity.domain.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Restitution : sans routes entrantes, la box émettrice ne change rien. */
class StudyRestorerTest {

    @Test
    void theOriginIsIgnoredByARestorerWithoutIncomingRoutes() throws Exception {
        List<String> restored = new ArrayList<>();
        StudyRestorer restorer = (study, instances) -> restored.add(study + ":" + instances.size());
        restorer.restore(UUID.randomUUID(), "2.25.1", List.of(new StudyRestorer.Instance("2.25.1.1", "2.25.1.1.1",
                Path.of("x.dcm"))));
        assertThat(restored).containsExactly("2.25.1:1");
    }
}
