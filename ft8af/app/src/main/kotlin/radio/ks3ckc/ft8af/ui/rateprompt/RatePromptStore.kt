package radio.ks3ckc.ft8af.ui.rateprompt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.ratePromptDataStore: DataStore<Preferences> by preferencesDataStore(name = "rate_prompt")

/**
 * DataStore-backed [RatePromptState]. Lives in its own preferences file so it
 * survives process death and app updates independently of the SQLite config table.
 */
internal class RatePromptStore(private val dataStore: DataStore<Preferences>) {

    suspend fun read(): RatePromptState =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
            .toState()

    /** Atomically rewrite the state with [transform]; returns the stored result. */
    suspend fun update(transform: (RatePromptState) -> RatePromptState): RatePromptState {
        var result = RatePromptState()
        dataStore.edit { prefs ->
            result = transform(prefs.toState())
            prefs[DECLINE_COUNT] = result.declineCount
            prefs[NEXT_ELIGIBLE_QSO_COUNT] = result.nextEligibleQsoCount
            prefs[RESOLVED] = result.resolved
        }
        return result
    }

    /**
     * Fire-and-forget [update] on a process-wide scope, so a dismissal is still
     * written if the screen that raised it leaves composition mid-write.
     */
    fun persist(transform: (RatePromptState) -> RatePromptState) {
        writeScope.launch { update(transform) }
    }

    private fun Preferences.toState() = RatePromptState(
        declineCount = this[DECLINE_COUNT] ?: 0,
        nextEligibleQsoCount = this[NEXT_ELIGIBLE_QSO_COUNT] ?: 0,
        resolved = this[RESOLVED] ?: false,
    )

    companion object {
        val DECLINE_COUNT = intPreferencesKey("ratePromptDeclineCount")
        val NEXT_ELIGIBLE_QSO_COUNT = intPreferencesKey("ratePromptNextEligibleQsoCount")
        val RESOLVED = booleanPreferencesKey("ratePromptResolved")

        private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun from(context: Context): RatePromptStore =
            RatePromptStore(context.applicationContext.ratePromptDataStore)
    }
}
