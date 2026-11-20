package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaRuntimeCheckTest {

    private static Runtime.Version v(String s) {
        return Runtime.Version.parse(s);
    }

    @Test
    void versionsBeforeTheFixAreReported() {
        assertThat(JavaRuntimeCheck.problem(v("25.0.5.0.3"))).get().asString()
                .contains("CVE-2026-114938").contains("25.0.5.1").contains("25.0.5.0.3");
        assertThat(JavaRuntimeCheck.problem(v("25.0.4"))).isPresent();
        assertThat(JavaRuntimeCheck.problem(v("25"))).isPresent();
        assertThat(JavaRuntimeCheck.problem(v("25.0.5+7"))).isPresent();
    }

    @Test
    void fixedOrOtherVersionsAreNot() {
        assertThat(JavaRuntimeCheck.problem(v("25.0.5.1"))).isEmpty();
        assertThat(JavaRuntimeCheck.problem(v("25.0.5.1+2"))).isEmpty();
        assertThat(JavaRuntimeCheck.problem(v("25.0.6"))).isEmpty();
        assertThat(JavaRuntimeCheck.problem(v("26.0.1"))).isEmpty();
    }

    @Test
    void warningDoesNotFail() {
        JavaRuntimeCheck.warnIfVulnerable();
    }
}
