package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests for [rigTakeoverMessage], the "took the radio over" toast text. */
class RigTakeoverMessageTest {

    private val template = "Took the radio over from %1\$s"

    @Test
    fun namesTheSiblingThatReleasedTheRig() {
        assertThat(rigTakeoverMessage(template, "radio.ks3ckc.sstvaf"))
            .isEqualTo("Took the radio over from SSTVAF")
    }

    @Test
    fun noMessageWhenNobodyConfirmedReleasing() {
        // We came back for a rig we had lent out; no sibling answered, so there is
        // nobody to name and nothing worth interrupting the operator for.
        assertThat(rigTakeoverMessage(template, null)).isNull()
    }
}
