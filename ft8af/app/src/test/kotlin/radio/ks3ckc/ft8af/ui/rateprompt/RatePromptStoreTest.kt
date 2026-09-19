package radio.ks3ckc.ft8af.ui.rateprompt

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DataStore round-trip for the persisted rate-prompt state. Plain JVM — no Android. */
class RatePromptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun storeAt(file: File): RatePromptStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return RatePromptStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @Test
    fun `fresh store reads the default state`() = runBlocking {
        val store = storeAt(File(tmp.root, "rate_prompt.preferences_pb"))
        assertThat(store.read()).isEqualTo(RatePromptState())
    }

    @Test
    fun `update writes all three keys and returns the new state`() = runBlocking {
        val store = storeAt(File(tmp.root, "rate_prompt.preferences_pb"))
        val written = store.update { it.afterDecline(qsoCount = 5) }
        assertThat(written).isEqualTo(RatePromptState(declineCount = 1, nextEligibleQsoCount = 20))
        assertThat(store.read()).isEqualTo(written)

        store.update { it.afterDontAskAgain() }
        assertThat(store.read()).isEqualTo(RatePromptState(declineCount = 1, nextEligibleQsoCount = 20, resolved = true))
    }

    @Test
    fun `state survives reopening the file`() = runBlocking {
        val file = File(tmp.root, "rate_prompt.preferences_pb")
        storeAt(file).update { it.afterDecline(qsoCount = 9) }
        scopes.forEach { it.cancel() }
        scopes.clear()

        assertThat(storeAt(file).read())
            .isEqualTo(RatePromptState(declineCount = 1, nextEligibleQsoCount = 24))
    }
}
