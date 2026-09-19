package radio.ks3ckc.ft8af.ui.rateprompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure decision logic for the in-app rating prompt — no Android types. */
class RatePromptLogicTest {

    // ---- shouldShowRatePrompt ----

    @Test
    fun `below the QSO threshold never shows`() {
        assertThat(shouldShowRatePrompt(qsoCount = 4, nextEligible = 0, declineCount = 0, resolved = false))
            .isFalse()
        assertThat(shouldShowRatePrompt(qsoCount = 0, nextEligible = 0, declineCount = 0, resolved = false))
            .isFalse()
    }

    @Test
    fun `exactly five QSOs shows on a fresh install`() {
        assertThat(shouldShowRatePrompt(qsoCount = 5, nextEligible = 0, declineCount = 0, resolved = false))
            .isTrue()
    }

    @Test
    fun `resolved short-circuits even when otherwise eligible`() {
        assertThat(shouldShowRatePrompt(qsoCount = 500, nextEligible = 0, declineCount = 0, resolved = true))
            .isFalse()
    }

    @Test
    fun `two declines suppress the prompt`() {
        assertThat(shouldShowRatePrompt(qsoCount = 500, nextEligible = 0, declineCount = 2, resolved = false))
            .isFalse()
        assertThat(shouldShowRatePrompt(qsoCount = 500, nextEligible = 0, declineCount = 1, resolved = false))
            .isTrue()
    }

    @Test
    fun `waits for the next eligible QSO count`() {
        assertThat(shouldShowRatePrompt(qsoCount = 19, nextEligible = 20, declineCount = 1, resolved = false))
            .isFalse()
        assertThat(shouldShowRatePrompt(qsoCount = 20, nextEligible = 20, declineCount = 1, resolved = false))
            .isTrue()
    }

    // ---- ratePromptVariant ----

    @Test
    fun `one band and two entities is minimal`() {
        assertThat(ratePromptVariant(bandsWorked = 1, dxccEntities = 2)).isEqualTo(RatePromptVariant.MINIMAL)
    }

    @Test
    fun `two bands is milestone`() {
        assertThat(ratePromptVariant(bandsWorked = 2, dxccEntities = 2)).isEqualTo(RatePromptVariant.MILESTONE)
    }

    @Test
    fun `three entities is milestone`() {
        assertThat(ratePromptVariant(bandsWorked = 1, dxccEntities = 3)).isEqualTo(RatePromptVariant.MILESTONE)
    }

    // ---- evaluateRatePrompt ----

    @Test
    fun `evaluate returns the variant when eligible and null otherwise`() {
        val stats = RatePromptLogStats(qsoCount = 5, bandsWorked = 2, dxccEntities = 1)
        assertThat(evaluateRatePrompt(stats, RatePromptState())).isEqualTo(RatePromptVariant.MILESTONE)
        assertThat(evaluateRatePrompt(stats, RatePromptState(resolved = true))).isNull()
        assertThat(evaluateRatePrompt(stats.copy(qsoCount = 4), RatePromptState())).isNull()
    }

    // ---- POTA activation-end trigger ----

    @Test
    fun `activation end triggers only at the park-credit minimum`() {
        assertThat(activationEndTriggersRatePrompt(0)).isFalse()
        assertThat(activationEndTriggersRatePrompt(9)).isFalse()
        assertThat(activationEndTriggersRatePrompt(10)).isTrue()
        assertThat(activationEndTriggersRatePrompt(42)).isTrue()
    }

    // ---- state transitions ----

    @Test
    fun `remind later re-arms fifteen QSOs out and counts a decline`() {
        val next = RatePromptState().afterDecline(qsoCount = 5)
        assertThat(next).isEqualTo(RatePromptState(declineCount = 1, nextEligibleQsoCount = 20, resolved = false))
        assertThat(shouldShowRatePrompt(19, next.nextEligibleQsoCount, next.declineCount, next.resolved)).isFalse()
        assertThat(shouldShowRatePrompt(20, next.nextEligibleQsoCount, next.declineCount, next.resolved)).isTrue()
    }

