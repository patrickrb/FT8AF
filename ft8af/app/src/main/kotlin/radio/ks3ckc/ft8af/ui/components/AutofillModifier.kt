package radio.ks3ckc.ft8af.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.onAutofillText
import androidx.compose.ui.semantics.semantics

/**
 * The credential fields we advertise to the Android Autofill framework. Each role
 * maps to a standard [ContentType] so password managers (1Password, Bitwarden,
 * Google Password Manager, …) can classify the field and offer fill / save.
 *
 * API-key / single-secret fields (QRZ Logbook, Cloudlog) are intentionally absent:
 * the framework has no "API key" type, so mislabeling them as a password would be
 * misleading. See issue #439, open questions — option (a).
 */
enum class CredentialFieldRole {
    /** Login name that is not an email — e.g. a QRZ callsign or ICOM username. */
    USERNAME,

    /** Email-address login — e.g. the POTA account. */
    EMAIL,

    /** Secret password field. */
    PASSWORD,
}

/**
 * Pure mapping from a credential field's [role][CredentialFieldRole] to the
 * [ContentType] it should advertise. Extracted so the decision is unit-testable
 * without touching the Compose autofill glue below.
 */
internal fun credentialContentType(role: CredentialFieldRole): ContentType =
    when (role) {
        CredentialFieldRole.USERNAME -> ContentType.Username
        CredentialFieldRole.EMAIL -> ContentType.EmailAddress
        CredentialFieldRole.PASSWORD -> ContentType.Password
    }

/**
 * Attaches Android autofill metadata to a text field so the OS autofill service can
 * classify it and offer to fill / save credentials.
 *
 * On Compose UI 1.8+ (BOM 2025.04.01, issue #440) autofill is declarative: we tag the
 * field's semantics with its [ContentType] so the framework classifies it, and register
 * an [onAutofillText] handler that pushes any filled value into the field's existing
 * state. This replaces the old imperative `AutofillNode` / `LocalAutofillTree` glue
 * (which tracked on-screen bounds and requested / cancelled autofill on focus by hand).
 *
 * [onFill] is captured through [rememberUpdatedState] so the stable semantics handler
 * always invokes the latest lambda.
 *
 * @param role which credential this field holds; maps to the advertised [ContentType]
 *   via [credentialContentType].
 * @param onFill invoked with the value the autofill service supplied; push it into
 *   the field's existing state exactly as `onValueChange` would.
 */
@Composable
fun Modifier.autofill(
    role: CredentialFieldRole,
    onFill: (String) -> Unit,
): Modifier {
    val currentOnFill by rememberUpdatedState(onFill)
    return this.semantics {
        contentType = credentialContentType(role)
        onAutofillText { text ->
            currentOnFill(text.text)
            true
        }
    }
}
