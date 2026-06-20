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

    @Test
    public void microphoneType_onlyFromApi30() {
        assertThat(RxServiceController.usesMicrophoneType(23)).isFalse(); // M
        assertThat(RxServiceController.usesMicrophoneType(29)).isFalse(); // Q
        assertThat(RxServiceController.usesMicrophoneType(30)).isTrue();  // R
        assertThat(RxServiceController.usesMicrophoneType(34)).isTrue();  // U
    }
}