    @Test
    fun `second decline resolves permanently`() {
        val next = RatePromptState(declineCount = 1, nextEligibleQsoCount = 20).afterDecline(qsoCount = 20)
        assertThat(next.declineCount).isEqualTo(2)
        assertThat(next.resolved).isTrue()
    }

    @Test
    fun `dont ask again and completion both resolve`() {
        assertThat(RatePromptState().afterDontAskAgain().resolved).isTrue()
        assertThat(RatePromptState().afterCompleted().resolved).isTrue()
        assertThat(RatePromptState().afterCompleted().declineCount).isEqualTo(0)
    }

    @Test
    fun `swipe or back on the ask step is a decline`() {
        val expected = RatePromptState().afterDecline(qsoCount = 7)
        assertThat(RatePromptState().afterDismiss(RatePromptStep.ASK, qsoCount = 7)).isEqualTo(expected)
    }

    @Test
    fun `swipe or back on the feedback step counts as skip`() {
        val next = RatePromptState().afterDismiss(RatePromptStep.FEEDBACK, qsoCount = 7)
        assertThat(next).isEqualTo(RatePromptState(resolved = true))
    }

    // ---- close latch (one persisted close per presentation) ----

    @Test
    fun `close latch allows exactly one close per presentation`() {
        val latch = RatePromptCloseLatch()
        latch.open()
        assertThat(latch.tryClose()).isTrue()
        // Second Back during the exit animation: must not persist another decline.
        assertThat(latch.tryClose()).isFalse()
        assertThat(latch.tryClose()).isFalse()
    }

    @Test
    fun `close latch refuses closes before the sheet is shown and re-arms on the next show`() {
        val latch = RatePromptCloseLatch()
        assertThat(latch.tryClose()).isFalse()
        latch.open()
        assertThat(latch.tryClose()).isTrue()
        latch.open()
        assertThat(latch.tryClose()).isTrue()
    }

    @Test
    fun `a double dismissal applies only one decline`() {
        val latch = RatePromptCloseLatch().apply { open() }
        var state = RatePromptState()
        repeat(2) {
            if (latch.tryClose()) state = state.afterDismiss(RatePromptStep.ASK, qsoCount = 5)
        }
        assertThat(state.declineCount).isEqualTo(1)
        assertThat(state.resolved).isFalse()
    }

    // ---- milestone headline + copy helpers ----

    @Test
    fun `headline names entities and bands when both milestones hit`() {
        assertThat(milestoneHeadline(bandsWorked = 2, dxccEntities = 3))
            .isEqualTo(MilestoneHeadline.ENTITIES_ACROSS_BANDS)
    }

    @Test
    fun `headline names entities alone on a single band`() {
        assertThat(milestoneHeadline(bandsWorked = 1, dxccEntities = 3)).isEqualTo(MilestoneHeadline.ENTITIES)
    }

    @Test
    fun `headline names contacts across bands when entities are few`() {
        assertThat(milestoneHeadline(bandsWorked = 2, dxccEntities = 2))
            .isEqualTo(MilestoneHeadline.CONTACTS_ACROSS_BANDS)
    }

    @Test
    fun `spelled count uses the word list then falls back to digits`() {
        val words = listOf("zero", "one", "two", "three")
        assertThat(spelledCount(3, words)).isEqualTo("three")
        assertThat(spelledCount(0, words)).isEqualTo("zero")
        assertThat(spelledCount(4, words)).isEqualTo("4")
        assertThat(spelledCount(-1, words)).isEqualTo("-1")
    }

    @Test
    fun `feedback can only be sent once something is typed`() {
        assertThat(canSendRatePromptFeedback("")).isFalse()
        assertThat(canSendRatePromptFeedback("   \n")).isFalse()
        assertThat(canSendRatePromptFeedback("Decodes stall on 40m")).isTrue()
    }
}
