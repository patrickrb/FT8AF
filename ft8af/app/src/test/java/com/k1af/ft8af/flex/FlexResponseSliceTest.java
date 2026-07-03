package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for parsing the slice index out of a Flex "slice create"
 * response. The app must own the slice the radio actually assigns (not a hardcoded
 * 0); this is the parse that captures that index. See
 * {@link com.k1af.ft8af.flex.FlexRadio.FlexResponse#getSliceIndex(String)} and its
 * use in FlexConnector.onResponse(SLICE_CREATE_FREQ).
 */
public class FlexResponseSliceTest {

    @Test
    public void parsesIndexFromSuccessfulCreate() {
        // R<seq>|0|<index> — result 0 = success, third field is the slice number.
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0|0")).isEqualTo(0);
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0|1")).isEqualTo(1);
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0|2")).isEqualTo(2);
    }

    @Test
    public void toleratesWhitespaceAroundIndex() {
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0| 1 ")).isEqualTo(1);
    }

    @Test
    public void returnsMinusOneOnErrorResult() {
        // Non-zero result code = the create failed; there is no valid index.
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|50000005|0")).isEqualTo(-1);
    }

    @Test
    public void returnsMinusOneOnNonNumericIndex() {
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0|abc")).isEqualTo(-1);
    }

    @Test
    public void returnsMinusOneWhenIndexMissing() {
        assertThat(FlexRadio.FlexResponse.getSliceIndex("R30030|0")).isEqualTo(-1);
        assertThat(FlexRadio.FlexResponse.getSliceIndex("")).isEqualTo(-1);
    }
}
