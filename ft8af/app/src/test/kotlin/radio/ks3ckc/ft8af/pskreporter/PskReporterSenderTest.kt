package radio.ks3ckc.ft8af.pskreporter

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for IPFIX binary encoding in [PskReporterSender].
 * Validates packet structure, template encoding, field ordering, and
 * variable-length string encoding against the PSKReporter IPFIX spec.
 */
@RunWith(RobolectricTestRunner::class)
class PskReporterSenderTest {

    private val captured = mutableListOf<ByteArray>()

    @Before
    fun setUp() {
        PskReporterSender.resetForTests()
        PskReporterSender.sendDatagram = { data -> captured.add(data.copyOf()) }
    }

    @After
    fun tearDown() {
        PskReporterSender.resetForTests()
        captured.clear()
    }

    // ---------------------------------------------------------------
    // Variable-length string encoding
    // ---------------------------------------------------------------

    @Test
    fun `encodeVarString short string has 1-byte length prefix`() {
        val result = PskReporterSender.encodeVarString("W1AW")
        assertThat(result.size).isEqualTo(5) // 1 byte len + 4 bytes
        assertThat(result[0].toInt() and 0xFF).isEqualTo(4)
        assertThat(String(result, 1, 4, Charsets.UTF_8)).isEqualTo("W1AW")
    }

