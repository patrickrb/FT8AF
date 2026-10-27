package com.k1af.ft8af.flows

import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.flows.Flows.waitForMainScreen
import com.k1af.ft8af.timer.UtcTimer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flow 3: a recorded 15 s FT8 slot fed through the real native decoder fills the
 * decode list on screen.
 *
 * The recording is one of the native decoder's own fixtures
 * (`src/main/cpp/ft8af_glue/testdata/ft8`, packaged into the test APK's assets),
 * with its jt9 reference decodes in the matching `.txt`. The samples go in
 * through [com.k1af.ft8af.ft8listener.FT8SignalListener.decodeFt8], the same
 * entry the live recorder uses, so everything from the JNI decoder to
 * DecodeScreen is the shipping code path; only the microphone is swapped out.
 */
@RunWith(AndroidJUnit4::class)
class DecodeWavFlowTest {

    private val compose = Flows.composeRule()

    @get:Rule
    val rules: TestRule = Flows.chain(compose)

    @Test
    fun recordedWav_fillsDecodeList() {
        compose.waitForMainScreen()
        val vm = MainViewModel.peekInstance()!!
        val samples = readWavAsFloats("$FIXTURE.wav")
        val truth = readTruth("$FIXTURE.txt")
        assertThat(samples.size).isEqualTo(15 * 12_000)

        // Start from an empty list with the default "All" filter, so whatever
        // shows up below came from this recording.
        Flows.onMain {
            vm.decodeFilter.value = "All"
            vm.clearFt8MessageList()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No signals decoded").fetchSemanticsNodes().isNotEmpty()
        }

        vm.ft8SignalListener.decodeFt8(UtcTimer.getSystemTime(), samples)

        // The fast pass delivers first and the deep passes add to it, so wait
        // for enough of jt9's reference decodes rather than a fixed delay.
        var matched = emptySet<String>()
        compose.waitUntil(DECODE_TIMEOUT_MS) {
            matched = decodedTexts(vm).intersect(truth)
            matched.size >= MIN_MATCHED
        }

        // The rows are on screen: the empty state is gone and at least one
        // decoded sender's callsign is rendered in the list.
        val senders = vm.mutableFt8MessageList.value.orEmpty().mapNotNull { it.callsignFrom }.toSet()
        compose.waitUntil(5_000) {
            senders.any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
        assertThat(compose.onAllNodesWithText("No signals decoded").fetchSemanticsNodes()).isEmpty()
        assertThat(matched.size).isAtLeast(MIN_MATCHED)

        Flows.screenshot("03_decode_wav_${FIXTURE}")
    }

    private fun decodedTexts(vm: MainViewModel): Set<String> =
        vm.mutableFt8MessageList.value.orEmpty().map { normalize(it.messageText) }.toSet()

    /** 16-bit mono PCM WAV -> floats in [-1, 1], as the decoder expects. */
    private fun readWavAsFloats(asset: String): FloatArray {
        val bytes = testAssets().open(asset).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Walk the RIFF chunks to "data" instead of assuming a 44-byte header.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            require(size >= 0 && pos + 8 + size <= bytes.size) { "$asset: bad size $size for chunk '$id'" }
            if (id == "data") {
                val count = size / 2
                return FloatArray(count) { i -> buf.getShort(pos + 8 + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("$asset has no data chunk")
    }

    /** Message texts from a jt9 reference file, e.g. "CQ IK4LZH JN54" (country column dropped). */
    private fun readTruth(asset: String): Set<String> =
        testAssets().open(asset).bufferedReader().readLines()
            .mapNotNull { line -> line.substringAfter('~', "").trim().takeIf { it.isNotEmpty() } }
            .map { normalize(it.split(Regex("\\s{2,}")).first()) }
            .toSet()

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

    private companion object {
        const val FIXTURE = "websdr_test1"

        /**
         * jt9 lists 18 decodes for this slot. Ask for a solid share of them
         * without tying the flow to the exact decoder tuning; decode quality has
         * its own floors in the native host tests.
         */
        const val MIN_MATCHED = 8

        const val DECODE_TIMEOUT_MS = 60_000L
    }
}
