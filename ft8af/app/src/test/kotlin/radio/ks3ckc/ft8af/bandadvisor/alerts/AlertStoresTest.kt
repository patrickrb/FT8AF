package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertStoresTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `prefs round-trip`() {
        val prefs = AlertPrefs(
            enabled = true,
            enabledTypes = setOf(AlertType.BAND_OPENING, AlertType.REGION_REACHABLE),
            quietStartHour = 23,
            quietEndHour = 6,
            watchedBands = setOf("10m", "15m"),
            watchedRegions = setOf("EUROPE"),
        )
        AlertPreferencesStore.save(context, prefs)
        assertThat(AlertPreferencesStore.load(context)).isEqualTo(prefs)
    }

    @Test
    fun `defaults are everything-off`() {
        val fresh = AlertPreferencesStore.load(context)
        assertThat(fresh.enabled).isFalse()
        assertThat(fresh.enabledTypes).isEmpty()
    }

    @Test
    fun `unknown persisted alert-type ids are dropped`() {
        context.getSharedPreferences("ft8af_prop_alerts", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("types", setOf("band_opening", "future_alert_type"))
            .commit()
        assertThat(AlertPreferencesStore.load(context).enabledTypes)
            .containsExactly(AlertType.BAND_OPENING)
    }

    @Test
    fun `history records, prunes, and clears`() {
        val now = 1_790_532_000_000L
        AlertHistoryStore.recordDelivery(context, "a|10m|-|1", now - DEDUP_RETENTION_MS - 1000)
        AlertHistoryStore.recordDelivery(context, "b|15m|-|2", now)
        // Recording "b" prunes the expired "a".
        assertThat(AlertHistoryStore.deliveredAt(context).keys).containsExactly("b|15m|-|2")

        AlertHistoryStore.clear(context)
        assertThat(AlertHistoryStore.deliveredAt(context)).isEmpty()
    }

    @Test
    fun `corrupt history json degrades to empty`() {
        assertThat(AlertHistoryStore.parse("not json")).isEmpty()
        assertThat(AlertHistoryStore.parse(null)).isEmpty()
        val round = AlertHistoryStore.encode(mapOf("k" to 42L))
        assertThat(AlertHistoryStore.parse(round)).containsExactly("k", 42L)
    }
}
