package radio.ks3ckc.ft8af.wsjtx

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * Byte-exact tests for the WSJT-X UDP codec. The golden hex here matches the
 * desktop (Rust) codec's vectors in `desktop/src-tauri/src/udp/codec.rs` and
 * `docs/wsjtx-udp.md`, so the two encoders are provably identical.
 */
class WsjtxCodecTest {

    /** Minimal big-endian reader mirroring the (private) codec reader. */
    private class R(bytes: ByteArray) {
        val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun i32() = bb.int
        fun i64() = bb.long
        fun f64() = bb.double
        fun u8() = bb.get().toInt() and 0xFF
        fun bool() = bb.get().toInt() != 0
        fun str(): String {
            val len = bb.int
            val b = ByteArray(len)
            bb.get(b)
            return String(b, Charsets.UTF_8)
        }
        fun remaining() = bb.remaining()
    }

    // Read a header, asserting magic/schema/id and returning the type; the reader
    // is left positioned at the first payload field.
    private fun readHeader(r: R): Int {
        assertThat(r.i32()).isEqualTo(WsjtxCodec.MAGIC)
        assertThat(r.i32()).isEqualTo(WsjtxCodec.SCHEMA)
        val type = r.i32()
        assertThat(r.str()).isEqualTo(WsjtxCodec.CLIENT_ID)
        return type
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test
    fun clearHeaderBytesAreExact() {
        assertThat(hex(WsjtxCodec.clear()))
            .isEqualTo("ad bc cb da 00 00 00 03 00 00 00 03 00 00 00 05 46 54 38 41 46")
    }

    @Test
    fun heartbeatBytesMatchGolden() {
        assertThat(hex(WsjtxCodec.heartbeat("0.1.0", "")))
            .isEqualTo(
                "ad bc cb da 00 00 00 03 00 00 00 00 00 00 00 05 46 54 38 41 46 " +
                    "00 00 00 03 00 00 00 05 30 2e 31 2e 30 00 00 00 00",
            )
    }

    @Test
    fun decodeBytesMatchGolden() {
        val d = WsjtxCodec.Decode(
            isNew = true, timeMs = 47_493_000, snr = -7, deltaTime = 0.2,
            deltaFreq = 1500, mode = "FT8", message = "CQ K1ABC FN42",
            lowConfidence = false, offAir = false,
        )
        assertThat(hex(WsjtxCodec.decode(d))).isEqualTo(
            "ad bc cb da 00 00 00 03 00 00 00 02 00 00 00 05 46 54 38 41 46 " +
                "01 02 d4 af 88 ff ff ff f9 3f c9 99 99 99 99 99 9a 00 00 05 dc " +
                "00 00 00 03 46 54 38 00 00 00 0d 43 51 20 4b 31 41 42 43 20 46 4e 34 32 00 00",
        )
    }

    @Test
    fun statusRoundtripsAllFields() {
        val s = WsjtxCodec.Status(
            dialFreqHz = 14_074_000, mode = "FT8", dxCall = "K1ABC", report = "-07",
            txMode = "FT8", txEnabled = true, transmitting = false, decoding = true,
            rxDf = 1500, txDf = 1500, deCall = "K0XYZ", deGrid = "EN37", dxGrid = "FN42",
            txWatchdog = false, subMode = "", fastMode = false, specialOpMode = 0,
            freqTolerance = -1, trPeriod = 15, configName = "Default",
            txMessage = "K1ABC K0XYZ EN37",
        )
        val r = R(WsjtxCodec.status(s))
        assertThat(readHeader(r)).isEqualTo(WsjtxCodec.T_STATUS)
        assertThat(r.i64()).isEqualTo(14_074_000L)
        assertThat(r.str()).isEqualTo("FT8")
        assertThat(r.str()).isEqualTo("K1ABC")
        assertThat(r.str()).isEqualTo("-07")
        assertThat(r.str()).isEqualTo("FT8")
        assertThat(r.bool()).isTrue()
        assertThat(r.bool()).isFalse()
        assertThat(r.bool()).isTrue()
        assertThat(r.i32()).isEqualTo(1500)
        assertThat(r.i32()).isEqualTo(1500)
        assertThat(r.str()).isEqualTo("K0XYZ")
        assertThat(r.str()).isEqualTo("EN37")
        assertThat(r.str()).isEqualTo("FN42")
        assertThat(r.bool()).isFalse()
        assertThat(r.str()).isEqualTo("")
        assertThat(r.bool()).isFalse()
        assertThat(r.u8()).isEqualTo(0)
        assertThat(r.i32()).isEqualTo(-1) // 0xFFFFFFFF freq tolerance
        assertThat(r.i32()).isEqualTo(15)
        assertThat(r.str()).isEqualTo("Default")
        assertThat(r.str()).isEqualTo("K1ABC K0XYZ EN37")
        assertThat(r.remaining()).isEqualTo(0)
    }

    @Test
    fun qsoLoggedRoundtripsWithDatetime() {
        val on = WsjtxCodec.DateTime.fromAdif("20260712", "134500")
        val off = WsjtxCodec.DateTime.fromAdif("20260712", "134615")
        val q = WsjtxCodec.QsoLogged(
            timeOff = off, dxCall = "K1ABC", dxGrid = "FN42", txFreqHz = 14_075_500,
            mode = "FT8", reportSent = "-07", reportReceived = "-12", txPower = "",
            comments = "", name = "", timeOn = on, operatorCall = "K0XYZ",
            myCall = "K0XYZ", myGrid = "EN37", exchangeSent = "", exchangeReceived = "",
            propMode = "",
        )
        val r = R(WsjtxCodec.qsoLogged(q))
        assertThat(readHeader(r)).isEqualTo(WsjtxCodec.T_QSO_LOGGED)
        assertThat(r.i64()).isEqualTo(off.julianDay)
        assertThat(r.i32()).isEqualTo(off.msOfDay)
        assertThat(r.u8()).isEqualTo(1) // UTC spec
        assertThat(r.str()).isEqualTo("K1ABC")
        assertThat(r.str()).isEqualTo("FN42")
        assertThat(r.i64()).isEqualTo(14_075_500L)
        assertThat(r.str()).isEqualTo("FT8")
        assertThat(r.str()).isEqualTo("-07")
        assertThat(r.str()).isEqualTo("-12")
        assertThat(r.str()).isEqualTo("") // tx power
        assertThat(r.str()).isEqualTo("") // comments
        assertThat(r.str()).isEqualTo("") // name
        assertThat(r.i64()).isEqualTo(on.julianDay)
        assertThat(r.i32()).isEqualTo(on.msOfDay)
        assertThat(r.u8()).isEqualTo(1)
        assertThat(r.str()).isEqualTo("K0XYZ") // operator
        assertThat(r.str()).isEqualTo("K0XYZ") // my call
        assertThat(r.str()).isEqualTo("EN37")  // my grid
        assertThat(r.str()).isEqualTo("")
        assertThat(r.str()).isEqualTo("")
        assertThat(r.str()).isEqualTo("")
        assertThat(r.remaining()).isEqualTo(0)
    }

    @Test
    fun loggedAdifWrapsString() {
        val r = R(WsjtxCodec.loggedAdif("<call:5>K1ABC <eor>"))
        assertThat(readHeader(r)).isEqualTo(WsjtxCodec.T_LOGGED_ADIF)
        assertThat(r.str()).isEqualTo("<call:5>K1ABC <eor>")
        assertThat(r.remaining()).isEqualTo(0)
    }

    // --- date/time goldens ---------------------------------------------------

    @Test
    fun julianDayMatchesKnownValues() {
        assertThat(WsjtxCodec.julianDayFromYyyymmdd("20000101")).isEqualTo(2_451_545L)
        assertThat(WsjtxCodec.julianDayFromYyyymmdd("19700101")).isEqualTo(2_440_588L)
        assertThat(WsjtxCodec.julianDayFromYyyymmdd("bad")).isNull()
        assertThat(WsjtxCodec.julianDayFromYyyymmdd("20261301")).isNull()
    }

    @Test
    fun msOfDayFromTime() {
        assertThat(WsjtxCodec.msOfDayFromHhmmss("000000")).isEqualTo(0)
        assertThat(WsjtxCodec.msOfDayFromHhmmss("134615")).isEqualTo(49_575_000)
        assertThat(WsjtxCodec.msOfDayFromHhmmss("235959")).isEqualTo(86_399_000)
        assertThat(WsjtxCodec.msOfDayFromHhmmss("240000")).isNull()
        assertThat(WsjtxCodec.msOfDayFromHhmmss("12345")).isNull()
    }

    // --- inbound parsing -----------------------------------------------------

    private fun beginInbound(type: Int): ByteBuffer {
        val bb = ByteBuffer.allocate(2048).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(WsjtxCodec.MAGIC)
        bb.putInt(WsjtxCodec.SCHEMA)
        bb.putInt(type)
        val id = WsjtxCodec.CLIENT_ID.toByteArray()
        bb.putInt(id.size); bb.put(id)
        return bb
    }

    private fun putStr(bb: ByteBuffer, s: String) {
        val b = s.toByteArray(); bb.putInt(b.size); bb.put(b)
    }

    private fun sized(bb: ByteBuffer): ByteArray {
        val out = ByteArray(bb.position()); bb.flip(); bb.get(out); return out
    }

    private fun makeReply(message: String, snr: Int, df: Int = 1500): ByteArray {
        val bb = beginInbound(WsjtxCodec.T_REPLY)
        bb.putInt(47_000_000) // time
        bb.putInt(snr)
        bb.putDouble(0.1)     // dt
        bb.putInt(df)         // df
        putStr(bb, "FT8")
        putStr(bb, message)
        bb.put(0)             // low confidence
        bb.put(0)             // modifiers
        return sized(bb)
    }

    @Test
    fun parseReplyPlainCq() {
        assertThat(WsjtxCodec.parseInbound(makeReply("CQ K1ABC FN42", -3)))
            .isEqualTo(WsjtxCodec.Inbound.Reply("K1ABC", "FN42", -3, 1500))
    }

    @Test
    fun parseReplyCqWithDirective() {
        assertThat(WsjtxCodec.parseInbound(makeReply("CQ DX K1ABC FN42", -3)))
            .isEqualTo(WsjtxCodec.Inbound.Reply("K1ABC", "FN42", -3, 1500))
        assertThat(WsjtxCodec.parseInbound(makeReply("CQ POTA W1AW/4 EM70", 5)))
            .isEqualTo(WsjtxCodec.Inbound.Reply("W1AW/4", "EM70", 5, 1500))
    }

    @Test
    fun parseReplyNonCqCallsSender() {
        assertThat(WsjtxCodec.parseInbound(makeReply("K0XYZ K1ABC -12", -12)))
            .isEqualTo(WsjtxCodec.Inbound.Reply("K1ABC", "", -12, 1500))
    }

    @Test
    fun parseReplyCapturesRequestedDeltaFreq() {
        // The `df` field carries the audio frequency the companion asked us to answer on;
        // the reply must surface it (not discard it) so the caller can key up on that tone.
        assertThat(WsjtxCodec.parseInbound(makeReply("CQ K1ABC FN42", -3, df = 2100)))
            .isEqualTo(WsjtxCodec.Inbound.Reply("K1ABC", "FN42", -3, 2100))
    }

    @Test
    fun parseHaltFreeTextReplay() {
        val halt = beginInbound(WsjtxCodec.T_HALT_TX).also { it.put(1) }
        assertThat(WsjtxCodec.parseInbound(sized(halt)))
            .isEqualTo(WsjtxCodec.Inbound.HaltTx(true))

        val free = beginInbound(WsjtxCodec.T_FREE_TEXT)
        putStr(free, "TEST DE FT8AF"); free.put(1)
        assertThat(WsjtxCodec.parseInbound(sized(free)))
            .isEqualTo(WsjtxCodec.Inbound.FreeText("TEST DE FT8AF", true))

        assertThat(WsjtxCodec.parseInbound(sized(beginInbound(WsjtxCodec.T_REPLAY))))
            .isEqualTo(WsjtxCodec.Inbound.Replay)
    }

    @Test
    fun parseRejectsBadAndOutboundOnly() {
        assertThat(WsjtxCodec.parseInbound(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))).isNull()
        // A Status message is outbound-only; not acted on.
        assertThat(WsjtxCodec.parseInbound(sized(beginInbound(WsjtxCodec.T_STATUS)))).isNull()
    }

