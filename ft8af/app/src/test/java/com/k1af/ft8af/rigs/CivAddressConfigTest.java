package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link CivAddressConfig} (issue #753): the {@code civ} config key is hex,
 * the Compose rig picker wrote decimal for three months, and the loader has to survive
 * both. Pure JUnit — no Android types.
 */
public class CivAddressConfigTest {

    // ---- encode --------------------------------------------------------------------

    @Test
    public void encode_isLowercaseHexWithoutPrefix() {
        assertThat(CivAddressConfig.encode(0xA4)).isEqualTo("a4");
        assertThat(CivAddressConfig.encode(0x94)).isEqualTo("94");
        assertThat(CivAddressConfig.encode(0x0E)).isEqualTo("e");
    }

    @Test
    public void encode_masksToOneByte() {
        assertThat(CivAddressConfig.encode(0x1A4)).isEqualTo("a4");
    }

    @Test
    public void encode_roundTripsThroughDecode() {
        for (int a = 0; a <= 0xFF; a++) {
            assertThat(CivAddressConfig.decode(CivAddressConfig.encode(a), -1)).isEqualTo(a);
        }
    }

    // ---- decode --------------------------------------------------------------------

    @Test
    public void decode_hex_asAlwaysStored() {
        assertThat(CivAddressConfig.decode("a4", 0)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("A4", 0)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode(" 5e ", 0)).isEqualTo(0x5E);
        assertThat(CivAddressConfig.decode("0xA4", 0)).isEqualTo(0xA4);
    }

    @Test
    public void decode_repairsDecimalWrittenByOldComposePicker() {
        // IC-705: 0xA4 == 164 -> stored "164" -> hex 0x164 overflows a byte -> decimal 164.
        assertThat(CivAddressConfig.decode("164", 0)).isEqualTo(0xA4);
        // IC-7300 0x94 == 148, IC-7000 0x70 == 112, IC-9700 0xA2 == 162, IC-7100 0x88 == 136
        assertThat(CivAddressConfig.decode("148", 0)).isEqualTo(0x94);
        assertThat(CivAddressConfig.decode("112", 0)).isEqualTo(0x70);
        assertThat(CivAddressConfig.decode("162", 0)).isEqualTo(0xA2);
        assertThat(CivAddressConfig.decode("136", 0)).isEqualTo(0x88);
    }

    @Test
    public void decode_twoDigitDecimal_isAmbiguous_readAsHex() {
        // "88" (an IC-706MKIIG's 0x58 in decimal) is also valid hex — decode alone can't
        // tell; reconcileWithModel settles it.
        assertThat(CivAddressConfig.decode("88", 0)).isEqualTo(0x88);
    }

    @Test
    public void decode_fallbacks() {
        assertThat(CivAddressConfig.decode(null, 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("", 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("   ", 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("zz", 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("0x", 0xA4)).isEqualTo(0xA4);
        // Too big in both bases.
        assertThat(CivAddressConfig.decode("999", 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("1a4", 0xA4)).isEqualTo(0xA4);
        assertThat(CivAddressConfig.decode("-1", 0xA4)).isEqualTo(0xA4);
    }

    // ---- reconcileWithModel ----------------------------------------------------------

    @Test
    public void reconcile_matchingModel_unchanged() {
        assertThat(CivAddressConfig.reconcileWithModel(0xA4, 0xA4)).isEqualTo(0xA4);
    }

    @Test
    public void reconcile_twoDigitDecimalTwinOfModel_isRepaired() {
        // Stored "88" for 0x58 (IC-706MKIIG) -> loaded 0x88; digits "88" in decimal == 0x58.
        assertThat(CivAddressConfig.reconcileWithModel(0x88, 0x58)).isEqualTo(0x58);
        // Stored "94" for 0x5E (IC-718).
        assertThat(CivAddressConfig.reconcileWithModel(0x94, 0x5E)).isEqualTo(0x5E);
        // Stored "40" for 0x28 (IC-725).
        assertThat(CivAddressConfig.reconcileWithModel(0x40, 0x28)).isEqualTo(0x28);
    }

    @Test
    public void reconcile_userOverride_isLeftAlone() {
        // Rig re-addressed to 0x5F on purpose while the model says 0xA4: not a decimal twin.
        assertThat(CivAddressConfig.reconcileWithModel(0x5F, 0xA4)).isEqualTo(0x5F);
        // Hex digits with a letter can't be a decimal twin at all.
        assertThat(CivAddressConfig.reconcileWithModel(0x9A, 0x62)).isEqualTo(0x9A);
    }

    @Test
    public void reconcile_invalidModelAddress_isIgnored() {
        assertThat(CivAddressConfig.reconcileWithModel(0x88, -1)).isEqualTo(0x88);
        assertThat(CivAddressConfig.reconcileWithModel(0x88, 0x100)).isEqualTo(0x88);
    }

    @Test
    public void decimalTwin_helper() {
        assertThat(CivAddressConfig.decimalTwin("88")).isEqualTo(88);
        assertThat(CivAddressConfig.decimalTwin("a4")).isEqualTo(-1);
        assertThat(CivAddressConfig.decimalTwin("")).isEqualTo(-1);
        assertThat(CivAddressConfig.decimalTwin(null)).isEqualTo(-1);
    }
}
