package radio.ks3ckc.ft8af.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.voice.VoiceCommandParser
import com.k1af.ft8af.voice.VoicePhrases
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextPrimary

/**
 * Visual/interaction state of the voice-command push-to-talk button.
 * Extracted as a pure function so the gating is unit-testable (the
 * Composable below is a thin wrapper).
 */
internal enum class VoiceButtonState { HIDDEN, READY, BLOCKED_MIC }

/**
 * @param commandsEnabled the voiceCommandsEnabled user setting
 * @param phoneMicInUse   FT8 RX holds an Android audio-capture session
 *                        (MainViewModel.isPhoneMicInUse) — a SpeechRecognizer
 *                        would fight it, so the button is shown disabled with
 *                        an explanatory toast instead of silently failing
 */
internal fun voiceButtonState(
    commandsEnabled: Boolean,
    phoneMicInUse: Boolean,
): VoiceButtonState =
    when {
        !commandsEnabled -> VoiceButtonState.HIDDEN
        phoneMicInUse -> VoiceButtonState.BLOCKED_MIC
        else -> VoiceButtonState.READY
    }

/**
 * Toast string resource for a recognizer error code, or null for codes that
 * must stay silent: ERROR_CLIENT is what the recognizer emits after a
 * user-initiated cancel() (second tap), and toasting it would make a
 * deliberate cancel look like a failure. Pure so the mapping is testable.
 */
internal fun voiceErrorToastRes(errorCode: Int): Int? =
    when (errorCode) {
        SpeechRecognizer.ERROR_CLIENT -> null
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> R.string.voice_not_understood
        else -> R.string.voice_recognizer_error
    }

/**
 * The spoken confirmation for an executed voice command, or null when there
 * is nothing to echo (UNKNOWN, or ANSWER with no candidate — those get toasts
 * instead). Pure; the phrases themselves live in [VoicePhrases].
 */
internal fun echoPhraseFor(
    command: VoiceCommandParser.Command,
    answeredCallsign: String?,
): String? =
    when (command) {
        VoiceCommandParser.Command.CALL_CQ -> VoicePhrases.echoCallingCq()
        VoiceCommandParser.Command.STOP -> VoicePhrases.echoStopping()
        VoiceCommandParser.Command.SKIP -> VoicePhrases.echoSkipping()
        VoiceCommandParser.Command.LOG -> VoicePhrases.echoLogged()
        VoiceCommandParser.Command.ANSWER ->
            answeredCallsign?.let { VoicePhrases.echoAnswering(it) }
        VoiceCommandParser.Command.UNKNOWN -> null
    }

/**
 * Push-to-talk mic button for one-shot voice commands ("answer", "call CQ",
 * "stop", "skip", "log it"). NOT always-listening: one tap = one recognition
 * pass, on-device preferred. Visible only when the voice-commands setting is
 * on; functionally disabled (tap explains why) while FT8 RX is capturing via
 * the phone-mic AudioRecord path — v1 never pauses the capture chain.
 */
