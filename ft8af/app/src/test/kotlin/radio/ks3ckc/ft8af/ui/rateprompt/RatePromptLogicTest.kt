package radio.ks3ckc.ft8af.ui.rateprompt

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
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

    // ---- star routing ----

    @Test
    fun `four and five stars go to Play, one to three to feedback`() {
        assertThat(ratePromptStepForStars(5)).isEqualTo(RatePromptStep.PLAY)
        assertThat(ratePromptStepForStars(4)).isEqualTo(RatePromptStep.PLAY)
        assertThat(ratePromptStepForStars(3)).isEqualTo(RatePromptStep.FEEDBACK)
        assertThat(ratePromptStepForStars(1)).isEqualTo(RatePromptStep.FEEDBACK)
    }

    @Test
    fun `star values outside one to five are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ratePromptStepForStars(0) }
        assertThrows(IllegalArgumentException::class.java) { ratePromptStepForStars(6) }
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
    fun `swipe or back on ask and Play steps is a decline`() {
        val expected = RatePromptState().afterDecline(qsoCount = 7)
        assertThat(RatePromptState().afterDismiss(RatePromptStep.ASK, qsoCount = 7)).isEqualTo(expected)
        assertThat(RatePromptState().afterDismiss(RatePromptStep.PLAY, qsoCount = 7)).isEqualTo(expected)
    }

    @Test
    fun `swipe or back on the feedback step counts as skip`() {
        val next = RatePromptState().afterDismiss(RatePromptStep.FEEDBACK, qsoCount = 7)
        assertThat(next).isEqualTo(RatePromptState(resolved = true))
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
