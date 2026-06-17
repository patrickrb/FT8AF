package radio.ks3ckc.ft8af.qrz

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Focused tests on QrzXmlClient.parseTag — a small XmlPullParser-based tag
 * scanner that the lookup/auth paths share. Lives in the same package as the
 * production class so it can call the @VisibleForTesting internal helper.
 */
@RunWith(RobolectricTestRunner::class)
class QrzXmlParseTagTest {

    @Test
    fun parseTag_returnsFirstMatchingTagText() {
        val xml = """
            <root>
                <Key>abc123</Key>
                <Count>42</Count>
            </root>
        """.trimIndent()
        assertThat(QrzXmlClient.parseTag(xml, "Key")).isEqualTo("abc123")
    }

    @Test
    fun parseTag_isCaseInsensitive() {
        // parseTag does a case-insensitive name comparison so QRZ's mixed-case
        // tags (e.g. <Key> vs <key>) all match.
        val xml = "<r><KEY>abc</KEY></r>"
        assertThat(QrzXmlClient.parseTag(xml, "key")).isEqualTo("abc")
    }

    @Test
    fun parseTag_returnsNullWhenTagAbsent() {
        val xml = "<r><other>x</other></r>"
        assertThat(QrzXmlClient.parseTag(xml, "Key")).isNull()
    }

    @Test
    fun parseTag_returnsNullForEmptyTagText() {
        // parseTag explicitly filters empty/null text so callers can rely on
        // a non-null return meaning "real value present".
        val xml = "<r><Key></Key></r>"
        assertThat(QrzXmlClient.parseTag(xml, "Key")).isNull()
    }

    @Test
    fun parseTag_returnsNullForMalformedXml() {
        // Any XmlPullParser exception is swallowed and surfaces as null.
        assertThat(QrzXmlClient.parseTag("not really xml <<<>>>", "Key")).isNull()
    }
}
