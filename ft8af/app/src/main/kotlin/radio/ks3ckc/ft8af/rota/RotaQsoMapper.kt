package radio.ks3ckc.ft8af.rota

import com.k1af.ft8af.log.QSLRecord

/**
 * Turns a logged contact into the trip-mode wire shape.
 *
 * The mapping is split in two so it can be tested without constructing a
 * QSLRecord (whose constructors reach into GeneralVariables and the rig layer):
 * [buildTripQso] is pure and takes the fields as plain values, and
 * [tripQsoFromRecord] is the thin adapter the QSO save path calls.
 */
object RotaQsoMapper {
    /**
     * @param roverLat/[roverLon] where the rover was — the last accepted GPS
     *        breadcrumb. Omitted when tracking hasn't produced a fix yet; the
     *        server then still records the contact, just without a map position.
     * @param nowMs fallback timestamp for a record with unparsable date/time.
     */
    @Suppress("LongParameterList")
    fun buildTripQso(
        toCallsign: String?,
        qsoDate: String?,
        timeOn: String?,
        qsoDateOff: String?,
        timeOff: String?,
        band: String?,
        mode: String?,
        toGrid: String?,
        sendReport: Int,
        receivedReport: Int,
        bandFreqHz: Long,
        roverLat: Double?,
        roverLon: Double?,
        state: String?,
        nowMs: Long,
    ): TripQso? {
        val call = toCallsign?.trim().orEmpty()
        if (call.isEmpty()) return null
        // Prefer TIME_ON (start of the exchange) — it is what the server's dedupe
        // key rounds to the minute, so the live copy and the end-of-trip ADIF
        // copy of the same QSO must agree on it.
        val ts =
            parseAdifUtc(qsoDate, timeOn)
                ?: parseAdifUtc(qsoDateOff, timeOff)
                ?: nowMs
        return TripQso(
            callsign = call,
            timestampMs = ts,
            band = band?.trim()?.takeIf { it.isNotEmpty() },
            mode = mode?.trim()?.takeIf { it.isNotEmpty() },
            grid = toGrid?.trim()?.takeIf { it.isNotEmpty() },
            // QSLRecord's sendReport is the report *we sent* the other station,
            // receivedReport the one they sent us — matching the API's naming.
            sentReport = formatReport(mode, sendReport),
            rcvdReport = formatReport(mode, receivedReport),
            roverLat = roverLat,
            roverLon = roverLon,
            state = state?.trim()?.takeIf { it.isNotEmpty() },
            frequencyKhz = frequencyKhzOrNull(bandFreqHz),
        )
    }

    fun tripQsoFromRecord(
        record: QSLRecord,
        roverLat: Double?,
        roverLon: Double?,
        state: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): TripQso? =
        buildTripQso(
            toCallsign = record.getToCallsign(),
            qsoDate = record.getQso_date(),
            timeOn = record.getTime_on(),
            qsoDateOff = record.getQso_date_off(),
            timeOff = record.getTime_off(),
            band = record.getBandLength(),
            mode = record.getMode(),
            toGrid = record.getToMaidenGrid(),
            sendReport = record.getSendReport(),
            receivedReport = record.getReceivedReport(),
            bandFreqHz = record.getBandFreq(),
            roverLat = roverLat,
            roverLon = roverLon,
            state = state,
            nowMs = nowMs,
        )
}
