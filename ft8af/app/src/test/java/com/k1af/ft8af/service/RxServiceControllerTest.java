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
    public void commandForAction_exitOnlyOnExactExitAction() {
        assertThat(RxServiceController.commandForAction(RxServiceController.ACTION_EXIT))
                .isEqualTo(RxServiceController.Command.EXIT);
    }

    @Test
    public void commandForAction_runsNormallyForAnythingElse() {
        // null covers plain start() intents and system restarts of the service.
        assertThat(RxServiceController.commandForAction(null))
                .isEqualTo(RxServiceController.Command.RUN);
        assertThat(RxServiceController.commandForAction(""))
                .isEqualTo(RxServiceController.Command.RUN);
        assertThat(RxServiceController.commandForAction("com.k1af.ft8af.service.OTHER"))
                .isEqualTo(RxServiceController.Command.RUN);
    }
}
