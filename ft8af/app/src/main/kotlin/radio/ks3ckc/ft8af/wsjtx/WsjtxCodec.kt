package radio.ks3ckc.ft8af.wsjtx

import androidx.annotation.VisibleForTesting
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Byte-exact (de)serialization of the WSJT-X UDP message protocol.
 *
 * WSJT-X broadcasts a documented binary protocol over UDP that GridTracker,
 * JTAlert, N1MM+, Log4OM etc. consume, and accepts a few request messages so
 * those apps can drive it. This object speaks that exact wire format so FT8AF is
 * a drop-in source/sink.
 *
 * The framing is a bespoke big-endian Qt-`QDataStream` layout (NOT JSON), so
 * byte-for-byte correctness is the whole game — every encoder here is pinned by
 * the same golden vectors as the desktop (Rust) codec (see `docs/wsjtx-udp.md`
 * and `WsjtxCodecTest`). Pure, no I/O — [WsjtxUdpService] owns the socket.
 *
 * Wire primitives (all big-endian): u32/u64/i32/i64/f64, bool(1 byte); string =
 * u32 byte-length (0xFFFFFFFF = null) then UTF-8 bytes (empty = length 0);
 * datetime (UTC) = i64 Julian day, u32 ms-since-midnight, u8=1. Every datagram:
 * u32 magic 0xADBCCBDA, u32 schema, u32 type, string id, then type fields.
 */
object WsjtxCodec {
    @VisibleForTesting internal val MAGIC: Int = 0xADBCCBDA.toInt()
    const val SCHEMA: Int = 3
    const val CLIENT_ID: String = "FT8AF"

    // Message type numbers.
    const val T_HEARTBEAT = 0
    const val T_STATUS = 1
    const val T_DECODE = 2
    const val T_CLEAR = 3
    const val T_REPLY = 4
    const val T_QSO_LOGGED = 5
    const val T_REPLAY = 7
    const val T_HALT_TX = 8
    const val T_FREE_TEXT = 9
    const val T_LOGGED_ADIF = 12

    // --- outbound field carriers --------------------------------------------

    data class Status(
        val dialFreqHz: Long,
        val mode: String,
        val dxCall: String,
        val report: String,
        val txMode: String,
        val txEnabled: Boolean,
        val transmitting: Boolean,
        val decoding: Boolean,
        val rxDf: Int,
        val txDf: Int,
        val deCall: String,
        val deGrid: String,
        val dxGrid: String,
        val txWatchdog: Boolean,
        val subMode: String,
        val fastMode: Boolean,
        val specialOpMode: Int,
        val freqTolerance: Int,
        val trPeriod: Int,
        val configName: String,
        val txMessage: String,
    )

    data class Decode(
        val isNew: Boolean,
        val timeMs: Int,
        val snr: Int,
        val deltaTime: Double,
        val deltaFreq: Int,
        val mode: String,
        val message: String,
        val lowConfidence: Boolean,
        val offAir: Boolean,
    )

    /** A UTC instant as WSJT-X serializes it. */
    data class DateTime(val julianDay: Long, val msOfDay: Int) {
        companion object {
            /** Build from ADIF `YYYYMMDD` + `HHMMSS` (UTC); zeroed on bad input. */
            @JvmStatic
            fun fromAdif(dateYyyymmdd: String?, timeHhmmss: String?): DateTime =
                DateTime(
                    julianDayFromYyyymmdd(dateYyyymmdd) ?: 0L,
                    msOfDayFromHhmmss(timeHhmmss) ?: 0,
                )
        }
    }

    data class QsoLogged(
        val timeOff: DateTime,
        val dxCall: String,
        val dxGrid: String,
        val txFreqHz: Long,
        val mode: String,
        val reportSent: String,
        val reportReceived: String,
        val txPower: String,
        val comments: String,
        val name: String,
        val timeOn: DateTime,
        val operatorCall: String,
        val myCall: String,
        val myGrid: String,
        val exchangeSent: String,
        val exchangeReceived: String,
        val propMode: String,
    )

    // --- writer -------------------------------------------------------------

