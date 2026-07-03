package com.k1af.ft8af

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Resolution of "<...>" (hashed) callsigns through [Ft8Message.hashList] in the
 * Ft8Message copy constructor — issue #392: an operator signing a nonstandard
 * call (SV8/DM5HF) saw every reply as "<...>" because the decoder emitted the
 * placeholder and the hash fields carried no received bits to match against
 * the seeded own-call hashes.
 *
 * The hash values here are arbitrary stand-ins: the map lookup only needs the
 * seeded key and the message field to agree, which is what the native layer
 * now guarantees by copying the over-the-air bits into callTo/FromHash10/12/22
 * (see ft8_hashfields.h; the bit-level agreement is host-tested in
 * test_hashfields.c).
 */
@RunWith(RobolectricTestRunner::class)
class Ft8MessageHashResolveTest {

    // n22-style stand-ins: h12 = h22 >> 10, h10 = h22 >> 12, as the native
    // derivation produces them.
    private val h22 = 0x2A5F3CL
    private val h12 = h22 shr 10
    private val h10 = h22 shr 12

    @Before
    fun clearHashList() {
        Ft8Message.hashList.clear()
    }

    private fun receivedMessage(): Ft8Message {
        val msg = Ft8Message(FT8Common.FT8_MODE)
        msg.i3 = 1 // plain standard message; i3=4 would re-hash via native code
        msg.callsignTo = "DL1ABC"
        msg.callsignFrom = "DL1ABC"
        return msg
    }

    @Test
    fun resolvesHashedCallsignTo_fromH22() {
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF")
        val src = receivedMessage()
        src.callsignTo = "<...>"
        src.callToHash22 = h22
        src.callToHash12 = h12
        src.callToHash10 = h10

        val copy = Ft8Message(src)

        assertThat(copy.callsignTo).isEqualTo("<SV8/DM5HF>")
        assertThat(copy.getCallsignTo()).isEqualTo("SV8/DM5HF")
    }

    @Test
    fun resolvesHashedCallsignFrom_fromH12_whenH22Unknown() {
        // An i3=4 message carries only the 12-bit hash; h22 arrives as 0.
        Ft8Message.hashList.addHash(h12, "SV8/DM5HF")
        val src = receivedMessage()
        src.callsignFrom = "<...>"
        src.callFromHash22 = 0
        src.callFromHash12 = h12
        src.callFromHash10 = h10

        val copy = Ft8Message(src)

        assertThat(copy.callsignFrom).isEqualTo("<SV8/DM5HF>")
    }

    @Test
    fun prefersWidestHash_overColliding10BitEntry() {
        // A 10-bit hash has 1024 buckets, so an unrelated callsign can occupy
        // this message's h10 value. The 22-bit match must win.
        Ft8Message.hashList.addHash(h10, "K1COLLIDE")
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF")
        val src = receivedMessage()
        src.callsignTo = "<...>"
        src.callToHash22 = h22
        src.callToHash12 = h12
        src.callToHash10 = h10

        val copy = Ft8Message(src)

        assertThat(copy.callsignTo).isEqualTo("<SV8/DM5HF>")
    }

    @Test
    fun staysUnresolved_whenNoHashMatches() {
        val src = receivedMessage()
        src.callsignTo = "<...>"
        src.callToHash22 = h22
        src.callToHash12 = h12
        src.callToHash10 = h10

        val copy = Ft8Message(src)

        assertThat(copy.callsignTo).isEqualTo("<...>")
    }

    @Test
    fun staysUnresolved_whenHashFieldsAreZero() {
        // The pre-fix native behavior: placeholder with zeroed hash fields.
        // Zero keys are never stored, so this must not accidentally resolve.
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF")
        val src = receivedMessage()
        src.callsignTo = "<...>"

        val copy = Ft8Message(src)

        assertThat(copy.callsignTo).isEqualTo("<...>")
    }

    @Test
    fun resolvedCompoundCall_matchesOwnCallsign() {
        // The full issue-#392 chain: reply addressed to the operator's hashed
        // compound call resolves and is recognized as "calling me".
        GeneralVariables.myCallsign = "SV8/DM5HF"
        Ft8Message.hashList.addHash(h22, "SV8/DM5HF")
        val src = receivedMessage()
        src.callsignTo = "<...>"
        src.callToHash22 = h22
        src.callToHash12 = h12
        src.callToHash10 = h10

        val copy = Ft8Message(src)

        assertThat(GeneralVariables.checkIsMyCallsign(copy.getCallsignTo())).isTrue()
    }
}
