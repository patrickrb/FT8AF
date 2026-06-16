package radio.ks3ckc.ft8us.pskreporter

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.bg7yoz.ft8cn.ModeProfile
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Uploads decoded FT8 spots to PSKReporter using the IPFIX binary protocol
 * over UDP (report.pskreporter.info:4739).
 *
 * Public API:
 * - [enqueue] — called from afterDecode() on every decode cycle
 * - [start] / [stop] — lifecycle control (called when recording starts/stops)
 */
object PskReporterSender {
    private const val TAG = "PskReporterSender"

    @VisibleForTesting
    internal const val HOST = "report.pskreporter.info"
    private const val PORT = 4739
    private const val MAX_DATAGRAM = 1400
    private const val SEND_INTERVAL_MS = 5 * 60 * 1000L       // 5 minutes
    private const val SEND_JITTER_MS = 30_000L                 // +/- 30s
    private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L         // 5 minutes per band
    private const val ENTERPRISE_NUMBER = 30351                // 0x0000768F
    private const val IPFIX_VERSION = 0x000A

    // Template set IDs
    private const val TEMPLATE_SET_ID_OPTIONS = 0x0003         // options template
    private const val TEMPLATE_SET_ID_DATA = 0x0002            // data template

    // Template IDs
    private const val RECEIVER_TEMPLATE_ID = 0x50E2            // 20706
    private const val SENDER_TEMPLATE_ID = 0x50E3              // 20707

    // Data set IDs (= template IDs cast to data set range)
    // Per IPFIX, data set IDs >= 256 and equal to the template ID they reference
    private const val RECEIVER_DATA_SET_ID = RECEIVER_TEMPLATE_ID
    private const val SENDER_DATA_SET_ID = SENDER_TEMPLATE_ID

    // IPFIX field type IDs. PSKReporter assigns specific element IDs under
    // enterprise 30351 (see WSJT-X PSKReporter.cpp / pskreporter.info/pskdev.html);
    // the collector maps element-ID -> column, so these must match exactly or the
    // records are parsed structurally but discarded as unrecognized.
    // Receiver (options) fields — all enterprise-specific under 30351.
    private const val FT_RX_CALLSIGN = 2       // receiverCallsign  (0x8002)
    private const val FT_RX_LOCATOR = 4        // receiverLocator   (0x8004)
    private const val FT_DECODING_SW = 8       // decodingSoftware  (0x8008)
    private const val FT_RX_ANTENNA = 9        // antennaInformation (0x8009)
    private const val FT_RX_RIG = 13            // rigInformation     (0x800D)
    // Sender (data) fields — enterprise-specific under 30351, EXCEPT flowStart.
    private const val FT_TX_CALLSIGN = 1       // senderCallsign    (0x8001)
    private const val FT_FREQUENCY = 5         // frequency         (0x8005)
    private const val FT_SNR = 6               // sNR               (0x8006)
    private const val FT_MODE = 10             // mode              (0x800A)
    private const val FT_TX_LOCATOR = 3        // senderLocator     (0x8003)
    private const val FT_INFO_SOURCE = 11      // informationSource (0x800B)
    // flowStartSeconds is the STANDARD IANA IPFIX element 150 (0x0096): no
    // enterprise bit, no enterprise number in its template descriptor.
    private const val FT_FLOW_START = 150      // flowStartSeconds  (0x0096, standard)

    data class SpotRecord(
        val senderCallsign: String,
        val frequencyHz: Long,
        val snr: Int,
        val mode: String,
        val senderLocator: String?,
        val flowStartSeconds: Long,
    )

    private val spotQueue = ConcurrentLinkedQueue<SpotRecord>()
    private val dedup = HashMap<String, Long>()  // "CALL|BAND" -> epoch ms
    private var sendJob: Job? = null
    private var scope: CoroutineScope? = null
    private var packetsSent = 0
    private var lastTemplateSentMs = 0L

    @VisibleForTesting
    internal val observationDomainId: Int = (Math.random() * Int.MAX_VALUE).toInt()

    @VisibleForTesting
    internal var sequenceNumber = 0

    /**
     * Build the PSKReporter "decodingSoftware" string, optionally including the
     * connected rig name. Pure function — testable without Android.
     */
    @VisibleForTesting
    internal fun buildSoftwareString(version: String, rigName: String?): String {
        val base = "FT8AF $version"
        return if (!rigName.isNullOrBlank()) "$base ($rigName)" else base
    }

    /** For testing: override the socket send behaviour. */
    @VisibleForTesting
    @JvmField
    internal var sendDatagram: (ByteArray) -> Unit = { data -> sendUdp(data) }

    fun start() {
        if (scope != null) return
        log("start")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sendJob = scope!!.launch {
            while (isActive) {
                val jitter = (Math.random() * 2 * SEND_JITTER_MS - SEND_JITTER_MS).toLong()
                delay(SEND_INTERVAL_MS + jitter)
                flush()
            }
        }
    }

