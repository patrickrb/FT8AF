package com.k1af.ft8af.service;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure-JVM tests for {@link RxServiceController}. */
public class RxServiceControllerTest {

    @Test
    public void shouldRun_onlyWhenActiveAndMicGranted() {
        assertThat(RxServiceController.shouldRunService(true, true)).isTrue();
        assertThat(RxServiceController.shouldRunService(true, false)).isFalse();
        assertThat(RxServiceController.shouldRunService(false, true)).isFalse();
        assertThat(RxServiceController.shouldRunService(false, false)).isFalse();
    }
}
