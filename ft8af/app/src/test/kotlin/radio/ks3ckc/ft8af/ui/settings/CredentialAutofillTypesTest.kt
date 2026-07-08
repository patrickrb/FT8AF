package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.ui.autofill.ContentType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.ui.components.CredentialFieldRole
import radio.ks3ckc.ft8af.ui.components.credentialContentType

/**
 * Covers the field-role → autofill [ContentType] mapping used by the QRZ, ICOM, and
 * POTA login dialogs (issue #439). The `Modifier.autofill` glue can't be unit-tested,
 * so the decision is extracted into `credentialContentType`, which is what's tested
 * here. Migrated to the semantics-based autofill API in issue #453.
 */
class CredentialAutofillTypesTest {

    @Test
    fun username_role_advertises_username() {
        assertThat(credentialContentType(CredentialFieldRole.USERNAME))
            .isEqualTo(ContentType.Username)
    }

    @Test
    fun email_role_advertises_email_address() {
        // POTA logs in with an email; EmailAddress lets managers offer the account.
        assertThat(credentialContentType(CredentialFieldRole.EMAIL))
            .isEqualTo(ContentType.EmailAddress)
    }

    @Test
    fun password_role_advertises_password() {
        assertThat(credentialContentType(CredentialFieldRole.PASSWORD))
            .isEqualTo(ContentType.Password)
    }

    @Test
    fun username_role_is_not_email_address() {
        // QRZ usernames are callsigns, not emails — mislabeling would let a manager
        // fill an email address into the callsign field.
        assertThat(credentialContentType(CredentialFieldRole.USERNAME))
            .isNotEqualTo(ContentType.EmailAddress)
    }

    @Test
    fun every_role_maps_to_a_content_type() {
        // Each credential field advertises a single, unambiguous type so the autofill
        // service classifies it deterministically.
        for (role in CredentialFieldRole.entries) {
            assertThat(credentialContentType(role)).isNotNull()
        }
    }
}