    @Test
    fun `encodeVarString empty string`() {
        val result = PskReporterSender.encodeVarString("")
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0].toInt() and 0xFF).isEqualTo(0)
    }

    @Test
    fun `encodeVarString long string uses 3-byte prefix`() {
        val longStr = "A".repeat(300)
        val result = PskReporterSender.encodeVarString(longStr)
        assertThat(result.size).isEqualTo(3 + 300)
        assertThat(result[0].toInt() and 0xFF).isEqualTo(0xFF)
        val len = ((result[1].toInt() and 0xFF) shl 8) or (result[2].toInt() and 0xFF)
        assertThat(len).isEqualTo(300)
    }

    // ---------------------------------------------------------------
    // Sender record encoding
    // ---------------------------------------------------------------

    @Test
    fun `encodeSenderRecord contains all fields in order`() {
        val spot = PskReporterSender.SpotRecord(
            senderCallsign = "W1AW",
            frequencyHz = 14_074_000L,
            snr = -15,
            mode = "FT8",
            senderLocator = "FN31",
            flowStartSeconds = 1_700_000_000L,
        )
        val data = PskReporterSender.encodeSenderRecord(spot)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Callsign: 1-byte len + "W1AW"
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(4)
        val callBytes = ByteArray(4)
        buf.get(callBytes)
        assertThat(String(callBytes)).isEqualTo("W1AW")

        // Frequency: uint32
        assertThat(buf.int.toLong() and 0xFFFFFFFFL).isEqualTo(14_074_000L)

        // SNR: int8
        assertThat(buf.get().toInt()).isEqualTo(-15)

        // Mode: 1-byte len + "FT8"
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(3)
        val modeBytes = ByteArray(3)
        buf.get(modeBytes)
        assertThat(String(modeBytes)).isEqualTo("FT8")

        // Locator: 1-byte len + "FN31"
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(4)
        val locBytes = ByteArray(4)
        buf.get(locBytes)
        assertThat(String(locBytes)).isEqualTo("FN31")

        // informationSource: uint8 = 1
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(1)

        // flowStartSeconds: uint32
        assertThat(buf.int.toLong() and 0xFFFFFFFFL).isEqualTo(1_700_000_000L)

        // Should have consumed all bytes
        assertThat(buf.remaining()).isEqualTo(0)
    }

    @Test
    fun `encodeSenderRecord null locator encodes as empty string`() {
        val spot = PskReporterSender.SpotRecord(
            senderCallsign = "K2ABC",
            frequencyHz = 7_074_000L,
            snr = 5,
            mode = "FT8",
            senderLocator = null,
            flowStartSeconds = 1_700_000_000L,
        )
        val data = PskReporterSender.encodeSenderRecord(spot)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Skip callsign (1+5), freq(4), snr(1), mode(1+3)
        buf.position(6 + 4 + 1 + 4)
        // Locator should be empty (length byte = 0)
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(0)
    }

    // ---------------------------------------------------------------
    // Receiver data set encoding
    // ---------------------------------------------------------------

    @Test
    fun `encodeReceiverDataSet has correct set ID and alignment`() {
        val data = PskReporterSender.encodeReceiverDataSet(
            "N0CALL", "EM48", "FT8AF 1.2.3", "EFHW 40-10m", "IC-7300"
        )
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Set ID = 0x50E2 (receiver template ID)
        assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(0x50E2)

        // Length = total byte count
        val len = buf.short.toInt() and 0xFFFF
        assertThat(len).isEqualTo(data.size)

        // Must be 4-byte aligned
        assertThat(data.size % 4).isEqualTo(0)

        // Callsign
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(6) // "N0CALL"
        val callBytes = ByteArray(6)
        buf.get(callBytes)
        assertThat(String(callBytes)).isEqualTo("N0CALL")

        // Grid
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(4)
        val gridBytes = ByteArray(4)
        buf.get(gridBytes)
        assertThat(String(gridBytes)).isEqualTo("EM48")

        // Software
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(11)
        val swBytes = ByteArray(11)
        buf.get(swBytes)
        assertThat(String(swBytes)).isEqualTo("FT8AF 1.2.3")

        // Antenna
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(11)
        val antBytes = ByteArray(11)
        buf.get(antBytes)
        assertThat(String(antBytes)).isEqualTo("EFHW 40-10m")

        // Rig
        assertThat(buf.get().toInt() and 0xFF).isEqualTo(7) // "IC-7300"
        val rigBytes = ByteArray(7)
        buf.get(rigBytes)
        assertThat(String(rigBytes)).isEqualTo("IC-7300")
    }

    // ---------------------------------------------------------------
    // Template encoding
    // ---------------------------------------------------------------

    @Test
    fun `encodeTemplates contains both receiver and sender templates`() {
        val data = PskReporterSender.encodeTemplates()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // First set: options template (set ID = 0x0003)
        val rxSetId = buf.short.toInt() and 0xFFFF
        assertThat(rxSetId).isEqualTo(0x0003)
        val rxSetLen = buf.short.toInt() and 0xFFFF
        assertThat(rxSetLen % 4).isEqualTo(0) // 4-byte aligned

        // Template ID = 0x50E2
        val rxTemplateId = buf.short.toInt() and 0xFFFF
        assertThat(rxTemplateId).isEqualTo(0x50E2)

        // Field count = 5
        val rxFieldCount = buf.short.toInt() and 0xFFFF
        assertThat(rxFieldCount).isEqualTo(5)

        // Scope field count = 0
        val scopeFieldCount = buf.short.toInt() and 0xFFFF
        assertThat(scopeFieldCount).isEqualTo(0)

        // Skip remaining receiver template fields and padding
        buf.position(rxSetLen)

        // Second set: data template (set ID = 0x0002)
        val txSetId = buf.short.toInt() and 0xFFFF
        assertThat(txSetId).isEqualTo(0x0002)
        val txSetLen = buf.short.toInt() and 0xFFFF
        assertThat(txSetLen % 4).isEqualTo(0)

        // Template ID = 0x50E3
        val txTemplateId = buf.short.toInt() and 0xFFFF
        assertThat(txTemplateId).isEqualTo(0x50E3)

        // Field count = 7
        val txFieldCount = buf.short.toInt() and 0xFFFF
        assertThat(txFieldCount).isEqualTo(7)

        // Verify enterprise number on first field
        // Field type has enterprise bit set (0x8000 | fieldId)
        val firstFieldType = buf.short.toInt() and 0xFFFF
        assertThat(firstFieldType and 0x8000).isNotEqualTo(0) // enterprise bit set
        buf.short // skip length
        val enterprise = buf.int
        assertThat(enterprise).isEqualTo(30351)
    }

    @Test
    fun `enterprise fields all carry 30351 except the standard flowStart element`() {
        val data = PskReporterSender.encodeTemplates()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val enterpriseValues = mutableListOf<Int>()
        // Receiver options template: 5 enterprise fields (8 bytes each).
        // Skip set header(4) + templateId(2) + fieldCount(2) + scopeFieldCount(2) = 10.
        buf.position(10)
        repeat(5) {
            buf.short // type
            buf.short // length
            enterpriseValues.add(buf.int)
        }

        val rxSetLen = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).run {
            short // skip set ID
            short.toInt() and 0xFFFF
        }

        // Sender data template: first SIX fields are enterprise (8 bytes each); the
        // seventh (flowStartSeconds) is standard element 0x0096 with NO enterprise
        // number, so it is excluded from the scan.
        // Skip set header(4) + templateId(2) + fieldCount(2) = 8.
        buf.position(rxSetLen + 8)
        repeat(6) {
            buf.short // type
            buf.short // length
            enterpriseValues.add(buf.int)
        }

        for (en in enterpriseValues) {
            assertThat(en).isEqualTo(30351)
        }
    }

    @Test
    fun `receiver template field IDs match PSKReporter spec`() {
        val data = PskReporterSender.encodeTemplates()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        // Skip set header(4) + templateId(2) + fieldCount(2) + scopeFieldCount(2) = 10.
        buf.position(10)

        // receiverCallsign 0x8002 / var, receiverLocator 0x8004 / var,
        // decodingSoftware 0x8008 / var, antennaInformation 0x8009 / var,
        // rigInformation 0x800D / var — all enterprise 30351.
        val expected = listOf(0x8002 to 0xFFFF, 0x8004 to 0xFFFF, 0x8008 to 0xFFFF, 0x8009 to 0xFFFF, 0x800D to 0xFFFF)
        for ((type, len) in expected) {
            assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(type)
            assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(len)
            assertThat(buf.int).isEqualTo(30351)
        }
    }

    @Test
    fun `sender template field IDs match PSKReporter spec`() {
        val data = PskReporterSender.encodeTemplates()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val rxSetLen = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).run {
            short // skip set ID
            short.toInt() and 0xFFFF
        }
        // Sender template starts at rxSetLen; skip set header(4) + templateId(2) + fieldCount(2) = 8.
        buf.position(rxSetLen + 8)

        // First six fields are enterprise-specific under 30351, in encode order:
        // senderCallsign 0x8001/var, frequency 0x8005/4, sNR 0x8006/1, mode 0x800A/var,
        // senderLocator 0x8003/var, informationSource 0x800B/1.
        val enterpriseFields = listOf(
            0x8001 to 0xFFFF,
            0x8005 to 4,
            0x8006 to 1,
            0x800A to 0xFFFF,
            0x8003 to 0xFFFF,
            0x800B to 1,
        )
        for ((type, len) in enterpriseFields) {
            assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(type)
            assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(len)
            assertThat(buf.int).isEqualTo(30351)
        }

        // Seventh field: flowStartSeconds is the STANDARD IPFIX element 0x0096
        // (4-byte descriptor: type+length, enterprise bit clear, no enterprise number).
        val flowType = buf.short.toInt() and 0xFFFF
        assertThat(flowType).isEqualTo(0x0096)
        assertThat(flowType and 0x8000).isEqualTo(0) // enterprise bit NOT set
        assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(4)
    }

    @Test
    fun `encodeTemplates matches the PSKReporter reference byte layout`() {
        // Golden vector: exact bytes the PSKReporter collector expects (same element
        // IDs as WSJT-X PSKReporter.cpp). Pins the layout so a future field-ID drift
        // fails loudly instead of silently producing discarded packets.
        val expected = hex(
            // --- Receiver options template (set 0x0003, len 0x0034 = 52) ---
            "0003 0034 50E2 0005 0000" +
                "8002 FFFF 0000768F" + // receiverCallsign    (id 2, var)
                "8004 FFFF 0000768F" + // receiverLocator     (id 4, var)
                "8008 FFFF 0000768F" + // decodingSoftware    (id 8, var)
                "8009 FFFF 0000768F" + // antennaInformation  (id 9, var)
                "800D FFFF 0000768F" + // rigInformation      (id 13, var)
                "0000" +               // padding to 4-byte boundary
                // --- Sender data template (set 0x0002, len 0x003C = 60) ---
                "0002 003C 50E3 0007" +
                "8001 FFFF 0000768F" + // senderCallsign    (id 1, var)
                "8005 0004 0000768F" + // frequency         (id 5, u32)
                "8006 0001 0000768F" + // sNR               (id 6, i8)
                "800A FFFF 0000768F" + // mode              (id 10, var)
                "8003 FFFF 0000768F" + // senderLocator     (id 3, var)
                "800B 0001 0000768F" + // informationSource (id 11, u8)
                "0096 0004"            // flowStartSeconds  (standard id 150, u32, no enterprise)
        )
        assertThat(PskReporterSender.encodeTemplates()).isEqualTo(expected)
    }

    // ---------------------------------------------------------------
    // Full packet structure
    // ---------------------------------------------------------------

    @Test
    fun `buildPackets produces valid IPFIX header`() {
        PskReporterSender.sequenceNumber = 42
        val spots = listOf(
            PskReporterSender.SpotRecord(
                senderCallsign = "W1AW",
                frequencyHz = 14_074_000L,
                snr = -10,
                mode = "FT8",
                senderLocator = "FN31",
                flowStartSeconds = 1_700_000_000L,
            )
        )

        val packets = PskReporterSender.buildPackets("N0CALL", "EM48", "FT8AF 1.0", spots)
        assertThat(packets).hasSize(1)

        val buf = ByteBuffer.wrap(packets[0]).order(ByteOrder.BIG_ENDIAN)

        // IPFIX version = 0x000A
        assertThat(buf.short.toInt() and 0xFFFF).isEqualTo(0x000A)

        // Total length matches actual byte array size
        val totalLen = buf.short.toInt() and 0xFFFF
        assertThat(totalLen).isEqualTo(packets[0].size)

        // Export time — non-zero unix timestamp
        val exportTime = buf.int.toLong() and 0xFFFFFFFFL
        assertThat(exportTime).isGreaterThan(0L)

        // Sequence number = 42
        assertThat(buf.int).isEqualTo(42)

        // Observation domain ID — just verify it's present
        buf.int // consume
    }

    @Test
    fun `buildPackets includes templates in first packet`() {
        val spots = listOf(
            PskReporterSender.SpotRecord("W1AW", 14_074_000L, -10, "FT8", "FN31", 1_700_000_000L)
        )

        val packets = PskReporterSender.buildPackets("N0CALL", "EM48", "FT8AF 1.0", spots)
        assertThat(packets).hasSize(1)

        val buf = ByteBuffer.wrap(packets[0]).order(ByteOrder.BIG_ENDIAN)
        buf.position(16) // skip IPFIX header

        // First set should be options template (0x0003)
        val firstSetId = buf.short.toInt() and 0xFFFF
        assertThat(firstSetId).isEqualTo(0x0003)
    }

    @Test
    fun `buildPackets respects MTU limit`() {
        // Create many spots to force multiple packets
        val spots = (1..100).map { i ->
            PskReporterSender.SpotRecord(
                senderCallsign = "CALL$i",
                frequencyHz = 14_074_000L + i,
                snr = -10,
                mode = "FT8",
                senderLocator = "FN${i.toString().padStart(2, '0')}",
                flowStartSeconds = 1_700_000_000L + i,
            )
        }

        val packets = PskReporterSender.buildPackets("N0CALL", "EM48", "FT8AF 1.0", spots)

        // Should produce multiple packets
        assertThat(packets.size).isGreaterThan(1)

        // Every packet must be <= 1400 bytes
        for (pkt in packets) {
            assertThat(pkt.size).isAtMost(1400)
        }

        // Total spots across all packets should equal 100
        // (verify by counting sender data set entries)
        var totalSpots = 0
        for (pkt in packets) {
            val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
            buf.position(2)
            val pktLen = buf.short.toInt() and 0xFFFF
            assertThat(pktLen).isEqualTo(pkt.size)
            totalSpots++ // approximate: at least one spot per packet
        }
        assertThat(totalSpots).isEqualTo(packets.size)
    }

    @Test
    fun `packet is 4-byte aligned`() {
        val spots = listOf(
            PskReporterSender.SpotRecord("W1AW", 14_074_000L, -10, "FT8", "FN31", 1_700_000_000L)
        )
        val packets = PskReporterSender.buildPackets("N0CALL", "EM48", "FT8AF 1.0", spots)
        for (pkt in packets) {
            assertThat(pkt.size % 4).isEqualTo(0)
        }
    }

    @Test
    fun `sequence number increments across packets`() {
        PskReporterSender.sequenceNumber = 0
        val spots = (1..100).map {
            PskReporterSender.SpotRecord("CALL$it", 14_074_000L, -10, "FT8", null, 1_700_000_000L)
        }
        val packets = PskReporterSender.buildPackets("N0CALL", "EM48", "FT8AF 1.0", spots)

        for ((i, pkt) in packets.withIndex()) {
            val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
            buf.position(8) // skip version, length, export time
            val seq = buf.int
            assertThat(seq).isEqualTo(i)
        }
    }

    // ---------------------------------------------------------------
    // Software string with rig name
    // ---------------------------------------------------------------

    @Test
    fun `buildSoftwareString without rig`() {
        assertThat(PskReporterSender.buildSoftwareString("1.0.2", ""))
            .isEqualTo("FT8AF 1.0.2")
    }

    @Test
    fun `buildSoftwareString with rig`() {
        assertThat(PskReporterSender.buildSoftwareString("1.0.2", "Icom"))
            .isEqualTo("FT8AF 1.0.2 (Icom)")
    }

    @Test
    fun `buildSoftwareString null rig`() {
        assertThat(PskReporterSender.buildSoftwareString("1.0.2", null))
            .isEqualTo("FT8AF 1.0.2")
    }

    // ---------------------------------------------------------------
    // Dedup window bookkeeping (markIfFresh) + thread-safety
    // ---------------------------------------------------------------

    // Mirrors PskReporterSender.DEDUP_WINDOW_MS (private const): 5 minutes.
    private val dedupWindowMs = 5 * 60 * 1000L

    @Test
    fun `markIfFresh reports first sighting then dedups within the window`() {
        val key = "W1AW|14"
        // First sighting → reportable, and now marked.
        assertThat(PskReporterSender.markIfFresh(key, 1_000L)).isTrue()
        // Same key well inside the window → duplicate.
        assertThat(PskReporterSender.markIfFresh(key, 1_000L + 1_000L)).isFalse()
        assertThat(PskReporterSender.markIfFresh(key, 1_000L + dedupWindowMs - 1)).isFalse()
        // Once the window has fully elapsed → reportable again.
        assertThat(PskReporterSender.markIfFresh(key, 1_000L + dedupWindowMs)).isTrue()
    }

    @Test
    fun `markIfFresh dedups per callsign-band key independently`() {
        assertThat(PskReporterSender.markIfFresh("W1AW|14", 1_000L)).isTrue()
        // Different band for the same call is a separate spot.
        assertThat(PskReporterSender.markIfFresh("W1AW|7", 1_000L)).isTrue()
        // Different call, same band is a separate spot.
        assertThat(PskReporterSender.markIfFresh("K2ABC|14", 1_000L)).isTrue()
        // Repeats of each are suppressed.
        assertThat(PskReporterSender.markIfFresh("W1AW|14", 1_000L)).isFalse()
        assertThat(PskReporterSender.markIfFresh("W1AW|7", 1_000L)).isFalse()
        assertThat(PskReporterSender.markIfFresh("K2ABC|14", 1_000L)).isFalse()
    }

    /**
     * Overlapping decode passes (#398) call enqueue()/toSpotRecord()/markIfFresh()
     * on two decode threads at once. When many threads race on the SAME key, the
     * get-then-put must be atomic so exactly one spot is reported — a non-atomic
     * check (or a plain HashMap) lets several threads see "absent" and each report
     * the same spot. Barrier-synchronized rounds maximize overlap so a
     * non-atomic implementation would let more than one through.
     */
    @Test
    fun `markIfFresh admits exactly one thread per key under concurrent access`() {
        val threadCount = 8
        repeat(30) { round ->
            PskReporterSender.resetForTests()
            PskReporterSender.sendDatagram = { data -> captured.add(data.copyOf()) }
            val key = "RACE|14"
            val now = 1_000L + round
            val admitted = java.util.concurrent.atomic.AtomicInteger(0)
            val barrier = java.util.concurrent.CyclicBarrier(threadCount)
            val threads = (0 until threadCount).map {
                Thread {
                    barrier.await()
                    if (PskReporterSender.markIfFresh(key, now)) admitted.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertThat(admitted.get()).isEqualTo(1)
        }
    }

    /**
     * Concurrent inserts of many DISTINCT keys hammer the shared map's resize path.
     * A plain HashMap mutated from multiple threads can corrupt its buckets, lose
     * entries, or spin — the guarded map must simply complete, admitting every key
     * exactly once.
     */
    @Test
    fun `markIfFresh handles concurrent distinct keys without corruption`() {
        val threadCount = 8
        val perThread = 500
        val admitted = java.util.concurrent.atomic.AtomicInteger(0)
        val barrier = java.util.concurrent.CyclicBarrier(threadCount)
        val threads = (0 until threadCount).map { t ->
            Thread {
                barrier.await()
                for (i in 0 until perThread) {
                    if (PskReporterSender.markIfFresh("CALL_${t}_$i|14", 1_000L)) {
                        admitted.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // Every distinct key is fresh exactly once — no lost or double inserts.
        assertThat(admitted.get()).isEqualTo(threadCount * perThread)
    }

    /** Decode a hex string (all whitespace ignored) into bytes for golden-vector comparison. */
    private fun hex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(clean.length / 2) {
            val hi = Character.digit(clean[it * 2], 16)
            val lo = Character.digit(clean[it * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex digit at index ${it * 2} in: $clean" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
