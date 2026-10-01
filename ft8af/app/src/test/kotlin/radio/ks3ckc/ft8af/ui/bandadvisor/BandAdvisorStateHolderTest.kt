package radio.ks3ckc.ft8af.ui.bandadvisor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.bandadvisor.BandAdvisor
import radio.ks3ckc.ft8af.bandadvisor.model.AdvisorTargetRegion
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlagRepository
import radio.ks3ckc.ft8af.flags.FeatureFlags
import radio.ks3ckc.ft8af.flags.FlagEvaluation
import radio.ks3ckc.ft8af.flags.FlagValueSource

@RunWith(RobolectricTestRunner::class)
class BandAdvisorStateHolderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        FeatureFlags.setDelegateForTests(null)
        BandAdvisor.setUseFixtures(context, false)
        GeneralVariables.setMyMaidenheadGrid("")
    }

    @Test
    fun `goal and target changes replace pending request and persist selection`() =
        runBlocking {
            FeatureFlags.setDelegateForTests(
                object : FeatureFlagRepository {
                    override fun isEnabled(flag: FeatureFlag) = true

                    override fun evaluate(flag: FeatureFlag) = FlagEvaluation(flag, true, FlagValueSource.BUILD_DEFAULT)
                },
            )
            BandAdvisor.setUseFixtures(context, true)
            GeneralVariables.setMyMaidenheadGrid("EM28")
            val holder = BandAdvisorStateHolder(context)
            holder.load(this, force = false)
            holder.setGoal(this, OperatingGoal.TARGET)
            coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertThat((holder.state as BandAdvisorUiState.Ready).recommendation.targetRegion).isEqualTo("EUROPE")
            holder.setTargetRegion(this, AdvisorTargetRegion.ASIA)
            coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertThat((holder.state as BandAdvisorUiState.Ready).recommendation.targetRegion).isEqualTo("ASIA")
            val restored = BandAdvisorStateHolder(context)
            assertThat(restored.goal).isEqualTo(OperatingGoal.TARGET)
            assertThat(restored.targetRegion).isEqualTo(AdvisorTargetRegion.ASIA)
            holder.setTargetRegion(this, AdvisorTargetRegion.ASIA)
            holder.setGoal(this, OperatingGoal.TARGET)
            assertThat(coroutineContext[Job]!!.children.toList()).isEmpty()
        }
}
