package radio.ks3ckc.ft8af.ui.settings

/**
 * Pure helpers for the FlexRadio picker's live-discovery list.
 *
 * The picker polls [com.k1af.ft8af.flex.FlexRadioFactory] once a second while
 * open. These functions keep that polling cheap and stable: derive a stable
 * identity per radio, and only rebuild the displayed list when the discovered
 * set actually changed (so the list doesn't recompose / reset its scroll every
 * tick). Kept free of Android/Compose types so it's unit-testable.
 */
internal object FlexPickerLogic {

    /**
     * Stable identity for a discovered radio. Prefers the serial number; falls
     * back to the IP when the serial is missing/blank. Trimmed so incidental
     * whitespace doesn't read as a change.
     */
    fun identityKey(serial: String?, ip: String?): String {
        val s = serial?.trim().orEmpty()
        if (s.isNotEmpty()) return s
        return ip?.trim().orEmpty()
    }

    /**
     * Whether the latest discovery snapshot differs from what's displayed,
     * compared by identity key in order. When false, the caller skips the
     * (otherwise per-second) list rebuild.
     */
    fun listChanged(displayedKeys: List<String>, latestKeys: List<String>): Boolean {
        return displayedKeys != latestKeys
    }
}
