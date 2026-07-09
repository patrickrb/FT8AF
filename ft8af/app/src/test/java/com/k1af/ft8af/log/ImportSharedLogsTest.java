package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link ImportSharedLogs#extractFieldValue(String, int)}, the ADIF
 * field-value clamp used by the shared-log importer (the VIEW/SEND intent path that
 * lets other apps hand a {@code .adi} file to FT8AF).
 *
 * The full {@link ImportSharedLogs#getLogRecords()} needs a {@code MainViewModel} and a
 * populated {@code fileContext}; the field-slicing decision is extracted into the pure
 * static {@code extractFieldValue} so it can be exercised directly (mirrors the
 * "extract the logic, test the logic" pattern used elsewhere in this module).
 */
public class ImportSharedLogsTest {

    @Test
    public void valueMatchesDeclaredLength_isReturnedVerbatim() {
        assertThat(ImportSharedLogs.extractFieldValue("W1AW", 4)).isEqualTo("W1AW");
    }

    @Test
    public void valueLongerThanDeclared_isTrimmedToDeclaredLength() {
        // The raw slice runs up to the next '<', so it usually carries trailing
        // whitespace/newline; the declared length trims it back to the real value.
        assertThat(ImportSharedLogs.extractFieldValue("W1AW\n", 4)).isEqualTo("W1AW");
        assertThat(ImportSharedLogs.extractFieldValue("FT8   ", 3)).isEqualTo("FT8");
    }

    @Test
    public void valueShorterThanDeclared_keepsEveryCharacter() {
        // Regression: the old `values[1].length() - 1` clamp dropped the last
        // character of a truncated field, turning "FN31" into "FN3".
        assertThat(ImportSharedLogs.extractFieldValue("FN31", 6)).isEqualTo("FN31");
        assertThat(ImportSharedLogs.extractFieldValue("FN3", 4)).isEqualTo("FN3");
    }

    @Test
    public void declaredLengthOneLongerThanValue_keepsWholeValue() {
        // The exact case LogFileImportTest documents for the twin parser:
        // <station_callsign:5> declared for the 4-char value "W1AW".
        assertThat(ImportSharedLogs.extractFieldValue("W1AW", 5)).isEqualTo("W1AW");
    }

    @Test
    public void singleCharacterValueShorterThanDeclared_isNotEmptied() {
        // Old clamp turned this into "" (length 1 - 1 = 0); the value must survive.
        assertThat(ImportSharedLogs.extractFieldValue("X", 5)).isEqualTo("X");
    }
}
