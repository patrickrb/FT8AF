package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.bandadvisor.BandAdvisor
import radio.ks3ckc.ft8af.bandadvisor.model.AdvisorTargetRegion

@RunWith(RobolectricTestRunner::class)
class AlertGridTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `cold process reads persisted grid without globals`() {
        GeneralVariables.setMyMaidenheadGrid("")
        context.openOrCreateDatabase("data.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE config (KeyName TEXT, Value TEXT)")
            db.execSQL("INSERT INTO config VALUES ('GRID', 'EM28ab')")
        }
        assertThat(loadAlertGrid(context)).isEqualTo("EM28")
        assertThat(GeneralVariables.getMyMaidenheadGrid()).isEmpty()
    }

    @Test
    fun `missing or invalid persisted grid skips checks`() {
        assertThat(loadAlertGrid(context)).isNull()
        context.openOrCreateDatabase("data.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE config (KeyName TEXT, Value TEXT)")
        }
        assertThat(loadAlertGrid(context)).isNull()
        context.openOrCreateDatabase("data.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("INSERT INTO config VALUES ('grid', 'INVALID')")
        }
        assertThat(loadAlertGrid(context)).isNull()
    }

    @Test
    fun `target region defaults and persists`() {
        assertThat(BandAdvisor.targetRegion(context)).isEqualTo(AdvisorTargetRegion.EUROPE)
        BandAdvisor.setTargetRegion(context, AdvisorTargetRegion.ASIA)
        assertThat(BandAdvisor.targetRegion(context)).isEqualTo(AdvisorTargetRegion.ASIA)
        assertThat(AdvisorTargetRegion.fromWireName("UNKNOWN")).isEqualTo(AdvisorTargetRegion.EUROPE)
    }
}