@Composable
fun VoiceCommandButton(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Recorder run-state, READ below so this composable subscribes and
    // re-evaluates the mic gate when recording starts/stops. Source changes
    // that don't flip the run-state (mic <-> LAN) are picked up on the next
    // ambient recomposition (slot timer / TX state churn in FT8AFApp).
    val recorderRunning by mainViewModel.mutableHamRecordIsRunning.observeAsState(false)

    // Observable mirror of the setting — a plain static read here would only be
    // re-evaluated on unrelated recompositions, so the button wouldn't appear or
    // disappear until app restart when the toggle flips.
    val commandsEnabled by GeneralVariables.mutableVoiceCommandsEnabled
        .observeAsState(GeneralVariables.voiceCommandsEnabled)

    val state = voiceButtonState(
        commandsEnabled = commandsEnabled == true,
        // isPhoneMicInUse() already includes the running check; the LiveData
        // read is ANDed in to make this a tracked snapshot dependency.
        phoneMicInUse = recorderRunning == true && mainViewModel.isPhoneMicInUse(),
    )
    if (state == VoiceButtonState.HIDDEN) return

    var listening by remember { mutableStateOf(false) }
    // One recognizer per composition, created lazily on first tap.
    val recognizerHolder = remember { arrayOfNulls<SpeechRecognizer>(1) }
    DisposableEffect(Unit) {
        onDispose {
            recognizerHolder[0]?.destroy()
            recognizerHolder[0] = null
        }
    }

    val readyDesc = stringResource(R.string.voice_button_desc)
    val busyDesc = stringResource(R.string.voice_button_mic_busy_desc)
    val desc = if (state == VoiceButtonState.BLOCKED_MIC) busyDesc else readyDesc

    val tint = when {
        listening -> Accent
        state == VoiceButtonState.BLOCKED_MIC -> TextFaint
        else -> TextPrimary
    }

    IconButton(
        onClick = {
            when {
                state == VoiceButtonState.BLOCKED_MIC ->
                    Toast
                        .makeText(context, context.getString(R.string.voice_mic_busy), Toast.LENGTH_LONG)
                        .show()
                listening -> {
                    // Second tap cancels the in-flight listen.
                    recognizerHolder[0]?.cancel()
                    listening = false
                }
                else -> listening = startVoiceRecognition(
                    context = context,
                    recognizerHolder = recognizerHolder,
                    onDone = { transcription ->
                        listening = false
                        handleVoiceResult(mainViewModel, context, transcription)
                    },
                    onError = { errorCode ->
                        listening = false
                        val msgRes = voiceErrorToastRes(errorCode)
                        if (msgRes != null) {
                            Toast
                                .makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                )
            }
        },
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(BgSurface2.copy(alpha = 0.92f))
            .border(1.dp, if (listening) Accent else Border, CircleShape)
            .semantics { contentDescription = desc },
    ) {
        FT8AFIcons.Mic(color = tint, size = 22.dp)
    }
}

/**
 * Kick off a single on-device recognition pass. Returns true when listening
 * actually started (recognition available and recognizer created).
 */
private fun startVoiceRecognition(
    context: Context,
    recognizerHolder: Array<SpeechRecognizer?>,
    onDone: (String?) -> Unit,
    onError: (Int) -> Unit,
): Boolean {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        Toast
            .makeText(context, context.getString(R.string.voice_recognizer_unavailable), Toast.LENGTH_LONG)
            .show()
        return false
    }
    val recognizer = recognizerHolder[0] ?: SpeechRecognizer.createSpeechRecognizer(context)
        .also { recognizerHolder[0] = it }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val top = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            onDone(top)
        }

        override fun onError(error: Int) = onError(error)

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) {}
    })

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Small fixed grammar — the on-device recognizer is plenty, and no
        // audio leaves the phone.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    recognizer.startListening(intent)
    Toast
        .makeText(context, context.getString(R.string.voice_listening), Toast.LENGTH_SHORT)
        .show()
    return true
}

/** Parse the transcription, run the mapped action, and give feedback. */
private fun handleVoiceResult(
    mainViewModel: MainViewModel,
    context: Context,
    transcription: String?,
) {
    val command = VoiceCommandParser.parse(transcription)
    GeneralVariables.fileLog("VOICE command [" + (transcription ?: "") + "] -> " + command)

    var answeredCallsign: String? = null
    when (command) {
        VoiceCommandParser.Command.ANSWER -> {
            answeredCallsign = mainViewModel.voiceAnswerBestCaller()
            if (answeredCallsign == null) {
                Toast
                    .makeText(context, context.getString(R.string.voice_no_caller), Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }
        VoiceCommandParser.Command.CALL_CQ -> {
            if (GeneralVariables.myCallsign.isNullOrEmpty()) {
                Toast
                    .makeText(context, context.getString(R.string.app_set_callsign_first), Toast.LENGTH_SHORT)
                    .show()
                return
            }
            // The canonical CQ-start sequence (same as the TX strip's CQ button).
            mainViewModel.ft8TransmitSignal.setTransmitFreeText(false)
            mainViewModel.ft8TransmitSignal.userResetToCQ()
            mainViewModel.ft8TransmitSignal.setActivated(true)
            GeneralVariables.resetLaunchSupervision()
        }
        VoiceCommandParser.Command.STOP ->
            mainViewModel.ft8TransmitSignal.setActivated(false)
        VoiceCommandParser.Command.SKIP ->
            mainViewModel.ft8TransmitSignal.userResetToCQ()
        VoiceCommandParser.Command.LOG ->
            mainViewModel.ft8TransmitSignal.forceLogAndMoveOn()
        VoiceCommandParser.Command.UNKNOWN -> {
            Toast
                .makeText(context, context.getString(R.string.voice_not_understood), Toast.LENGTH_SHORT)
                .show()
            return
        }
    }

    // Spoken confirmation. Unique utterance id — echoes are never deduped —
    // and the announcer's transmit gate silently drops it mid-TX.
    val echo = echoPhraseFor(command, answeredCallsign)
    if (echo != null) {
        mainViewModel.voiceAnnouncer.speak(echo, "VECHO:" + System.nanoTime())
    }
}
