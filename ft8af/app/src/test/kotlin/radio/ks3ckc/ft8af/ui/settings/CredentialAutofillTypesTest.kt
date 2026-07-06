package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.ui.components.CredentialFieldRole
import radio.ks3ckc.ft8af.ui.components.credentialAutofillTypes

/**
 * Covers the field-role → autofill-hint mapping used by the QRZ, ICOM, and POTA
 * login dialogs (issue #439). The `Modifier.autofill` glue can't be unit-tested, so
 * the decision is extracted into `credentialAutofillTypes`, which is what's tested
 * here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CredentialAutofillTypesTest {

    @Test
    fun username_role_advertises_username_only() {
        assertThat(credentialAutofillTypes(CredentialFieldRole.USERNAME))
            .containsExactly(AutofillType.Username)
    }

    @Test
    fun email_role_advertises_email_address_only() {
        // POTA logs in with an email; EmailAddress lets managers offer the account.
        assertThat(credentialAutofillTypes(CredentialFieldRole.EMAIL))
            .containsExactly(AutofillType.EmailAddress)
    }

    @Test
    fun password_role_advertises_password_only() {
        assertThat(credentialAutofillTypes(CredentialFieldRole.PASSWORD))
            .containsExactly(AutofillType.Password)
    }

    @Test
    fun username_role_never_advertises_email_address() {
        // QRZ usernames are callsigns, not emails — mislabeling would let a manager
        // fill an email address into the callsign field.
        assertThat(credentialAutofillTypes(CredentialFieldRole.USERNAME))
            .doesNotContain(AutofillType.EmailAddress)
    }

    @Test
    fun every_role_maps_to_exactly_one_hint() {
        // Each credential field advertises a single, unambiguous type so the autofill
        // service classifies it deterministically.
        for (role in CredentialFieldRole.entries) {
            assertThat(credentialAutofillTypes(role)).hasSize(1)
        }
    }
}
