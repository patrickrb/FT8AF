package radio.ks3ckc.ft8us.pota.model

/** One row from the live POTA spots feed (api.pota.app/spot/activator). */
data class PotaSpot(
    val activator: String,
    val frequencyKhz: Double,
    val mode: String,
    val reference: String,
    val parkName: String,
    val locationDesc: String,
    val spotter: String,
    val spotTimeUtc: String,
    val comments: String,
) {
    val frequencyHz: Long get() = (frequencyKhz * 1000).toLong()
}

/** Park metadata. Mostly we fill this from spot rows; the lookup endpoint is optional. */
data class PotaPark(
    val reference: String,
    val name: String,
    val locationDesc: String,
)

/** A single QSO row for the activation contacts list. */
data class PotaQso(
    val id: Long,
    val callsign: String,
    val grid: String,
    val band: String,
    val mode: String,
    val rstSent: String,
    val rstRcvd: String,
    val qsoDate: String,
    val timeOn: String,
    val sig: String?,
    val sigInfo: String?,
    /** The operator's own grid at the time of this QSO (my_gridsquare). Lets the
     *  activation map place the operator for historical activations. */
    val myGrid: String = "",
)

/** A logged activation session (row from pota_activation). */
data class PotaActivation(
    val id: Long,
    val parkRef: String,
    val operator: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val qsoCount: Int,
    val notes: String?,
) {
    val isActive: Boolean get() = endedAtMs == null

    /** Individual park references (splits comma-separated `parkRef`). */
    val parkRefs: List<String>
        get() = parkRef.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Human-readable display: "K-1234 + K-5678". */
    val parkRefsDisplay: String
        get() = parkRefs.joinToString(" + ")
}
