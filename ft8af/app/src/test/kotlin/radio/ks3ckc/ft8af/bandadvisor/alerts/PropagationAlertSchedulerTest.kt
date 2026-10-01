package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlagRepository
import radio.ks3ckc.ft8af.flags.FeatureFlags
import radio.ks3ckc.ft8af.flags.FlagEvaluation
import radio.ks3ckc.ft8af.flags.FlagValueSource

@RunWith(RobolectricTestRunner::class)
class PropagationAlertSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FixedFlags(private val enabled: Boolean) : FeatureFlagRepository {
        override fun isEnabled(flag: FeatureFlag) = enabled
        override fun evaluate(flag: FeatureFlag) =
            FlagEvaluation(flag, enabled, FlagValueSource.BUILD_DEFAULT)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun workState(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PropagationAlertScheduler.WORK_NAME)
            .get()

    private val onPrefs = AlertPrefs(enabled = true, enabledTypes = setOf(AlertType.BAND_OPENING))

    @Test
    fun `shouldSchedule requires flag AND master pref AND at least one type`() {
        assertThat(PropagationAlertScheduler.shouldSchedule(true, onPrefs)).isTrue()
        assertThat(PropagationAlertScheduler.shouldSchedule(false, onPrefs)).isFalse()
        assertThat(
            PropagationAlertScheduler.shouldSchedule(true, onPrefs.copy(enabled = false)),
        ).isFalse()
        assertThat(
            PropagationAlertScheduler.shouldSchedule(true, onPrefs.copy(enabledTypes = emptySet())),
        ).isFalse()
    }

    @Test
    fun `sync schedules when enabled and cancels when disabled`() {
        FeatureFlags.setDelegateForTests(FixedFlags(true))
        try {
            AlertPreferencesStore.save(context, onPrefs)
            PropagationAlertScheduler.sync(context)
            assertThat(workState().single().state).isEqualTo(WorkInfo.State.ENQUEUED)

            // User turns the master preference off: pending work is cancelled.
            AlertPreferencesStore.save(context, onPrefs.copy(enabled = false))
            PropagationAlertScheduler.sync(context)
            assertThat(workState().single().state).isEqualTo(WorkInfo.State.CANCELLED)
        } finally {
            FeatureFlags.setDelegateForTests(null)
        }
    }

    @Test
    fun `sync cancels when the feature flag is off even with the pref on`() {
        FeatureFlags.setDelegateForTests(FixedFlags(true))
        try {
            AlertPreferencesStore.save(context, onPrefs)
            PropagationAlertScheduler.sync(context)
            assertThat(workState().single().state).isEqualTo(WorkInfo.State.ENQUEUED)

            // Remote-config rollback: the flag flips off, the next sync cancels.
            FeatureFlags.setDelegateForTests(FixedFlags(false))
            PropagationAlertScheduler.sync(context)
            assertThat(workState().single().state).isEqualTo(WorkInfo.State.CANCELLED)
        } finally {
            FeatureFlags.setDelegateForTests(null)
        }
    }
}
