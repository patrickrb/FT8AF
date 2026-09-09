package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexMeterList#setMeters(byte[], FlexMeterInfos)} —
 * the crash fix for a FlexRadio meter definition that carries a {@code .unit}
 * but no {@code .nam}.
 *
 * <p>Meter value frames arrive on the FlexRadio meter-stream read thread, whose
 * loop catches only {@code SocketException}/{@code IOException} (mirroring the
 * {@link FlexMeterInfos} crash fixed earlier). {@code setMeters} already guards
 * the unknown-meter case ({@code infos.get(val) == null}), but then
 * unconditionally dereferenced {@code meter.name.contains("PWR")}.
 * {@link FlexMeterInfos.FlexMeterInfo#nam} is {@code null} whenever the
 * {@code .nam} token was absent or empty in the meter-definition frame (e.g. a
 * partial/split {@code METER_LIST} frame that carried only {@code N.unit=...}),
 * so a dBm/swr meter with no name threw an uncaught
 * {@link NullPointerException} on the read thread and crashed the whole app.
 *
 * <p>{@link FlexMeterList} touches no Android types, so no Robolectric runner is
 * needed.
 */
public class FlexMeterListTest {

    /**
     * Encodes one meter (id + raw value) into the 4-byte-per-meter payload that
     * {@code setMeters} consumes. Both fields are big-endian 16-bit, matching
     * {@link VITA#readShortData(byte[], int)}.
     */
    private static byte[] meterPayload(int id, int rawValue) {
        return new byte[]{
                (byte) (id >> 8), (byte) id,
                (byte) (rawValue >> 8), (byte) rawValue,
        };
    }

    @Test
    public void dbmMeterWithoutName_doesNotCrash() {
        // A meter definition that carried .unit=dBm but no .nam token: nam stays
        // null while unit becomes dBm. Reaching the dBm branch used to NPE on
        // meter.name.contains("PWR").
        FlexMeterInfos infos = new FlexMeterInfos("meter 7.unit=dBm");
        assertThat(infos.get(7).nam).isNull();
        assertThat(infos.get(7).unit).isEqualTo(FlexMeterType.dBm);

        FlexMeterList meters = new FlexMeterList();
        meters.setMeters(meterPayload(7, 128), infos); // 128 / 128f == 1.0 dBm

        FlexMeterList.FlexMeter meter = meters.get(7);
        assertThat(meter).isNotNull();
        assertThat(meter.name).isNull();
        assertThat(meter.value).isEqualTo(1.0f);
    }

    @Test
    public void swrMeterWithoutName_doesNotCrash() {
        // The swr branch shares the meter.name.contains("PWR") dereference.
        FlexMeterInfos infos = new FlexMeterInfos("meter 3.unit=SWR");
        assertThat(infos.get(3).nam).isNull();
        assertThat(infos.get(3).unit).isEqualTo(FlexMeterType.swr);

        FlexMeterList meters = new FlexMeterList();
        meters.setMeters(meterPayload(3, 256), infos); // 256 / 128f == 2.0

        assertThat(meters.get(3).value).isEqualTo(2.0f);
    }

    @Test
    public void namedPwrMeter_stillConvertsDbmToPower() {
        // The PWR-name path must still work: FWDPWR is dBm and gets converted to
        // watts via 10^(dBm/10)/1000. 30 dBm -> 1.0 W.
        FlexMeterInfos infos = new FlexMeterInfos("meter 5.nam=FWDPWR#5.unit=dBm");
        FlexMeterList meters = new FlexMeterList();
        meters.setMeters(meterPayload(5, 30 * 128), infos); // 3840 / 128f == 30 dBm

        assertThat(meters.get(5).value).isWithin(1e-4f).of(1.0f);
    }

    @Test
    public void namedNonPwrDbmMeter_keepsRawDbm() {
        FlexMeterInfos infos = new FlexMeterInfos("meter 1.nam=LEVEL#1.unit=dBm");
        FlexMeterList meters = new FlexMeterList();
        meters.setMeters(meterPayload(1, -20 * 128), infos); // -2560 / 128f == -20 dBm

        assertThat(meters.get(1).value).isEqualTo(-20.0f);
    }

    @Test
    public void unknownMeterId_isSkipped() {
        // Pre-existing guard: a value frame for an id with no definition is
        // ignored rather than crashing.
        FlexMeterInfos infos = new FlexMeterInfos("meter 1.nam=LEVEL#1.unit=dBm");
        FlexMeterList meters = new FlexMeterList();
        meters.setMeters(meterPayload(99, 128), infos);
        assertThat(meters).isEmpty();
    }
}
