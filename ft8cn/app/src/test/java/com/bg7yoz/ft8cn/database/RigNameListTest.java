package com.bg7yoz.ft8cn.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for {@link RigNameList.RigName}'s string parser and the
 * static {@link RigNameList#getLinesFromInputStream(InputStream, String)} helper.
 *
 * The instance helpers (getRigNameByIndex / getIndexByAddress / getRigNamesFromFile)
 * require a Context-backed RigNameList and AssetManager, so they are not exercised
 * here. RigName.getName() is also skipped because the empty-model branch reaches a
 * R.string resource via GeneralVariables.
 *
 * Plain JUnit: the tested paths touch no Android types.
 */
public class RigNameListTest {

    @Test
    public void rigNameStringConstructor_parsesHexAddressAndDecimalFields() {
        // Format: "ICOM IC-705,A4,19200,0" — address is hex, baud + set decimal.
        RigNameList.RigName r = new RigNameList.RigName("ICOM IC-705,A4,19200,0");
        assertThat(r.modelName).isEqualTo("ICOM IC-705");
        assertThat(r.address).isEqualTo(0xA4);   // 164
        assertThat(r.bauRate).isEqualTo(19200);
        assertThat(r.instructionSet).isEqualTo(0);
    }

    @Test
    public void rigNameStringConstructor_trimsWhitespaceAroundFields() {
        RigNameList.RigName r = new RigNameList.RigName(" Yaesu FT-991 , 1F , 38400 , 2 ");
        assertThat(r.modelName).isEqualTo("Yaesu FT-991");
        assertThat(r.address).isEqualTo(0x1F);   // 31
        assertThat(r.bauRate).isEqualTo(38400);
        assertThat(r.instructionSet).isEqualTo(2);
    }

    @Test
    public void rigNameStringConstructor_tooFewFields_fallsBackToDefaults() {
        // Fewer than 4 comma-separated fields → default rig (addr 0xA4, 19200).
        RigNameList.RigName r = new RigNameList.RigName("ICOM IC-705,A4");
        assertThat(r.modelName).isEmpty();
        assertThat(r.address).isEqualTo(0xA4);
        assertThat(r.bauRate).isEqualTo(19200);
        assertThat(r.instructionSet).isEqualTo(0);
    }

    @Test
    public void rigNameFieldConstructor_assignsAllFields() {
        RigNameList.RigName r = new RigNameList.RigName("Model X", 0x70, 9600, 1);
        assertThat(r.modelName).isEqualTo("Model X");
        assertThat(r.address).isEqualTo(0x70);
        assertThat(r.bauRate).isEqualTo(9600);
        assertThat(r.instructionSet).isEqualTo(1);
    }

    @Test
    public void rigNameGetName_returnsModelWhenNonEmpty() {
        // Non-empty model name short-circuits before the R.string branch.
        RigNameList.RigName r = new RigNameList.RigName("ICOM IC-7300", 0x94, 19200, 0);
        assertThat(r.getName()).isEqualTo("ICOM IC-7300");
    }

    @Test
    public void getLinesFromInputStream_splitsOnNewline() {
        InputStream in = new ByteArrayInputStream(
                "ICOM IC-705,A4,19200,0\nYaesu FT-991,1F,38400,2"
                        .getBytes(StandardCharsets.UTF_8));
        String[] lines = RigNameList.getLinesFromInputStream(in, "\n");
        assertThat(lines).asList().hasSize(2);
        assertThat(lines[0]).isEqualTo("ICOM IC-705,A4,19200,0");
        assertThat(lines[1]).isEqualTo("Yaesu FT-991,1F,38400,2");
    }

    @Test
    public void getLinesFromInputStream_singleLineNoDelimiter() {
        InputStream in = new ByteArrayInputStream("solo".getBytes(StandardCharsets.UTF_8));
        String[] lines = RigNameList.getLinesFromInputStream(in, "\n");
        assertThat(lines).asList().containsExactly("solo");
    }
}
