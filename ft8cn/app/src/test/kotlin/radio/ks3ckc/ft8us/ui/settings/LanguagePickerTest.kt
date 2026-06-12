package radio.ks3ckc.ft8us.ui.settings

import com.bg7yoz.ft8cn.R
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Guards the in-app Language picker's single source of truth ([SUPPORTED_LANGUAGES])
 * and the pure [currentLanguageNameRes] mapping, plus parity between that list, the
 * system per-app locale config (res/xml/locales_config.xml), and the actual
 * values-<locale>/strings_compose.xml translation folders.
 *
 * No Robolectric: R.string.* are plain int constants and the parity checks are file
 * IO against the module's res/ tree.
 */
class LanguagePickerTest {

    @Test
    fun `tag list is system-default first then the supported tags in order`() {
        assertThat(LANGUAGE_TAGS.first()).isEmpty()
        assertThat(LANGUAGE_TAGS.drop(1))
            .containsExactlyElementsIn(SUPPORTED_LANGUAGES.map { it.tag })
            .inOrder()
    }

    @Test
    fun `supported tags are unique`() {
        val tags = SUPPORTED_LANGUAGES.map { it.tag }
        assertThat(tags).containsNoDuplicates()
    }

    @Test
    fun `no tag is a prefix of another (prefix matching stays unambiguous)`() {
        // currentLanguageNameRes() resolves by startsWith; if one tag prefixed
        // another the earlier list entry could shadow the later one.
        val tags = SUPPORTED_LANGUAGES.map { it.tag }
        for (a in tags) {
            for (b in tags) {
                if (a !== b && b.startsWith(a)) {
                    throw AssertionError("Tag '$a' is a prefix of '$b'")
                }
            }
        }
    }

    @Test
    fun `currentLanguageNameRes resolves each exact tag to its display name`() {
        for (lang in SUPPORTED_LANGUAGES) {
            assertThat(currentLanguageNameRes(lang.tag)).isEqualTo(lang.nameRes)
        }
    }

    @Test
    fun `currentLanguageNameRes resolves region-qualified tags by language prefix`() {
        assertThat(currentLanguageNameRes("en-US")).isEqualTo(R.string.language_name_en)
        assertThat(currentLanguageNameRes("es-419")).isEqualTo(R.string.language_name_es)
        // zh-CN must not be shadowed by zh-TW (or vice versa).
        assertThat(currentLanguageNameRes("zh-CN")).isEqualTo(R.string.language_name_zh_cn)
        assertThat(currentLanguageNameRes("zh-TW")).isEqualTo(R.string.language_name_zh_tw)
    }

    @Test
    fun `currentLanguageNameRes falls back to system default for empty or unknown tags`() {
        assertThat(currentLanguageNameRes("")).isEqualTo(R.string.settings_language_system)
        assertThat(currentLanguageNameRes("de")).isEqualTo(R.string.settings_language_system)
    }

    @Test
    fun `locales_config lists exactly the supported tags`() {
        val configTags = Regex("""android:name="([^"]+)"""")
            .findAll(resFile("xml/locales_config.xml").readText())
            .map { it.groupValues[1] }
            .toList()
        assertThat(configTags)
            .containsExactlyElementsIn(SUPPORTED_LANGUAGES.map { it.tag })
    }

    @Test
    fun `every supported language has a translation folder with strings_compose`() {
        for (lang in SUPPORTED_LANGUAGES) {
            // English is the default values/ dir; the others get a values-<qualifier>.
            val rel = when (lang.tag) {
                "en" -> "values/strings_compose.xml"
                else -> "${valuesDir(lang.tag)}/strings_compose.xml"
            }
            val f = resFile(rel)
            assertThat(f.exists()).isTrue()
            // Non-empty and contains at least one real string entry.
            assertThat(f.readText()).contains("<string name=")
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Android resource-qualifier folder for a BCP-47 tag. */
    private fun valuesDir(tag: String): String = when (tag) {
        "zh-CN" -> "values-zh-rCN"
        "zh-TW" -> "values-zh-rTW"
        "id" -> "values-in" // legacy Indonesian qualifier AAPT2 canonicalizes to
        else -> "values-$tag"
    }

    /** Resolve a path under src/main/res regardless of the test working dir. */
    private fun resFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return@repeat
            for (base in listOf(File(d, "src/main/res"), File(d, "ft8cn/app/src/main/res"))) {
                if (base.isDirectory) return File(base, rel)
            }
            dir = d.parentFile
        }
        throw IllegalStateException("Could not locate src/main/res from ${File("").absolutePath}")
    }
}
