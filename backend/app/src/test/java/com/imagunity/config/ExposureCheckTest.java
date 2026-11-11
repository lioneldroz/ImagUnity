package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

class ExposureCheckTest {

    private static DicomProperties dicom(boolean enabled, String bindAddress) {
        return new DicomProperties(enabled, bindAddress, 11112, Set.of(), List.of(), Path.of("in"),
                Duration.ofSeconds(30), DataSize.ofGigabytes(1));
    }

    @Test
    void listenersOnAllInterfacesAreReported() {
        ExposureCheck check = new ExposureCheck(new MockEnvironment(), dicom(true, ""));

        assertThat(check.exposedListeners()).extracting(l -> l[1])
                .containsExactly("SERVER_ADDRESS", "IMAGUNITY_DICOM_BIND_ADDRESS");
        check.check();
    }

    @Test
    void listenersOnTheLocalNetworkAreNotReported() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.address", "172.31.1.10");
        ExposureCheck check = new ExposureCheck(environment, dicom(true, "172.31.1.10"));

        assertThat(check.exposedListeners()).isEmpty();
        check.check();
    }

    @Test
    void disabledDicomInputIsNotReported() {
        ExposureCheck check = new ExposureCheck(new MockEnvironment().withProperty("server.address", "10.0.0.5"),
                dicom(false, null));

        assertThat(check.exposedListeners()).isEmpty();
    }

    @Test
    void wildcardAddresses() {
        assertThat(ExposureCheck.isWildcard(null)).isTrue();
        assertThat(ExposureCheck.isWildcard("0.0.0.0")).isTrue();
        assertThat(ExposureCheck.isWildcard("::")).isTrue();
        assertThat(ExposureCheck.isWildcard("[::]")).isTrue();
        assertThat(ExposureCheck.isWildcard("127.0.0.1")).isFalse();
    }
}
