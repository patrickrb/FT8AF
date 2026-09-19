package radio.ks3ckc.ft8af.pota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import radio.ks3ckc.ft8af.pota.model.PotaActivation
import radio.ks3ckc.ft8af.pota.model.PotaQso
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks the user's current POTA activation. While an activation is running we
 * force `GeneralVariables.toModifier = "POTA"` so generated CQs come out as
 * "CQ POTA <call> <grid>" via the existing message-formatting path
 * (Ft8Message.java:277). On stop we restore whatever modifier was set before
 * the activation started — so a user can still send "CQ NA …" etc. without
 * losing their preference.
 *
 * The activator UI and the QSO save path both consult this manager:
 * - UI binds to [currentActivation] to drive counter/buttons/etc.
 * - The QSO save path calls [stampQso] to attach MY_SIG/MY_SIG_INFO so the
 *   ADIF export can identify the contact as part of an activation. The
 *   matching pota_activation row's qso_count is bumped inside DatabaseOpr.
 */
object PotaSessionManager {
    private const val TAG = "PotaSessionManager"
    private const val MY_SIG_POTA = "POTA"

    private val _currentActivation = MutableStateFlow<PotaActivation?>(null)
    val currentActivation: StateFlow<PotaActivation?> = _currentActivation.asStateFlow()

    private val _activationQsos = MutableStateFlow<List<PotaQso>>(emptyList())
    val activationQsos: StateFlow<List<PotaQso>> = _activationQsos.asStateFlow()

    // One-shot "an activation just ended" events carrying the final activation
    // (with its qso_count). No replay: only a listener that's running when the
    // operator ends the activation (the in-app rating prompt) should react.
    private val _endedActivations = MutableSharedFlow<PotaActivation>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val endedActivations: SharedFlow<PotaActivation> = _endedActivations.asSharedFlow()

    @Volatile
    private var savedModifier: String = ""

    val isActive: Boolean get() = _currentActivation.value != null
    val currentParkRefs: List<String> get() = _currentActivation.value?.parkRefs ?: emptyList()

    @Synchronized
    fun start(parkRefs: List<String>, notes: String?): PotaActivation? {
        if (_currentActivation.value != null) {
            log("start ignored — activation already running for ${currentParkRefs}")
            return _currentActivation.value
        }
        val refs = parkRefs
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
        if (refs.isEmpty()) {
            log("start rejected — no valid park refs")
            return null
        }
        val joined = refs.joinToString(",")
        savedModifier = GeneralVariables.toModifier ?: ""
        GeneralVariables.toModifier = MY_SIG_POTA
        val operator = GeneralVariables.myCallsign?.takeIf { it.isNotBlank() }
        val activation = PotaActivationDao.startActivation(joined, operator, notes)
        _currentActivation.value = activation
        _activationQsos.value = emptyList()
        log("start refs=$joined id=${activation.id} priorModifier='${savedModifier}'")
        return activation
    }

    @Synchronized
    fun resume() {
        if (_currentActivation.value != null) return
        val active = PotaActivationDao.findActiveActivation() ?: return
        savedModifier = GeneralVariables.toModifier ?: ""
        GeneralVariables.toModifier = MY_SIG_POTA
        _currentActivation.value = active
        _activationQsos.value = PotaActivationDao.getActivationQsos(active)
        log("resume ref=${active.parkRef} id=${active.id} qsoCount=${active.qsoCount}")
    }

    @Synchronized
    fun end() {
        val active = _currentActivation.value ?: run {
            log("end ignored — no activation running")
            return
        }
        // One timestamp for the DB row and the published copy, so listeners get
        // exactly what was persisted: an ended activation, not the still-active
        // in-memory object.
        val endedAtMs = System.currentTimeMillis()
        PotaActivationDao.endActivation(active.id, endedAtMs)
        GeneralVariables.toModifier = savedModifier
        savedModifier = ""
        log("end ref=${active.parkRef} id=${active.id} qsoCount=${active.qsoCount} restoredModifier='${GeneralVariables.toModifier}'")
        _currentActivation.value = null
        _activationQsos.value = emptyList()
        notifyActivationEnded(endedActivation(active, endedAtMs))
    }

    /** [active] as [end] persisted it: the same row stamped with [endedAtMs], so it reads as ended. */
    internal fun endedActivation(active: PotaActivation, endedAtMs: Long): PotaActivation =
        active.copy(endedAtMs = endedAtMs)

    /** Publish [ended] on [endedActivations]. Split out of [end] so it's testable without the DB. */
    internal fun notifyActivationEnded(ended: PotaActivation) {
        _endedActivations.tryEmit(ended)
    }

    /**
     * Called from the QSO save path (DatabaseOpr) right after it recounts
     * pota_activation.qso_count in SQLite, carrying the refreshed dupe-free count,
     * so the in-memory activation that the phone and Android Auto UIs observe
     * stays in step with the DB without a blocking reload on the save path.
     */
    @JvmStatic
    @Synchronized
    fun onQsoLogged(mySigInfo: String?, uniqueQsoCount: Int) {
        activationWithLoggedQso(_currentActivation.value, mySigInfo, uniqueQsoCount)?.let {
            _currentActivation.value = it
        }
    }

    /**
     * Pure decision behind [onQsoLogged]: the replacement activation carrying the
     * DB's recounted unique-contact total, or null when nothing should change (no
     * activation running, the QSO belongs to a different park ref, or the count is
     * invalid). The in-memory count must move exactly to the DB's value — it is a
     * dupe-free COUNT(DISTINCT), so it can stay flat after a logged QSO or even
     * shrink relative to the raw QSO tally.
     */
    internal fun activationWithLoggedQso(
        active: PotaActivation?,
        mySigInfo: String?,
        uniqueQsoCount: Int,
    ): PotaActivation? {
        if (active == null || uniqueQsoCount < 0) return null
        if (!qsoCountsForActivation(active.parkRef, mySigInfo)) return null
        return active.copy(qsoCount = uniqueQsoCount)
    }

    /** Pull the latest qso_count and contacts from the DB so the UI stays accurate. */
    fun refreshCounter() {
        val active = _currentActivation.value ?: return
        PotaActivationDao.reload(active.id)?.let {
            _currentActivation.value = it
            _activationQsos.value = PotaActivationDao.getActivationQsos(it)
        }
    }

    fun history(): List<PotaActivation> = PotaActivationDao.history()

    fun getQsosForActivation(activation: PotaActivation): List<PotaQso> =
        PotaActivationDao.getActivationQsos(activation)

    /**
     * Stamp POTA ADIF fields onto a QSO record about to be inserted. Mutates
     * [record] in place — called from the QSO save path with the latest spots
     * cache so we can also auto-fill SIG/SIG_INFO when the worked station is
     * itself activating (Park-to-Park).
     */
    @JvmStatic
    fun stampQso(record: com.k1af.ft8af.log.QSLRecord, spottedParkRef: String?) {
        _currentActivation.value?.parkRef?.let {
            record.mySig = MY_SIG_POTA
            record.mySigInfo = it
        }
        spottedParkRef?.let {
            record.sig = MY_SIG_POTA
            record.sigInfo = it
        }
    }

    /**
     * Mirrors DatabaseOpr's bump predicate (`park_ref = ? AND ended_at IS NULL`
     * bound to the record's MY_SIG_INFO): the in-memory counter must move
     * exactly when the DB row moved, or the two drift apart.
     */
    internal fun qsoCountsForActivation(activeParkRef: String, mySigInfo: String?): Boolean =
        !mySigInfo.isNullOrEmpty() && mySigInfo == activeParkRef

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Pota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
