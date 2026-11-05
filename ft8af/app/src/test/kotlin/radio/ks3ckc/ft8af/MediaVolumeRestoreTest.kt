package radio.ks3ckc.ft8af

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [MediaVolumeRestore] (issue #985): raise the phone's media volume
 * back to the saved level at startup and before TX, never lower it.
 *
 * Robolectric: [MediaVolumeRestore.restore] goes through a real [AudioManager].
 */
@RunWith(RobolectricTestRunner::class)
class MediaVolumeRestoreTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @After
    fun tearDown() {
        GeneralVariables.restoreMediaVolume = false
        MediaVolumeRestore.saveLevel(-1)
    }

    @Test
    fun `saveLevel updates the restore target and the observed level`() {
        MediaVolumeRestore.saveLevel(9)
        assertThat(GeneralVariables.restoreMediaVolumeLevel).isEqualTo(9)
        assertThat(MediaVolumeRestore.savedLevel.value).isEqualTo(9)

        // A second writer (the hardware volume keys) must reach the Settings row too.
        MediaVolumeRestore.saveLevel(4)
        assertThat(MediaVolumeRestore.savedLevel.value).isEqualTo(4)
    }

    private val max get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun media() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun setMedia(level: Int) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)

    // raiseTarget

    @Test
    fun `raiseTarget raises a lowered volume to the saved level`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 3, saved = 12, max = 15)).isEqualTo(12)
    }

    @Test
    fun `raiseTarget leaves a volume at the saved level alone`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 12, saved = 12, max = 15)).isNull()
    }

    @Test
    fun `raiseTarget never lowers a volume the user turned up past the saved level`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 15, saved = 12, max = 15)).isNull()
    }

    @Test
    fun `raiseTarget does nothing with no saved level`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 0, saved = -1, max = 15)).isNull()
    }

    @Test
    fun `raiseTarget clamps a level saved on a device with a higher max`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 5, saved = 25, max = 15)).isEqualTo(15)
    }

    @Test
    fun `raiseTarget does nothing when the max can't be read`() {
        assertThat(MediaVolumeRestore.raiseTarget(current = 0, saved = 10, max = 0)).isNull()
    }

    // classify

    @Test
    fun `classify reports raised when the read-back reaches the target`() {
        assertThat(MediaVolumeRestore.classify(target = 12, after = 12))
            .isEqualTo(MediaVolumeRestore.Outcome.RAISED)
    }

    @Test
    fun `classify reports capped when the system held it lower`() {
        // Android's safe-volume limit: asked for 12, got the safe index 9.
        assertThat(MediaVolumeRestore.classify(target = 12, after = 9))
            .isEqualTo(MediaVolumeRestore.Outcome.CAPPED)
    }

    // description

    @Test
    fun `description shows the saved level when on`() {
        assertThat(MediaVolumeRestore.description("Base", "Saved 9 of 15", enabled = true, savedLevel = 9))
            .isEqualTo("Base\nSaved 9 of 15")
    }

    @Test
    fun `description hides the saved level when off or none saved`() {
        assertThat(MediaVolumeRestore.description("Base", "Saved", enabled = false, savedLevel = 9)).isEqualTo("Base")
        assertThat(MediaVolumeRestore.description("Base", "Saved", enabled = true, savedLevel = -1)).isEqualTo("Base")
    }

    // restore against AudioManager

    @Test
    fun `restore is a no-op while the setting is off`() {
        setMedia(2)
        GeneralVariables.restoreMediaVolumeLevel = 10
        assertThat(MediaVolumeRestore.restore(context, "test")).isEqualTo(MediaVolumeRestore.Outcome.DISABLED)
        assertThat(media()).isEqualTo(2)
    }

    @Test
    fun `restore needs a saved level`() {
        GeneralVariables.restoreMediaVolume = true
        assertThat(MediaVolumeRestore.restore(context, "test"))
            .isEqualTo(MediaVolumeRestore.Outcome.NO_SAVED_LEVEL)
    }

    @Test
    fun `restore raises a lowered media volume`() {
        setMedia(2)
        GeneralVariables.restoreMediaVolume = true
        GeneralVariables.restoreMediaVolumeLevel = 10
        assertThat(MediaVolumeRestore.restore(context, "test")).isEqualTo(MediaVolumeRestore.Outcome.RAISED)
        assertThat(media()).isEqualTo(10)
    }

    @Test
    fun `restore leaves a higher media volume alone`() {
        setMedia(max)
        GeneralVariables.restoreMediaVolume = true
        GeneralVariables.restoreMediaVolumeLevel = 5
        assertThat(MediaVolumeRestore.restore(context, "test")).isEqualTo(MediaVolumeRestore.Outcome.AT_LEVEL)
        assertThat(media()).isEqualTo(max)
    }

    @Test
    fun `restore without a context does nothing`() {
        GeneralVariables.restoreMediaVolume = true
        GeneralVariables.restoreMediaVolumeLevel = 5
        assertThat(MediaVolumeRestore.restore(null, "test")).isEqualTo(MediaVolumeRestore.Outcome.UNAVAILABLE)
    }

    @Test
    fun `currentLevel and maxLevel read the media stream`() {
        setMedia(7)
        assertThat(MediaVolumeRestore.currentLevel(context)).isEqualTo(7)
        assertThat(MediaVolumeRestore.maxLevel(context)).isEqualTo(max)
        assertThat(MediaVolumeRestore.currentLevel(null)).isEqualTo(-1)
        assertThat(MediaVolumeRestore.maxLevel(null)).isEqualTo(0)
    }
}
