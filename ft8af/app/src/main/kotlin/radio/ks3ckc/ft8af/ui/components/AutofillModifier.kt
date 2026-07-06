package radio.ks3ckc.ft8af.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * The credential fields we advertise to the Android Autofill framework. Each role
 * maps to a standard [AutofillType] so password managers (1Password, Bitwarden,
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
 * autofill hint(s) it should advertise. Extracted so the decision is unit-testable
 * without touching the experimental Compose autofill glue below.
 *
 * [AutofillType] carries an error-level experimental opt-in, so this — and any test
 * that inspects the result — must opt in; keeping the type inside this function (the
 * public [autofill] modifier takes a plain [CredentialFieldRole]) stops that opt-in
 * from spreading into every dialog call site.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun credentialAutofillTypes(role: CredentialFieldRole): List<AutofillType> =
    when (role) {
        CredentialFieldRole.USERNAME -> listOf(AutofillType.Username)
        CredentialFieldRole.EMAIL -> listOf(AutofillType.EmailAddress)
        CredentialFieldRole.PASSWORD -> listOf(AutofillType.Password)
    }

/**
 * Attaches Android autofill metadata to a text field so the OS autofill service can
 * classify it and offer to fill / save credentials.
 *
 * On the current Compose toolchain (BOM 2024.02.00, Compose UI ~1.6) the autofill
 * API is experimental: we register an [AutofillNode] in [LocalAutofillTree], track
 * its on-screen bounds via [onGloballyPositioned], and request / cancel autofill as
 * the field gains / loses focus. Once the BOM is bumped to ~1.8+ (issue #440) this
 * can be replaced by the declarative `Modifier.semantics { contentType = … }`.
 *
 * @param role which credential this field holds; maps to the advertised hint(s) via
 *   [credentialAutofillTypes].
 * @param onFill invoked with the value the autofill service supplied; push it into
 *   the field's existing state exactly as `onValueChange` would.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.autofill(
    role: CredentialFieldRole,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val node = AutofillNode(autofillTypes = credentialAutofillTypes(role), onFill = onFill)
    LocalAutofillTree.current += node
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.run {
                if (state.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}
