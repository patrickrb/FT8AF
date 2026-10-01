package radio.ks3ckc.ft8af.flags

/**
 * Rollout flags for features under staged release (Band Advisor and its
 * companions). Each flag maps to a key in the remote feature-configuration
 * payload ([remoteKey]) and to a build-time `BuildConfig` default wired up in
 * [radio.ks3ckc.ft8af.flags.FeatureFlags].
 *
 * These flags are ROLLOUT CONTROLS, not subscription security: they gate what
 * the client shows and requests, but a modified client can flip them. Any
 * future paid feature must ALSO be validated server-side (the backend refuses
 * to serve data the account is not entitled to) — never rely on a client flag
 * alone for access control.
 */
enum class FeatureFlag(val remoteKey: String) {
    /** The Band Advisor card, detail sheet, and recommendation fetching. */
    BAND_ADVISOR("bandAdvisor"),

    /** Callsign-specific PSK Reporter analytics inside Band Advisor. */
    PERSONAL_PSK_ANALYTICS("personalPskAnalytics"),

    /** Background propagation / band-opening alert checks + notifications. */
    PROPAGATION_ALERTS("propagationAlerts"),
}

/**
 * Developer override for one flag, persisted only in debug builds. Tri-state
 * so "no override" is distinguishable from "forced off".
 */
enum class FlagOverride(val id: String) {
    DEFAULT("default"),
    FORCED_ON("on"),
    FORCED_OFF("off"),
    ;

    companion object {
        /** Parse a persisted id; unknown/missing values mean no override. */
        fun fromId(raw: String?): FlagOverride =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** Which layer supplied the effective value of a flag. */
enum class FlagValueSource {
    DEBUG_OVERRIDE,
    REMOTE_CONFIG,
    BUILD_DEFAULT,
}

/** The effective value of a flag plus the layer that decided it. */
data class FlagEvaluation(
    val flag: FeatureFlag,
    val enabled: Boolean,
    val source: FlagValueSource,
)
