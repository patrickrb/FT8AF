package radio.ks3ckc.ft8af.bandadvisor.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalAnalyticsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `history round-trips per band`() {
        PersonalAnalyticsStore.record(context, "20m", 5000)
        PersonalAnalyticsStore.record(context, "20m", 6000)
        PersonalAnalyticsStore.record(context, "40m", 1500)
        assertThat(PersonalAnalyticsStore.historyFor(context, "20m"))
            .containsExactly(5000, 6000).inOrder()
        assertThat(PersonalAnalyticsStore.historyFor(context, "40m")).containsExactly(1500)
        assertThat(PersonalAnalyticsStore.historyFor(context, "10m")).isEmpty()
    }

    @Test
    fun `history is bounded to the newest sessions`() {
        repeat(PersonalAnalyticsStore.MAX_SESSIONS + 5) { i ->
            PersonalAnalyticsStore.record(context, "20m", i)
        }
        val history = PersonalAnalyticsStore.historyFor(context, "20m")
        assertThat(history).hasSize(PersonalAnalyticsStore.MAX_SESSIONS)
        assertThat(history.last()).isEqualTo(PersonalAnalyticsStore.MAX_SESSIONS + 4)
        assertThat(history.first()).isEqualTo(5)
    }

    @Test
    fun `clear wipes everything`() {
        PersonalAnalyticsStore.record(context, "20m", 5000)
        PersonalAnalyticsStore.clear(context)
        assertThat(PersonalAnalyticsStore.historyFor(context, "20m")).isEmpty()
    }

    @Test
    fun `corrupt persisted json degrades to empty`() {
        assertThat(PersonalAnalyticsStore.parseHistory("not json")).isEmpty()
        assertThat(PersonalAnalyticsStore.parseHistory(null)).isEmpty()
        val roundTrip = PersonalAnalyticsStore.encodeHistory(mapOf("20m" to listOf(1, 2)))
        assertThat(PersonalAnalyticsStore.parseHistory(roundTrip)).containsExactly("20m", listOf(1, 2))
    }
}
