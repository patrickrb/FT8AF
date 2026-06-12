package com.bg7yoz.ft8cn

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import radio.ks3ckc.ft8us.ComposeMainActivity

/**
 * Instrumented smoke test. Launches the real ComposeMainActivity on a device
 * (or emulator) and asserts it reaches RESUMED without throwing.
 *
 * Runs in CI on an emulator via the `instrumented` job in
 * .github/workflows/build-release.yml. Local invocation:
 *   cd ft8cn && cmd.exe /c "gradlew.bat connectedDebugAndroidTest"
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    /**
     * ComposeMainActivity.onCreate requests these dangerous permissions. On a
     * fresh install the system permission dialog sits on top of the activity,
     * which then never leaves PAUSED and the RESUMED assertion below times out.
     * Pre-granting them lets the activity reach RESUMED so the test exercises
     * the real launch path rather than the one-time permission prompt. (On the
     * API-30 CI image BLUETOOTH_CONNECT/POST_NOTIFICATIONS aren't requested, so
     * these three cover every dialog-triggering permission.)
     */
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun composeMainActivity_launchesAndReachesResumed() {
        ActivityScenario.launch(ComposeMainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertThat(activity).isNotNull()
                assertThat(activity.isFinishing).isFalse()
            }
        }
    }
}
