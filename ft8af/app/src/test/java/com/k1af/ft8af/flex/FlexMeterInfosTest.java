package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexMeterInfos#setMeterInfos(String)} — the crash
 * fix for a truncated/garbled FlexRadio {@code METER_LIST} response.
 *
 * <p>The meter list is parsed from {@code FlexResponse.exContent}, which arrives
 * on the FlexRadio TCP read thread ({@link RadioTcpClient}). That read loop
 * catches only {@code SocketException}/{@code IOException}, so any
 * {@link RuntimeException} thrown while parsing escapes and crashes the whole
 * app.
 *
 * <p>The old code did
 * {@code content.substring(content.indexOf("meter ") + 6)} with no guard: when
 * {@code content} does not contain {@code "meter "} (a corrupt or partial
 * frame), {@code indexOf} returns {@code -1}, so the offset is {@code 5} and a
 * short frame throws {@link StringIndexOutOfBoundsException}. These tests pin
 * the guarded behaviour and confirm well-formed frames still parse.
 *
 * <p>{@link FlexMeterInfos} touches no Android types, so no Robolectric runner
 * is needed.
 */
public class FlexMeterInfosTest {

    /** A representative well-formed meter-list frame. */
    private static final String METER_LIST =
            "meter 1.src=RAD#1.num=1#1.nam=LEVEL#1.low=-140.0#1.hi=20.0#1.unit=dBm"
                    + "#2.src=RAD#2.num=2#2.nam=SWR#2.hi=3.0#2.unit=SWR"
                    + "#3.src=RAD#3.num=3#3.nam=FWDPWR#3.unit=dBm"
                    + "#4.src=RAD#4.num=4#4.nam=ALC#4.unit=dBm"
                    + "#5.src=RAD#5.num=5#5.nam=PATEMP#5.unit=degC";

    @Test
    public void parsesWellFormedMeterList() {
        FlexMeterInfos infos = new FlexMeterInfos(METER_LIST);
        assertThat(infos).hasSize(5);
        assertThat(infos.sMeterId).isEqualTo(1);
        assertThat(infos.swrId).isEqualTo(2);
        assertThat(infos.pwrId).isEqualTo(3);
        assertThat(infos.alcId).isEqualTo(4);
        assertThat(infos.tempCId).isEqualTo(5);
        FlexMeterInfos.FlexMeterInfo swr = infos.get(2);
        assertThat(swr.nam).isEqualTo("SWR");
        assertThat(swr.unit).isEqualTo(FlexMeterType.swr);
        assertThat(swr.hi).isEqualTo(3.0f);
    }

    @Test
    public void emptyContent_isNoOp() {
        FlexMeterInfos infos = new FlexMeterInfos("");
        assertThat(infos).isEmpty();
    }

    @Test
    public void shortFrameWithoutMeterKeyword_doesNotCrash() {
        // Regression: indexOf("meter ") == -1 -> offset 5 -> substring(5) on a
        // 1..4 char frame threw StringIndexOutOfBoundsException on the read thread.
        FlexMeterInfos infos = new FlexMeterInfos("0");
        assertThat(infos).isEmpty();
    }

    @Test
    public void frameWithoutMeterKeyword_isIgnored() {
        // A non-meter frame must not be parsed as meter definitions, even when it
        // is long enough that substring(5) would not have thrown.
        FlexMeterInfos infos = new FlexMeterInfos("handle 0x1234#foo.bar=baz");
        assertThat(infos).isEmpty();
        assertThat(infos.sMeterId).isEqualTo(-1);
    }

    @Test
    public void meterKeywordAtEnd_doesNotCrash() {
        // "meter " at the very end -> substring(len) == "" must parse to nothing.
        FlexMeterInfos infos = new FlexMeterInfos("status meter ");
        assertThat(infos).isEmpty();
    }

    @Test
    public void reparseUpdatesExistingDefinitions() {
        FlexMeterInfos infos = new FlexMeterInfos("meter 1.nam=SWR");
        assertThat(infos.swrId).isEqualTo(1);
        // A follow-up frame for the same id must update in place, not duplicate.
        infos.setMeterInfos("meter 1.unit=SWR");
        assertThat(infos).hasSize(1);
        assertThat(infos.get(1).unit).isEqualTo(FlexMeterType.swr);
    }
}
