package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.dicom.DicomInputConfig;
import com.imagunity.dicom.DicomStoreScp;
import com.imagunity.dicom.StowService;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class DicomWebControllerTest {

    @TempDir
    Path storage;

    @Test
    void withoutDicomInputTheEndpointAnswersNotFound() throws Exception {
        DicomWebController controller = new DicomWebController(Providers.none(StowService.class));

        var response = controller.store("IU_TO_B", new MockHttpServletRequest(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("désactivée");
    }

    @Test
    void requestsAreHandedToTheStowServiceWithTheAccountAsSource() throws Exception {
        DicomStoreScp scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", 1, Set.of(),
                Map.of("IU_TO_B", "box-b"), storage, Duration.ofSeconds(1), 0), study -> { });
        DicomWebController controller = new DicomWebController(Providers.of(StowService.class,
                new StowService(scp)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dicomweb/IU_TO_B/studies");
        request.setContentType("application/dicom");
        Principal pacs = () -> "pacs";

        var unsupported = controller.store("IU_TO_B", request, pacs);
        var badStudy = controller.storeStudy("IU_TO_B", "pas-un-uid",
                withType("multipart/related; type=\"application/dicom\"; boundary=x"), null);

        assertThat(unsupported.getStatusCode().value()).isEqualTo(415);
        assertThat(unsupported.getHeaders().getFirst("Content-Type")).startsWith("text/plain");
        assertThat(badStudy.getStatusCode().value()).isEqualTo(400);
    }

    private static MockHttpServletRequest withType(String type) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dicomweb/IU_TO_B/studies/x");
        request.setContentType(type);
        return request;
    }
}
