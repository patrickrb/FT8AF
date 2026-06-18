package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guard test for the release R8 configuration.
 *
 * Enabling minification on the release build is only safe because
 * proguard-rules.pro keeps the names the native libft8af.so (and a couple of
 * reflection sites) look up by string. If someone deletes minifyEnabled or one
 * of those keep rules, the app still *builds* — it just silently breaks decode
 * (Ft8Message fields obfuscated), USB audio capture (callback methods renamed),
 * USB serial probing (driver ctor/getSupportedDevices stripped), or POTA login
 * (AWS Cognito SDK shrunk). None of that is caught by a normal unit test, so
 * assert the configuration itself stays intact.
 *
 * Unit tests run with the working directory at the module root (ft8af/app), so
 * both files resolve relative to it.
 */
class R8KeepRulesTest {

    private val proguardRules: String by lazy { File("proguard-rules.pro").readText() }
    private val buildGradle: String by lazy { File("build.gradle").readText() }

    @Test
    fun releaseBuildEnablesR8() {
        // Strip comments so a commented-out directive can't satisfy the assertion.
        val active = buildGradle.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertThat(active).contains("minifyEnabled true")
    }

    @Test
    fun keepsFt8MessageFieldsForJniFieldAccess() {
        assertThat(proguardRules).contains("class com.k1af.ft8af.Ft8Message")
    }

    @Test
    fun keepsUsbAudioCallbackMethodsForJni() {
        assertThat(proguardRules)
            .contains("com.k1af.ft8af.wave.UsbAudioNative\$AudioInputCallback")
        assertThat(proguardRules).contains("onAudioData(float[], int)")
        assertThat(proguardRules).contains("onCaptureStopped(int)")
    }

    @Test
    fun keepsReflectivelyInstantiatedUsbSerialDrivers() {
        assertThat(proguardRules)
            .contains("implements com.k1af.ft8af.serialport.UsbSerialDriver")
        assertThat(proguardRules).contains("<init>(android.hardware.usb.UsbDevice)")
        assertThat(proguardRules).contains("getSupportedDevices()")
    }

    @Test
    fun keepsAwsCognitoSdk() {
        assertThat(proguardRules).contains("-keep class com.amazonaws.** { *; }")
    }

    @Test
    fun keepsLineNumbersSoMappingDeobfuscatesStackTraces() {
        assertThat(proguardRules).contains("-keepattributes SourceFile,LineNumberTable")
    }
}
