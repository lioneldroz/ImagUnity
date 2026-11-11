package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoxInfoControllerTest {

    @Test
    void returnsConfiguredName() {
        var controller = new BoxInfoController(new BoxProperties("box-a"));

        var info = controller.info();

        assertThat(info.name()).isEqualTo("box-a");
        assertThat(info.version()).isNotBlank();
    }
}
