package radio.ks3ckc.ft8af.ui.components

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import com.k1af.ft8af.voice.VoiceCommandParser
import org.junit.Test

/**
 * Pure-JVM tests for the voice-command button's extracted decision logic
 * (the Composable itself is a thin wrapper): visibility/enabled gating and
 * the spoken echo mapping.
 */
class VoiceCommandButtonLogicTest {
    // ---- voiceButtonState -------------------------------------------------

    @Test
    fun hiddenWheneverSettingIsOff() {
        assertThat(voiceButtonState(commandsEnabled = false, phoneMicInUse = false))
            .isEqualTo(VoiceButtonState.HIDDEN)
        // Setting off wins even when the mic would also be busy.
        assertThat(voiceButtonState(commandsEnabled = false, phoneMicInUse = true))
            .isEqualTo(VoiceButtonState.HIDDEN)
    }

    @Test
    fun blockedWhilePhoneMicCapturesFt8Audio() {
        assertThat(voiceButtonState(commandsEnabled = true, phoneMicInUse = true))
            .isEqualTo(VoiceButtonState.BLOCKED_MIC)
    }

    @Test
    fun readyWhenEnabledAndMicFree() {
        assertThat(voiceButtonState(commandsEnabled = true, phoneMicInUse = false))
            .isEqualTo(VoiceButtonState.READY)
    }

    // ---- echoPhraseFor ------------------------------------------------------

    @Test
    fun echoesForEachCommand() {
        assertThat(echoPhraseFor(VoiceCommandParser.Command.CALL_CQ, null))
            .isEqualTo("Calling CQ")
        assertThat(echoPhraseFor(VoiceCommandParser.Command.STOP, null))
            .isEqualTo("Stopping")
        assertThat(echoPhraseFor(VoiceCommandParser.Command.SKIP, null))
            .isEqualTo("Back to CQ")
        assertThat(echoPhraseFor(VoiceCommandParser.Command.LOG, null))
            .isEqualTo("Logged")
    }

    @Test
    fun answerEchoSpellsTheCallsign() {
        assertThat(echoPhraseFor(VoiceCommandParser.Command.ANSWER, "K1ABC"))
            .isEqualTo("Answering K 1 A B C")
    }

    @Test
    fun answerWithoutCandidateHasNoEcho() {
        assertThat(echoPhraseFor(VoiceCommandParser.Command.ANSWER, null)).isNull()
    }

    @Test
    fun unknownHasNoEcho() {
        assertThat(echoPhraseFor(VoiceCommandParser.Command.UNKNOWN, null)).isNull()
    }

    // ---- recognizer error -> toast mapping ---------------------------------

    @Test
    fun errorClientIsSilent() {
        // ERROR_CLIENT is what cancel() (second tap) produces — a deliberate
        // user action must not be toasted as a failure.
        assertThat(voiceErrorToastRes(SpeechRecognizer.ERROR_CLIENT)).isNull()
    }

    @Test
    fun noMatchAndTimeoutToastNotUnderstood() {
        assertThat(voiceErrorToastRes(SpeechRecognizer.ERROR_NO_MATCH))
            .isEqualTo(R.string.voice_not_understood)
        assertThat(voiceErrorToastRes(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            .isEqualTo(R.string.voice_not_understood)
    }

    @Test
    fun otherErrorsToastGenericMessage() {
        assertThat(voiceErrorToastRes(SpeechRecognizer.ERROR_NETWORK))
            .isEqualTo(R.string.voice_recognizer_error)
        assertThat(voiceErrorToastRes(SpeechRecognizer.ERROR_AUDIO))
            .isEqualTo(R.string.voice_recognizer_error)
    }
}