    fun stop() {
        log("stop (${spotQueue.size} spots pending)")
        // Try to send any remaining spots before stopping
        runBlocking(Dispatchers.IO) {
            try { flush() } catch (_: Exception) {}
        }
        sendJob?.cancel()
        sendJob = null
        scope?.cancel()
        scope = null
    }

    /**
     * Enqueue decoded messages for PSKReporter upload.
     * Called from afterDecode() on every decode cycle.
     * Filters out invalid/self/free-text messages and deduplicates.
     */
    fun enqueue(messages: List<Ft8Message>) {
        if (!GeneralVariables.enablePskReporter) return
        if (GeneralVariables.myCallsign.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        var added = 0
        for (msg in messages) {
            val spot = toSpotRecord(msg, now) ?: continue
            spotQueue.add(spot)
            added++
        }
        if (added > 0) {
            log("enqueued $added spots (queue=${spotQueue.size})")
        }
    }

    private fun toSpotRecord(msg: Ft8Message, nowMs: Long): SpotRecord? {
        // Skip invalid messages
        val call = msg.callsignFrom ?: return null
        if (call.isEmpty() || call == "<...>") return null

        // Strip angle brackets from hashed callsigns
        val cleanCall = call.replace("<", "").replace(">", "")
        if (cleanCall.isEmpty()) return null

        // Skip our own callsign
        if (GeneralVariables.checkIsMyCallsign(cleanCall)) return null

        // Skip free-text messages (i3=0, n3=0)
        if (msg.i3 == 0 && msg.n3 == 0) return null

        // Mode string (FT8/FT4/FT2) derived from the message's mode descriptor. fromId()
        // falls back to FT8 for unknown ids, so guard against a corrupt/unexpected
        // signalFormat by dropping the spot rather than mislabelling it (prior behavior).
        val profile = ModeProfile.fromId(msg.signalFormat)
        if (profile.id != msg.signalFormat) return null
        val mode = profile.displayName

        // Frequency = carrier band + audio offset
        val freqHz = msg.band + msg.freq_hz.toLong()

        // Dedup: skip if same callsign+band reported within window
        val bandMhz = msg.band / 1_000_000
        val dedupKey = "$cleanCall|$bandMhz"
        val lastSeen = dedup[dedupKey]
        if (lastSeen != null && nowMs - lastSeen < DEDUP_WINDOW_MS) return null
        dedup[dedupKey] = nowMs

        return SpotRecord(
            senderCallsign = cleanCall,
            frequencyHz = freqHz,
            snr = msg.snr,
            mode = mode,
            senderLocator = msg.maidenGrid?.takeIf { it.length >= 4 },
            flowStartSeconds = msg.utcTime / 1000,
        )
    }

    @VisibleForTesting
    internal suspend fun flush() {
        val spots = mutableListOf<SpotRecord>()
        while (true) {
            val s = spotQueue.poll() ?: break
            spots.add(s)
        }
        if (spots.isEmpty()) return

        val myCall = GeneralVariables.myCallsign ?: return
        if (myCall.isEmpty()) return
        val myGrid = GeneralVariables.getMyMaidenheadGrid() ?: ""
        val rigName = GeneralVariables.myRigName ?: ""
        val software = buildSoftwareString(
            GeneralVariables.VERSION, GeneralVariables.myRigName)
        val antenna = GeneralVariables.myAntenna ?: ""

        // Build IPFIX packets, respecting MTU limit
        val packets = buildPackets(myCall, myGrid, software, spots, antenna, rigName)
        for (pkt in packets) {
            try {
                sendDatagram(pkt)
            } catch (e: Exception) {
                log("send error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        log("sent ${packets.size} packet(s) with ${spots.size} spots")
    }

    /**
     * Build one or more IPFIX datagrams containing the given spots.
     * Each datagram stays under [MAX_DATAGRAM] bytes.
     */
    @VisibleForTesting
    internal fun buildPackets(
        rxCall: String,
        rxGrid: String,
        software: String,
        spots: List<SpotRecord>,
        antenna: String = "",
        rig: String = "",
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val includeTemplates = needTemplates()

        var remaining = spots.toMutableList()
        while (remaining.isNotEmpty()) {
            val templateBytes = if (includeTemplates) encodeTemplates() else ByteArray(0)
            val receiverBytes = encodeReceiverDataSet(rxCall, rxGrid, software, antenna, rig)

            // Header is 16 bytes
            val overhead = 16 + templateBytes.size + receiverBytes.size
            val budget = MAX_DATAGRAM - overhead

            // Encode as many sender records as fit
            val senderRecords = mutableListOf<ByteArray>()
            var senderSetSize = 4 // set header (setId + length)
            val used = mutableListOf<SpotRecord>()

            for (spot in remaining) {
                val rec = encodeSenderRecord(spot)
                if (senderSetSize + rec.size > budget && used.isNotEmpty()) break
                senderRecords.add(rec)
                senderSetSize += rec.size
                used.add(spot)
            }
            remaining = remaining.drop(used.size).toMutableList()

            // Pad sender set to 4-byte boundary
            val senderPadding = (4 - (senderSetSize % 4)) % 4
            senderSetSize += senderPadding

            val totalLen = 16 + templateBytes.size + receiverBytes.size + senderSetSize
            val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

            // IPFIX header
            val exportTime = (System.currentTimeMillis() / 1000).toInt()
            buf.putShort(IPFIX_VERSION.toShort())
            buf.putShort(totalLen.toShort())
            buf.putInt(exportTime)
            buf.putInt(sequenceNumber)
            buf.putInt(observationDomainId)
            sequenceNumber++
            packetsSent++

            // Template sets (if needed)
            if (templateBytes.isNotEmpty()) {
                buf.put(templateBytes)
            }

            // Receiver data set
            buf.put(receiverBytes)

            // Sender data set header
            buf.putShort(SENDER_DATA_SET_ID.toShort())
            buf.putShort(senderSetSize.toShort())
            for (rec in senderRecords) {
                buf.put(rec)
            }
            // Padding
            repeat(senderPadding) { buf.put(0) }

            packets.add(buf.array())
            if (includeTemplates) lastTemplateSentMs = System.currentTimeMillis()
        }
        return packets
    }

    private fun needTemplates(): Boolean {
        if (packetsSent < 3) return true
        if (System.currentTimeMillis() - lastTemplateSentMs > 3600_000L) return true
        return false
    }

    /**
     * Encode the template sets (receiver options template + sender data template).
     * These describe the structure of subsequent data sets.
     */
    @VisibleForTesting
    internal fun encodeTemplates(): ByteArray {
        val rxTemplate = encodeReceiverTemplate()
        val txTemplate = encodeSenderTemplate()
        val buf = ByteBuffer.allocate(rxTemplate.size + txTemplate.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(rxTemplate)
        buf.put(txTemplate)
        return buf.array()
    }

    private fun encodeReceiverTemplate(): ByteArray {
        // Options template set (set ID = 0x0003)
        // Template ID = 0x50E2, field count = 5, scope field count = 0
        // Each field: type(2) + length(2) + enterprise(4)
        val fieldSize = 8 // 2+2+4 per field
        val fieldCount = 5
        val templateRecordLen = 2 + 2 + 2 + (fieldCount * fieldSize) // templateId + fieldCount + scopeFieldCount + fields
        val setLen = 4 + templateRecordLen // setHeader (id+len) + record
        val padding = (4 - (setLen % 4)) % 4
        val totalLen = setLen + padding

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // Set header
        buf.putShort(TEMPLATE_SET_ID_OPTIONS.toShort())
        buf.putShort(totalLen.toShort())
        // Template record header
        buf.putShort(RECEIVER_TEMPLATE_ID.toShort())
        buf.putShort(fieldCount.toShort())
        buf.putShort(0.toShort()) // scope field count

        // Field 1: receiverCallsign — variable length string
        buf.putShort((FT_RX_CALLSIGN or 0x8000).toShort()) // enterprise bit set
        buf.putShort(0xFFFF.toShort()) // variable length
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 2: receiverLocator — variable length string
        buf.putShort((FT_RX_LOCATOR or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 3: decodingSoftware — variable length string
        buf.putShort((FT_DECODING_SW or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 4: antennaInformation — variable length string
        buf.putShort((FT_RX_ANTENNA or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 5: rigInformation — variable length string
        buf.putShort((FT_RX_RIG or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        repeat(padding) { buf.put(0) }
        return buf.array()
    }

    private fun encodeSenderTemplate(): ByteArray {
        // Data template set (set ID = 0x0002)
        // Template ID = 0x50E3, field count = 7. Six fields are enterprise-specific
        // (8-byte descriptor: type+length+enterprise); flowStartSeconds is the
        // standard element 0x0096 (4-byte descriptor: type+length, no enterprise).
        val fieldCount = 7
        val enterpriseFieldSize = 8 // type(2) + length(2) + enterprise(4)
        val standardFieldSize = 4   // type(2) + length(2)
        val templateRecordLen =
            2 + 2 + (6 * enterpriseFieldSize) + standardFieldSize // templateId + fieldCount + fields
        val setLen = 4 + templateRecordLen
        val padding = (4 - (setLen % 4)) % 4
        val totalLen = setLen + padding

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // Set header
        buf.putShort(TEMPLATE_SET_ID_DATA.toShort())
        buf.putShort(totalLen.toShort())
        // Template record header
        buf.putShort(SENDER_TEMPLATE_ID.toShort())
        buf.putShort(fieldCount.toShort())

        // Field 1: senderCallsign — variable length
        buf.putShort((FT_TX_CALLSIGN or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 2: frequency — uint32 (4 bytes)
        buf.putShort((FT_FREQUENCY or 0x8000).toShort())
        buf.putShort(4.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 3: sNR — int8 (1 byte)
        buf.putShort((FT_SNR or 0x8000).toShort())
        buf.putShort(1.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 4: mode — variable length
        buf.putShort((FT_MODE or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 5: senderLocator — variable length
        buf.putShort((FT_TX_LOCATOR or 0x8000).toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 6: informationSource — uint8 (1 byte)
        buf.putShort((FT_INFO_SOURCE or 0x8000).toShort())
        buf.putShort(1.toShort())
        buf.putInt(ENTERPRISE_NUMBER)

        // Field 7: flowStartSeconds — standard IPFIX element 0x0096, uint32 (4 bytes).
        // No enterprise bit and no enterprise number (4-byte descriptor).
        buf.putShort(FT_FLOW_START.toShort())
        buf.putShort(4.toShort())

        repeat(padding) { buf.put(0) }
        return buf.array()
    }

    /**
     * Encode the receiver data set (our station info).
     * Set ID = [RECEIVER_DATA_SET_ID].
     */
    @VisibleForTesting
    internal fun encodeReceiverDataSet(
        call: String, grid: String, software: String, antenna: String, rig: String = "",
    ): ByteArray {
        val callBytes = encodeVarString(call)
        val gridBytes = encodeVarString(grid)
        val swBytes = encodeVarString(software)
        val antBytes = encodeVarString(antenna)
        val rigBytes = encodeVarString(rig)
        val bodyLen = callBytes.size + gridBytes.size + swBytes.size + antBytes.size + rigBytes.size
        val setLen = 4 + bodyLen
        val padding = (4 - (setLen % 4)) % 4
        val totalLen = setLen + padding

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(RECEIVER_DATA_SET_ID.toShort())
        buf.putShort(totalLen.toShort())
        buf.put(callBytes)
        buf.put(gridBytes)
        buf.put(swBytes)
        buf.put(antBytes)
        buf.put(rigBytes)
        repeat(padding) { buf.put(0) }
        return buf.array()
    }

    /**
     * Encode a single sender record (one spot). No set header — these are
     * concatenated inside the sender data set.
     */
    @VisibleForTesting
    internal fun encodeSenderRecord(spot: SpotRecord): ByteArray {
        val callBytes = encodeVarString(spot.senderCallsign)
        val modeBytes = encodeVarString(spot.mode)
        val locBytes = encodeVarString(spot.senderLocator ?: "")
        // callsign(var) + freq(4) + snr(1) + mode(var) + locator(var) + infoSource(1) + flowStart(4)
        val size = callBytes.size + 4 + 1 + modeBytes.size + locBytes.size + 1 + 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(callBytes)
        buf.putInt(spot.frequencyHz.toInt())
        buf.put(spot.snr.toByte())
        buf.put(modeBytes)
        buf.put(locBytes)
        buf.put(1.toByte()) // informationSource = 1 (automatic)
        buf.putInt(spot.flowStartSeconds.toInt())
        return buf.array()
    }

    /**
     * Encode a variable-length string per IPFIX: 1-byte length prefix + UTF-8 bytes.
     * If length >= 255, uses 3-byte prefix (0xFF + 2-byte length).
     */
    @VisibleForTesting
    internal fun encodeVarString(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        return if (utf8.size < 255) {
            val out = ByteArray(1 + utf8.size)
            out[0] = utf8.size.toByte()
            System.arraycopy(utf8, 0, out, 1, utf8.size)
            out
        } else {
            val out = ByteArray(3 + utf8.size)
            out[0] = 0xFF.toByte()
            out[1] = (utf8.size shr 8).toByte()
            out[2] = (utf8.size and 0xFF).toByte()
            System.arraycopy(utf8, 0, out, 3, utf8.size)
            out
        }
    }

    private fun sendUdp(data: ByteArray) {
        DatagramSocket().use { socket ->
            val addr = InetAddress.getByName(HOST)
            val packet = DatagramPacket(data, data.size, addr, PORT)
            socket.send(packet)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use {
                it.append("$ts PskReporterSender: $msg\n")
            }
        } catch (_: Exception) {
        }
    }

    /** Reset all state for testing. */
    @VisibleForTesting
    internal fun resetForTests() {
        spotQueue.clear()
        dedup.clear()
        sendJob?.cancel()
        sendJob = null
        scope?.cancel()
        scope = null
        packetsSent = 0
        sequenceNumber = 0
        lastTemplateSentMs = 0L
        sendDatagram = { data -> sendUdp(data) }
    }
}
