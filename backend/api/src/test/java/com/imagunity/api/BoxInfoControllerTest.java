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

    @Test
    void returnsVersionFromJarManifest() {
        var controller = new BoxInfoController(new BoxProperties("box-a"), "0.1.0");

        assertThat(controller.info().version()).isEqualTo("0.1.0");
    }

    @Test
    void outsideAJarVersionIsDev() {
        var controller = new BoxInfoController(new BoxProperties("box-a"), null);

        assertThat(controller.info().version()).isEqualTo("dev");
    }
}
