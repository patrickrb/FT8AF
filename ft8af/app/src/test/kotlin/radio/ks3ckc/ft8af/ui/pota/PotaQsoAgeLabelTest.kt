package radio.ks3ckc.ft8af.ui.pota

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resource side of the POTA row's "ago" readout: [qsoAgeLabel] must
 * resolve every [QsoAgeUnit] through the string/plural resources (so the row
 * is translated with the rest of the screen — Copilot review on #787) and the
 * default English wording must stay what the row showed before it was
 * localized.
 */
@RunWith(AndroidJUnit4::class)
class PotaQsoAgeLabelTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun justNow_usesTheStringResource() {
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.JUST_NOW, 0)))
            .isEqualTo(res.getString(R.string.pota_qso_age_just_now))
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.JUST_NOW, 0))).isEqualTo("just now")
    }

    @Test
    fun everyUnit_resolvesThroughItsPlural() {
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.SECONDS, 45))).isEqualTo("45s ago")
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.MINUTES, 5))).isEqualTo("5m ago")
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.HOURS, 2))).isEqualTo("2h ago")
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.DAYS, 3))).isEqualTo("3d ago")
    }

    @Test
    fun singularCounts_goThroughTheOneQuantity() {
        // English spells both quantities the same; the point is that the
        // count reaches the plural lookup so a locale that differs can differ.
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.MINUTES, 1)))
            .isEqualTo(res.getQuantityString(R.plurals.pota_qso_age_minutes, 1, 1))
        assertThat(qsoAgeLabel(res, QsoAge(QsoAgeUnit.DAYS, 1))).isEqualTo("1d ago")
    }

    @Test
    fun endToEnd_fromTimestampsToLabel() {
        val now = 1_700_000_000_000L
        assertThat(qsoAgeLabel(res, qsoTimeAgo(now - 5 * 60_000L, now))).isEqualTo("5m ago")
        assertThat(qsoAgeLabel(res, qsoTimeAgo(now + 120_000L, now))).isEqualTo("just now")
    }
}
