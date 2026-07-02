package radio.ks3ckc.ft8af.pota

import com.k1af.ft8af.Ft8Message

/**
 * Single source of truth for "is this decode a POTA CQ".
 *
 * Shared by the decode-list "CQ POTA" display filter ([radio.ks3ckc.ft8af.ui.decode.filterMessages])
 * and the Hunt auto-call path ([com.k1af.ft8af.ft8transmit.FT8TransmitSignal]) so the two agree on
 * who counts as a POTA station — otherwise Hunt would call general CQs even with the POTA filter
 * selected (issue #333).
 */
object PotaCqClassifier {
    /**
     * Pure predicate over already-resolved signals — testable without Android/network.
     *
     * Matches the same three signals the display filter uses:
     *  1. an explicit `POTA` [modifier] on a CQ (`CQ POTA …`),
     *  2. a CQ from a station currently spotted on pota.app ([isSpottedPota]) — activators often
     *     drop the suffix to save characters,
     *  3. a garbled free-text fragment where [callsignTo] starts with `CQ POT` (decoders mangle
     *     long activator calls).
     */
    @JvmStatic
    fun isPotaCq(
        isCq: Boolean,
        modifier: String?,
        callsignTo: String?,
        isSpottedPota: Boolean,
    ): Boolean = isCq && (
        modifier == "POTA" ||
            isSpottedPota ||
            (callsignTo?.startsWith("CQ POT", ignoreCase = true) == true)
    )

    /** Convenience over an [Ft8Message], resolving the spot via [PotaSpotsRepository]. */
    @JvmStatic
    fun isPotaCq(msg: Ft8Message): Boolean = isPotaCq(
        isCq = msg.checkIsCQ(),
        modifier = msg.modifier,
        callsignTo = msg.callsignTo,
        isSpottedPota = PotaSpotsRepository.parkRefFor(msg.callsignFrom) != null,
    )
}