    @Test
    fun parseReplyShortBufferIsNull() {
        val bb = beginInbound(WsjtxCodec.T_REPLY)
        bb.putInt(1) // time only, truncated
        assertThat(WsjtxCodec.parseInbound(sized(bb))).isNull()
    }

    @Test
    fun parseHaltTxMissingBoolIsRejected() {
        // Truncated HaltTx (no autoOnly byte) must be rejected, not defaulted to false —
        // a malformed packet must never trigger an unintended halt.
        assertThat(WsjtxCodec.parseInbound(sized(beginInbound(WsjtxCodec.T_HALT_TX)))).isNull()
    }

    @Test
    fun parseFreeTextMissingSendFlagIsRejected() {
        // Truncated Free-Text (text present, send byte missing) must be rejected, not
        // defaulted to send=true — a malformed packet must never trigger a transmit.
        val free = beginInbound(WsjtxCodec.T_FREE_TEXT)
        putStr(free, "HELLO") // no trailing send bool
        assertThat(WsjtxCodec.parseInbound(sized(free))).isNull()
    }

    @Test
    fun parseRejectsNegativeStringLength() {
        // A hostile 32-bit string length that is negative (and not the 0xFFFFFFFF null
        // sentinel) must not move the reader backwards / throw — the reader rejects it.
        val bb = beginInbound(WsjtxCodec.T_FREE_TEXT)
        bb.putInt(-2) // negative "text" length (not -1)
        assertThat(WsjtxCodec.parseInbound(sized(bb))).isNull()
    }

    @Test
    fun parseRejectsOverflowStringLength() {
        // A huge positive length must be rejected via the remaining-bytes check, not wrap
        // past the buffer end through pos + n integer overflow.
        val bb = beginInbound(WsjtxCodec.T_FREE_TEXT)
        bb.putInt(Int.MAX_VALUE) // enormous "text" length
        assertThat(WsjtxCodec.parseInbound(sized(bb))).isNull()
    }
}
