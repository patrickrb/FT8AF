package com.k1af.ft8af.handoff;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure-JVM tests for {@link RigHandoffProtocol}. */
public class RigHandoffProtocolTest {

    private static final String SELF = "radio.ks3ckc.sstvaf";
    private static final String FT8AF = "radio.ks3ckc.ft8af";
    private static final long NOW = 1_000_000L;

    @Test
    public void wireConstants_arePinned() {
        // These strings are the cross-app contract: changing one here without changing
        // it in every sibling app silently breaks the handoff.
        assertThat(RigHandoffProtocol.ACTION_TAKEOVER)
                .isEqualTo("radio.ks3ckc.rig.action.TAKEOVER");
        assertThat(RigHandoffProtocol.ACTION_RELEASED)
                .isEqualTo("radio.ks3ckc.rig.action.RELEASED");
        assertThat(RigHandoffProtocol.EXTRA_FROM_PACKAGE)
                .isEqualTo("radio.ks3ckc.rig.extra.FROM_PACKAGE");
        assertThat(RigHandoffProtocol.EXTRA_SENT_AT_MS)
                .isEqualTo("radio.ks3ckc.rig.extra.SENT_AT_MS");
        assertThat(RigHandoffProtocol.FAMILY_PACKAGES).containsExactly(
                "radio.ks3ckc.ft8af", "radio.ks3ckc.sstvaf",
                "radio.ks3ckc.rttyaf", "radio.ks3ckc.jttyaf").inOrder();
    }

    @Test
    public void siblingsOf_isEveryFamilyMemberButSelf() {
        assertThat(RigHandoffProtocol.siblingsOf(SELF)).containsExactly(
                "radio.ks3ckc.ft8af", "radio.ks3ckc.rttyaf", "radio.ks3ckc.jttyaf");
        // An app outside the family still addresses the whole family.
        assertThat(RigHandoffProtocol.siblingsOf("com.example.other")).hasSize(4);
    }

    @Test
    public void shouldHonor_freshMessageFromSibling() {
        assertThat(RigHandoffProtocol.shouldHonor(SELF, FT8AF, null, NOW, NOW)).isTrue();
        assertThat(RigHandoffProtocol.shouldHonor(SELF, FT8AF, FT8AF, NOW, NOW)).isTrue();
    }

    @Test
    public void shouldHonor_rejectsSelfUnknownAndMissingSender() {
        assertThat(RigHandoffProtocol.shouldHonor(SELF, SELF, null, NOW, NOW)).isFalse();
        assertThat(RigHandoffProtocol.shouldHonor(SELF, null, null, NOW, NOW)).isFalse();
        assertThat(RigHandoffProtocol.shouldHonor(SELF, "com.evil.app", null, NOW, NOW))
                .isFalse();
    }

    @Test
    public void shouldHonor_rejectsClaimThePlatformContradicts() {
        // Message says FT8AF, Android says it came from somewhere else: forged.
        assertThat(RigHandoffProtocol.shouldHonor(SELF, FT8AF, "com.evil.app", NOW, NOW))
                .isFalse();
    }

    @Test
    public void shouldHonor_rejectsStaleMessage() {
        // A broadcast held for a frozen process and delivered when it is next opened.
        long old = NOW - RigHandoffProtocol.MAX_MESSAGE_AGE_MS - 1;
        assertThat(RigHandoffProtocol.shouldHonor(SELF, FT8AF, null, old, NOW)).isFalse();
    }

    @Test
    public void isFresh_boundaries() {
        long max = RigHandoffProtocol.MAX_MESSAGE_AGE_MS;
        assertThat(RigHandoffProtocol.isFresh(NOW - max, NOW)).isTrue();
        assertThat(RigHandoffProtocol.isFresh(NOW - max - 1, NOW)).isFalse();
        assertThat(RigHandoffProtocol.isFresh(NOW + max, NOW)).isTrue();
        assertThat(RigHandoffProtocol.isFresh(NOW + max + 1, NOW)).isFalse();
        // No stamp at all (0) is never fresh.
        assertThat(RigHandoffProtocol.isFresh(0L, NOW)).isFalse();
    }

    @Test
    public void predatesOwnClaim_onlyWhenSentBeforeWeLastForegrounded() {
        assertThat(RigHandoffProtocol.predatesOwnClaim(NOW - 1, NOW)).isTrue();
        assertThat(RigHandoffProtocol.predatesOwnClaim(NOW, NOW)).isFalse();
        assertThat(RigHandoffProtocol.predatesOwnClaim(NOW + 1, NOW)).isFalse();
        // Never foregrounded this process (0): there is no claim to predate.
        assertThat(RigHandoffProtocol.predatesOwnClaim(NOW, 0L)).isFalse();
    }

    @Test
    public void displayName_isShortUppercaseForFamily_packageOtherwise() {
        assertThat(RigHandoffProtocol.displayName(FT8AF)).isEqualTo("FT8AF");
        assertThat(RigHandoffProtocol.displayName(SELF)).isEqualTo("SSTVAF");
        assertThat(RigHandoffProtocol.displayName("com.example.other"))
                .isEqualTo("com.example.other");
        assertThat(RigHandoffProtocol.displayName(null)).isEmpty();
    }
}
