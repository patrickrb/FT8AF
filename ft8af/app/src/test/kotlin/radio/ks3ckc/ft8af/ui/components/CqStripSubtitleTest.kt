package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [cqStripSubtitle] — the precedence that decides the small label under the
 * "Call CQ" button (free-text > Field Day > custom modifier > none, and nothing while in STOP).
 */
class CqStripSubtitleTest {

    @Test
    fun `stop state shows no subtitle`() {
        assertThat(cqStripSubtitle(cqIsStop = true, isFreeTextMode = true, fieldDayEnabled = true, cqModifier = "DX"))
            .isNull()
    }

    @Test
    fun `free text wins over field day and modifier`() {
        assertThat(cqStripSubtitle(cqIsStop = false, isFreeTextMode = true, fieldDayEnabled = true, cqModifier = "DX"))
            .isEqualTo("FREE")
    }

    @Test
    fun `field day wins over modifier`() {
        assertThat(cqStripSubtitle(cqIsStop = false, isFreeTextMode = false, fieldDayEnabled = true, cqModifier = "DX"))
            .isEqualTo("FD")
    }

    @Test
    fun `modifier shown when set and no higher-priority mode`() {
        assertThat(cqStripSubtitle(cqIsStop = false, isFreeTextMode = false, fieldDayEnabled = false, cqModifier = "DX"))
            .isEqualTo("DX")
    }

    @Test
    fun `blank modifier and no modes yields no subtitle`() {
        assertThat(cqStripSubtitle(cqIsStop = false, isFreeTextMode = false, fieldDayEnabled = false, cqModifier = ""))
            .isNull()
    }
}
