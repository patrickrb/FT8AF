package radio.ks3ckc.ft8af.pota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        PotaActivationDao.endActivation(active.id)
        GeneralVariables.toModifier = savedModifier
        savedModifier = ""
        log("end ref=${active.parkRef} id=${active.id} qsoCount=${active.qsoCount} restoredModifier='${GeneralVariables.toModifier}'")
        _currentActivation.value = null
        _activationQsos.value = emptyList()
    }

    /**
     * Called from the QSO save path (DatabaseOpr) right after it bumps
     * pota_activation.qso_count in SQLite, so the in-memory activation that the
     * phone and Android Auto UIs observe stays in step with the DB without a
     * blocking reload on the save path.
     */
    @JvmStatic
    fun onQsoLogged(mySigInfo: String?) {
        val active = _currentActivation.value ?: return
        if (!qsoCountsForActivation(active.parkRef, mySigInfo)) return
        _currentActivation.value = active.copy(qsoCount = active.qsoCount + 1)
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
