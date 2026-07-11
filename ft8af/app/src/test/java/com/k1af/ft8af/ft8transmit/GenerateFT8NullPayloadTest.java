package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ModeProfile;

import org.junit.Test;

/**
 * Coverage for {@link GenerateFT8#generateFt8ByA91(byte[], float, int, ModeProfile)} when
 * the a91 payload is {@code null}.
 *
 * <p>{@link GenerateFT8#generateA91} returns {@code null} for an invalid own callsign
 * (fewer than 3 characters): it has already shown the "callsign_error" toast and the
 * transmit is meant to abort gracefully. {@link GenerateFT8#generateFt8}
 * ({@code generateFt8ByA91(generateA91(...), ...)}) then handed that {@code null} straight
 * to the native encoder ({@code ModeProfile.encode} → {@code ft8_encode}/{@code ft4_encode},
 * whose JNI wrappers call {@code GetArrayLength(null)}/{@code GetByteArrayRegion(null,...)}
 * with no null guard) → an uncatchable native SIGSEGV on the TX thread, defeating the
 * {@code buffer == null} / {@code data == null} checks every caller already has.
 *
 * <p>The fix returns {@code null} from {@code generateFt8ByA91} before touching the native
 * encoder, restoring the graceful-abort contract the callers were written against.
 *
 * <p>Plain JUnit: with a {@code null} payload the method returns before any native call, so
 * the missing {@code libft8af} in the JVM (swallowed by GenerateFT8's static initialiser)
 * is never reached. Before the fix these cases instead reached {@code ModeProfile.encode}
 * and threw {@link UnsatisfiedLinkError} in the JVM (SIGSEGV on device).
 */
public class GenerateFT8NullPayloadTest {

    @Test
    public void nullPayload_ft8_returnsNullWithoutNativeCall() {
        assertThat(GenerateFT8.generateFt8ByA91(null, 1500f, 12000, ModeProfile.FT8)).isNull();
    }

    @Test
    public void nullPayload_ft4_returnsNullWithoutNativeCall() {
        assertThat(GenerateFT8.generateFt8ByA91(null, 1500f, 12000, ModeProfile.FT4)).isNull();
    }

    @Test
    public void nullPayload_ft2_returnsNullWithoutNativeCall() {
        assertThat(GenerateFT8.generateFt8ByA91(null, 1500f, 12000, ModeProfile.FT2)).isNull();
    }

    /** The guard must hold regardless of frequency/sample-rate; a degenerate rate still returns null. */
    @Test
    public void nullPayload_isNullEvenForDegenerateRate() {
        assertThat(GenerateFT8.generateFt8ByA91(null, 0f, 0, ModeProfile.FT8)).isNull();
    }
}
