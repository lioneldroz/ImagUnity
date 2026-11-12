package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoxInfoControllerTest {

    @Test
    void returnsConfiguredName() {
        var controller = new BoxInfoController(new BoxProperties("box-a"), "", 0);

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
        assertThat(controller.info().releaseSequence()).isNull();
    }

    @Test
    void signedVersionCheckedByTheLauncherIsShown() {
        var controller = new BoxInfoController(new BoxProperties("box-a"), "0.1.0-SNAPSHOT", "0.2.0", 2);

        assertThat(controller.info().version()).isEqualTo("0.2.0");
        assertThat(controller.info().releaseSequence()).isEqualTo(2);
        assertThat(new BoxInfoController(new BoxProperties("box-a"), "0.1.0", "", 3).info().version())
                .isEqualTo("0.1.0");
    }
}
