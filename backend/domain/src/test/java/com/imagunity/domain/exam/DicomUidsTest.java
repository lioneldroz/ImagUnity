package com.imagunity.domain.exam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DicomUidsTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.2.840.10008.5.1.4.1.1.2", "2.25.123456789012345678901234567890", "0.1", "1"})
    void acceptsValidUids(String uid) {
        assertThat(DicomUids.isValid(uid)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1..2", "1.2.", ".1", "1.02", "1.2.a", "../../etc/passwd", "1/2", "1.2 3",
            "1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17.18.19.20.21.22.23.24.25.26.27.28.29"})
    void rejectsInvalidUids(String uid) {
        assertThat(DicomUids.isValid(uid)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void rejectsNull() {
        assertThat(DicomUids.isValid(null)).isFalse();
    }
}
