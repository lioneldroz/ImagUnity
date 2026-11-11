package com.imagunity.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PackagingConfigTest {

    @Test
    void defaultsStayBelowTheXftpLimit() {
        PackagingConfig config = PackagingConfig.defaults();

        assertThat(config.maxPartSize()).isLessThan(1024L * 1024 * 1024);
        assertThat(config.compressionLevel()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new PackagingConfig(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PackagingConfig(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PackagingConfig(100, 23)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExtremeCompressionLevels() {
        assertThat(new PackagingConfig(100, 1).compressionLevel()).isEqualTo(1);
        assertThat(new PackagingConfig(100, 22).compressionLevel()).isEqualTo(22);
    }
}
