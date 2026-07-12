package radio.ks3ckc.ft8af.wsjtx

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WSJT-X UDP transport for Android: owns the socket, broadcasts outbound
 * datagrams built by [WsjtxCodec], and (when "accept requests" is on) runs a
 * background listener that turns inbound requests into callbacks the ViewModel
 * wires to the transmit path.
 *
 * Modelled on [radio.ks3ckc.ft8af.pskreporter.PskReporterSender] (an `object`
 * with an IO coroutine scope + `DatagramSocket`). Settings live in
 * [GeneralVariables] (`udpEnabled`/`udpHost`/`udpPort`/`udpAcceptRequests`);
 * [reload] (re)binds from them, so both startup and a Settings change route
 * through one place.
 *
 * Inbound callbacks are posted to the main thread, since they drive the same
 * transmit entry points as UI taps.
 */
object WsjtxUdpService {
    private const val TAG = "WsjtxUdpService"
    private const val REPLAY_CACHE_CAP = 1000
    private const val STATUS_INTERVAL_MS = 1000L
    private const val HEARTBEAT_INTERVAL_MS = 15_000L
    private const val RECV_TIMEOUT_MS = 500

    private val mainHandler = Handler(Looper.getMainLooper())

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    private var targetPort = 0
    private var listenerThread: Thread? = null
    @Volatile private var listening = false
    // Guarded by [replayLock]: mutated from decode/band-change callers and read from the
    // listener thread handling a Replay request, so all access must be synchronized —
    // ArrayDeque is not thread-safe.
    private val replayCache = ArrayDeque<WsjtxCodec.Decode>()
    private val replayLock = Any()

    // Java-friendly SAM interfaces so the (Java) ViewModel can wire these with
    // plain lambdas.
    fun interface StatusProvider { fun get(): WsjtxCodec.Status? }
    fun interface ReplyHandler { fun onReply(call: String, grid: String, snr: Int) }
    fun interface HaltHandler { fun onHalt(autoOnly: Boolean) }
    fun interface FreeTextHandler { fun onFreeText(text: String, send: Boolean) }
    fun interface ReplayHandler { fun onReplay() }

    /** Supplies the current [WsjtxCodec.Status] snapshot for the periodic push. */
    @JvmField var statusProvider: StatusProvider? = null

    // Inbound request handlers (set by the ViewModel; invoked on the main thread).
    @JvmField var onReply: ReplyHandler? = null
    @JvmField var onReplay: ReplayHandler? = null
    @JvmField var onHaltTx: HaltHandler? = null
    @JvmField var onFreeText: FreeTextHandler? = null

    val enabled: Boolean get() = socket != null

    /** (Re)bind from the persisted [GeneralVariables] settings. Idempotent. */
    fun reload() {
        teardown()
        if (!GeneralVariables.udpEnabled) {
            log("disabled")
            return
        }
        try {
            target = InetAddress.getByName(GeneralVariables.udpHost)
            targetPort = GeneralVariables.udpPort
            // When accepting requests, bind the configured UDP port (default 2237) so
            // companion apps that send Reply/Halt/FreeText/Replay to that port actually
            // reach us. An ephemeral local port would only work for outbound broadcasts —
            // no companion could discover it. Send-only mode keeps the ephemeral port.
            val localPort = if (GeneralVariables.udpAcceptRequests) targetPort else 0
            val sock = DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
                broadcast = true
            }
            socket = sock

            val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope = s
            s.launch { periodicLoop() }

            if (GeneralVariables.udpAcceptRequests) startListener(sock)
            log("bound → ${GeneralVariables.udpHost}:${targetPort}" +
                if (GeneralVariables.udpAcceptRequests) " (accepting requests)" else "")
        } catch (e: Exception) {
            log("bind failed: ${e.javaClass.simpleName}: ${e.message}")
            teardown()
        }
    }

    /** Tear down socket + loops. Safe to call when already stopped. */
    fun stop() = teardown()

    private fun teardown() {
        listening = false
        scope?.cancel()
        scope = null
        socket?.close()
        socket = null
        target = null
        // The listener's blocking receive unblocks via SO_TIMEOUT + the closed
        // socket; give it a moment to exit but don't hang teardown on it.
        listenerThread?.let { t -> t.join(1000) }
        listenerThread = null
    }

    private fun send(datagram: ByteArray) {
        val sock = socket ?: return
        val addr = target ?: return
        try {
            sock.send(DatagramPacket(datagram, datagram.size, addr, targetPort))
        } catch (e: Exception) {
            log("send error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // --- outbound -----------------------------------------------------------

    fun sendClear() {
        if (enabled) send(WsjtxCodec.clear())
    }

    fun sendDecode(d: WsjtxCodec.Decode) {
        if (!enabled) return
        send(WsjtxCodec.decode(d))
        synchronized(replayLock) {
            if (replayCache.size >= REPLAY_CACHE_CAP) replayCache.removeFirst()
            replayCache.addLast(d)
        }
    }

    fun sendQsoLogged(q: WsjtxCodec.QsoLogged, adif: String) {
        if (!enabled) return
        send(WsjtxCodec.qsoLogged(q))
        send(WsjtxCodec.loggedAdif(adif))
    }

    fun clearReplayCache() = synchronized(replayLock) { replayCache.clear() }

    /** Re-broadcast this session's decodes (marked not-new) for a Replay request. */
    private fun replay() {
        if (!enabled) return
        // Snapshot under the lock, then send outside it (send() must not hold replayLock).
        val snapshot = synchronized(replayLock) { replayCache.toList() }
        for (d in snapshot) send(WsjtxCodec.decode(d.copy(isNew = false)))
    }

    private suspend fun periodicLoop() {
        val s = scope ?: return
        var sinceHeartbeatMs = HEARTBEAT_INTERVAL_MS // send one immediately
        while (s.isActive) {
            statusProvider?.get()?.let { send(WsjtxCodec.status(it)) }
            if (sinceHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
                send(WsjtxCodec.heartbeat(GeneralVariables.VERSION, ""))
                sinceHeartbeatMs = 0
            }
            delay(STATUS_INTERVAL_MS)
            sinceHeartbeatMs += STATUS_INTERVAL_MS
        }
    }

    // --- inbound listener ---------------------------------------------------

    private fun startListener(sock: DatagramSocket) {
        sock.soTimeout = RECV_TIMEOUT_MS
        listening = true
        val t = Thread({
            val buf = ByteArray(2048)
            while (listening) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    dispatch(packet.data.copyOf(packet.length))
                } catch (e: java.net.SocketTimeoutException) {
                    // loop to re-check `listening`
                } catch (e: Exception) {
                    if (listening) log("recv error: ${e.javaClass.simpleName}: ${e.message}")
                    if (sock.isClosed) break
                }
            }
        }, "ft8af-wsjtx-rx")
        t.isDaemon = true
        t.start()
        listenerThread = t
    }

    private fun dispatch(buf: ByteArray) {
        when (val msg = WsjtxCodec.parseInbound(buf)) {
            is WsjtxCodec.Inbound.Reply ->
                mainHandler.post { onReply?.onReply(msg.call, msg.grid, msg.snr) }
            is WsjtxCodec.Inbound.HaltTx ->
                mainHandler.post { onHaltTx?.onHalt(msg.autoOnly) }
            is WsjtxCodec.Inbound.FreeText ->
                mainHandler.post { onFreeText?.onFreeText(msg.text, msg.send) }
            WsjtxCodec.Inbound.Replay -> {
                replay()
                mainHandler.post { onReplay?.onReplay() }
            }
            null -> {} // ignored/unparseable
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts WsjtxUdpService: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