    private class Writer {
        val bytes = ByteArrayOutputStream(64)
        private val out = DataOutputStream(bytes)
        fun u8(v: Int) = out.writeByte(v)
        fun bool(v: Boolean) = out.writeByte(if (v) 1 else 0)
        fun i32(v: Int) = out.writeInt(v)
        fun i64(v: Long) = out.writeLong(v)
        fun f64(v: Double) = out.writeDouble(v)
        fun string(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size)
            out.write(b)
        }
        fun datetime(dt: DateTime) {
            out.writeLong(dt.julianDay)
            out.writeInt(dt.msOfDay)
            out.writeByte(1) // Qt::UTC
        }
        fun toByteArray(): ByteArray { out.flush(); return bytes.toByteArray() }
    }

    private fun begin(type: Int): Writer {
        val w = Writer()
        w.i32(MAGIC)
        w.i32(SCHEMA)
        w.i32(type)
        w.string(CLIENT_ID)
        return w
    }

    // --- outbound builders --------------------------------------------------

    fun heartbeat(version: String, revision: String): ByteArray {
        val w = begin(T_HEARTBEAT)
        w.i32(SCHEMA) // max schema
        w.string(version)
        w.string(revision)
        return w.toByteArray()
    }

    fun status(s: Status): ByteArray {
        val w = begin(T_STATUS)
        w.i64(s.dialFreqHz)
        w.string(s.mode)
        w.string(s.dxCall)
        w.string(s.report)
        w.string(s.txMode)
        w.bool(s.txEnabled)
        w.bool(s.transmitting)
        w.bool(s.decoding)
        w.i32(s.rxDf)
        w.i32(s.txDf)
        w.string(s.deCall)
        w.string(s.deGrid)
        w.string(s.dxGrid)
        w.bool(s.txWatchdog)
        w.string(s.subMode)
        w.bool(s.fastMode)
        w.u8(s.specialOpMode)
        w.i32(s.freqTolerance)
        w.i32(s.trPeriod)
        w.string(s.configName)
        w.string(s.txMessage)
        return w.toByteArray()
    }

    fun decode(d: Decode): ByteArray {
        val w = begin(T_DECODE)
        w.bool(d.isNew)
        w.i32(d.timeMs)
        w.i32(d.snr)
        w.f64(d.deltaTime)
        w.i32(d.deltaFreq)
        w.string(d.mode)
        w.string(d.message)
        w.bool(d.lowConfidence)
        w.bool(d.offAir)
        return w.toByteArray()
    }

    fun clear(): ByteArray = begin(T_CLEAR).toByteArray()

    fun qsoLogged(q: QsoLogged): ByteArray {
        val w = begin(T_QSO_LOGGED)
        w.datetime(q.timeOff)
        w.string(q.dxCall)
        w.string(q.dxGrid)
        w.i64(q.txFreqHz)
        w.string(q.mode)
        w.string(q.reportSent)
        w.string(q.reportReceived)
        w.string(q.txPower)
        w.string(q.comments)
        w.string(q.name)
        w.datetime(q.timeOn)
        w.string(q.operatorCall)
        w.string(q.myCall)
        w.string(q.myGrid)
        w.string(q.exchangeSent)
        w.string(q.exchangeReceived)
        w.string(q.propMode)
        return w.toByteArray()
    }

    fun loggedAdif(adif: String): ByteArray {
        val w = begin(T_LOGGED_ADIF)
        w.string(adif)
        return w.toByteArray()
    }

    // --- inbound ------------------------------------------------------------

    sealed class Inbound {
        /**
         * A Reply request. [deltaFreq] is the audio frequency (Hz) the companion asked us
         * to answer on — the caller should honor it so we key up on the requested tone
         * rather than whatever the current TX frequency happens to be (mirrors the iOS
         * port). It is the WSJT-X message's `df` field; 0 means "unspecified".
         */
        data class Reply(val call: String, val grid: String, val snr: Int, val deltaFreq: Int) :
            Inbound()
        object Replay : Inbound()
        data class HaltTx(val autoOnly: Boolean) : Inbound()
        data class FreeText(val text: String, val send: Boolean) : Inbound()
    }

    /** Bounds-checked big-endian reader; every read returns null past the end. */
    private class Reader(private val b: ByteArray) {
        var pos = 0
        private fun take(n: Int): Int? {
            // Reject a negative length (e.g. a hostile/garbled 32-bit string length) and any
            // n that overruns the buffer. Comparing against the remaining bytes (b.size - pos,
            // always >= 0 since pos only advances through validated takes) avoids the
            // pos + n integer overflow a huge n would cause with a naive `pos + n > b.size`.
            if (n < 0 || n > b.size - pos) return null
            val start = pos; pos += n; return start
        }
        fun u8(): Int? { val i = take(1) ?: return null; return b[i].toInt() and 0xFF }
        fun bool(): Boolean? { val v = u8() ?: return null; return v != 0 }
        fun i32(): Int? {
            val i = take(4) ?: return null
            return ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        }
        fun f64(): Double? {
            val i = take(8) ?: return null
            var bits = 0L
            for (k in 0 until 8) bits = (bits shl 8) or (b[i + k].toLong() and 0xFF)
            return Double.fromBits(bits)
        }
        fun string(): String? {
            val len = i32() ?: return null
            if (len == -1) return "" // 0xFFFFFFFF null
            val i = take(len) ?: return null
            return String(b, i, len, Charsets.UTF_8)
        }
    }

    /**
     * Parse an inbound datagram into intent. Returns null for anything we don't
     * act on (bad magic, unknown/outbound-only type, truncated buffer).
     */
    fun parseInbound(buf: ByteArray): Inbound? {
        val r = Reader(buf)
        if (r.i32() != MAGIC) return null
        r.i32() ?: return null // schema
        val type = r.i32() ?: return null
        r.string() ?: return null // id
        return when (type) {
            T_REPLY -> {
                r.i32() ?: return null // time
                val snr = r.i32() ?: return null
                r.f64() ?: return null // dt
                val deltaFreq = r.i32() ?: return null // df: the requested audio freq (Hz)
                r.string() ?: return null // mode
                val message = r.string() ?: return null
                val target = parseReplyTarget(message) ?: return null
                Inbound.Reply(target.first, target.second, snr, deltaFreq)
            }
            T_REPLAY -> Inbound.Replay
            // Reject a truncated packet rather than defaulting the trailing bool: a malformed
            // datagram must not trigger an unintended halt or transmit when accepting requests.
            T_HALT_TX -> Inbound.HaltTx(r.bool() ?: return null)
            T_FREE_TEXT -> {
                val text = r.string() ?: return null
                Inbound.FreeText(text, r.bool() ?: return null)
            }
            else -> null
        }
    }

    /**
     * From a decoded message line, work out which station a Reply should call and
     * their grid. Handles `TO FROM ...` and CQ lines with an optional directive
     * (`CQ FROM GRID`, `CQ DX FROM GRID`, `CQ POTA FROM GRID`).
     */
    @VisibleForTesting
    internal fun parseReplyTarget(message: String): Pair<String, String>? {
        val tokens = message.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val callIdx = if (tokens[0].equals("CQ", ignoreCase = true)) {
            when {
                tokens.size < 2 -> return null
                isCallsign(tokens[1]) -> 1
                else -> 2
            }
        } else {
            1
        }
        val call = tokens.getOrNull(callIdx) ?: return null
        if (!isCallsign(call)) return null
        val grid = tokens.drop(callIdx + 1).firstOrNull { isGrid4(it) }?.uppercase() ?: ""
        return Pair(call.uppercase(), grid)
    }

    private fun isCallsign(t: String): Boolean {
        val core = t.substringBefore('/')
        val hasAlpha = core.any { it in 'A'..'Z' || it in 'a'..'z' }
        val hasDigit = core.any { it.isDigit() }
        val okChars = t.all { it.isLetterOrDigit() || it == '/' }
        return hasAlpha && hasDigit && okChars && !isGrid4(t)
    }

    private fun isGrid4(t: String): Boolean {
        if (t.length != 4) return false
        val a = t[0].uppercaseChar(); val b = t[1].uppercaseChar()
        return a in 'A'..'R' && b in 'A'..'R' && t[2].isDigit() && t[3].isDigit()
    }

    // --- date/time helpers --------------------------------------------------

    /** Julian Day Number for a UTC `YYYYMMDD` date (matches Qt's toJulianDay). */
    @VisibleForTesting
    internal fun julianDayFromYyyymmdd(s: String?): Long? {
        if (s == null || s.length != 8 || !s.all { it.isDigit() }) return null
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(4, 6).toInt()
            val day = s.substring(6, 8).toInt()
            // epochDay 0 = 1970-01-01 = JDN 2440588.
            java.time.LocalDate.of(year, month, day).toEpochDay() + 2_440_588L
        } catch (e: Exception) {
            null
        }
    }

    /** Milliseconds since midnight for a UTC `HHMMSS` string. */
    @VisibleForTesting
    internal fun msOfDayFromHhmmss(s: String?): Int? {
        if (s == null || s.length != 6 || !s.all { it.isDigit() }) return null
        val h = s.substring(0, 2).toInt()
        val m = s.substring(2, 4).toInt()
        val sec = s.substring(4, 6).toInt()
        if (h > 23 || m > 59 || sec > 60) return null
        return ((h * 3600) + (m * 60) + sec) * 1000
    }
}
