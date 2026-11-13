package com.k1af.ft8af.message;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.k1af.ft8af.message.SpecialMessage.IaruRegion;
import com.k1af.ft8af.message.SpecialMessage.Kind;
import com.k1af.ft8af.message.SpecialMessage.Parsed;
import com.k1af.ft8af.message.SpecialMessage.QsyBand;

import org.junit.Test;

/** Pure-JVM tests for {@link SpecialMessage} (no Android runtime needed). */
public class SpecialMessageTest {

    // ---- regionFromNumber ----

    @Test
    public void regionFromNumber_mapsKnownValues() {
        assertThat(SpecialMessage.regionFromNumber(1)).isEqualTo(IaruRegion.REGION_1);
        assertThat(SpecialMessage.regionFromNumber(2)).isEqualTo(IaruRegion.REGION_2);
        assertThat(SpecialMessage.regionFromNumber(3)).isEqualTo(IaruRegion.REGION_3);
    }

    @Test
    public void regionFromNumber_defaultsToRegion2() {
        assertThat(SpecialMessage.regionFromNumber(0)).isEqualTo(IaruRegion.REGION_2);
        assertThat(SpecialMessage.regionFromNumber(9)).isEqualTo(IaruRegion.REGION_2);
        assertThat(SpecialMessage.regionFromNumber(-1)).isEqualTo(IaruRegion.REGION_2);
    }

    // ---- buildQsyRequest ----

    @Test
    public void buildQsyRequest_formatsCallBandOffset() {
        String msg = SpecialMessage.buildQsyRequest("k1abc", "20", 74, IaruRegion.REGION_2);
        assertThat(msg).isEqualTo("K1ABC.20 074");
        assertThat(msg.length()).isAtMost(SpecialMessage.FREE_TEXT_LIMIT);
    }

    @Test
    public void buildQsyRequest_zeroPadsOffset() {
        assertThat(SpecialMessage.buildQsyRequest("W5X", "6", 5, IaruRegion.REGION_2))
                .isEqualTo("W5X.6 005");
    }

    @Test
    public void buildQsyRequest_rejectsMissingCall() {
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("  ", "20", 74, IaruRegion.REGION_2));
    }

    @Test
    public void buildQsyRequest_rejectsNullRegion() {
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("K1ABC", "20", 74, null));
    }

    @Test
    public void buildQsyRequest_rejectsUnknownBand() {
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("K1ABC", "99", 74, IaruRegion.REGION_2));
    }

    @Test
    public void buildQsyRequest_rejectsOffsetAboveRegionEdge() {
        // 40m in Region 1 stops at +200 (7.200 MHz); +250 is legal in Region 2.
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("K1ABC", "40", 250, IaruRegion.REGION_1));
        assertThat(SpecialMessage.buildQsyRequest("K1ABC", "40", 250, IaruRegion.REGION_2))
                .isEqualTo("K1ABC.40 250");
    }

    @Test
    public void buildQsyRequest_rejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("K1ABC", "20", -1, IaruRegion.REGION_2));
    }

    @Test
    public void buildQsyRequest_rejectsWhenTooLongForCall() {
        // "KA1ABC.160 074" is 14 chars > 13.
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequest("KA1ABC", "160", 74, IaruRegion.REGION_2));
    }

    // ---- buildQsyRequestForFreqKhz ----

    @Test
    public void buildQsyRequestForFreq_resolvesBand() {
        assertThat(SpecialMessage.buildQsyRequestForFreqKhz("K1ABC", 14074, IaruRegion.REGION_2))
                .isEqualTo("K1ABC.20 074");
        assertThat(SpecialMessage.buildQsyRequestForFreqKhz("K1ABC", 50313, IaruRegion.REGION_2))
                .isEqualTo("K1ABC.6 313");
    }

    @Test
    public void buildQsyRequestForFreq_rejectsOutOfBandFreq() {
        // 13000 kHz is between 30m and 20m — not in any allocation.
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildQsyRequestForFreqKhz("K1ABC", 13000, IaruRegion.REGION_2));
    }

    // ---- buildCannedMessage ----

    @Test
    public void buildCanned_addressesTheCall() {
        assertThat(SpecialMessage.buildCannedMessage("k1abc", "73 GL")).isEqualTo("K1ABC 73 GL");
    }

    @Test
    public void buildCanned_rejectsEmptyText() {
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildCannedMessage("K1ABC", "  "));
    }

    @Test
    public void buildCanned_rejectsWhenTooLong() {
        // "KA1XYZ SRI QRM" is 14 chars > 13.
        assertThrows(IllegalArgumentException.class,
                () -> SpecialMessage.buildCannedMessage("KA1XYZ", "SRI QRM"));
    }

    // ---- fitsAddressed ----

    @Test
    public void fitsAddressed_accountsForSeparator() {
        // "K1ABC" (5) + sep (1) + payload(6) = 12 <= 13.
        assertThat(SpecialMessage.fitsAddressed("K1ABC", 6)).isTrue();
        // "KA1ABC" (6) + 1 + 7 = 14 > 13.
        assertThat(SpecialMessage.fitsAddressed("KA1ABC", 7)).isFalse();
        assertThat(SpecialMessage.fitsAddressed("", 1)).isFalse();
    }

    // ---- bands / region filtering ----

    @Test
    public void bandsForRegion_allBandsAllocatedEverywhere() {
        // Every band in the table is allocated in all three regions.
        assertThat(SpecialMessage.bandsForRegion(IaruRegion.REGION_1))
                .hasSize(SpecialMessage.allBands().size());
    }

    @Test
    public void bandByCode_knownAndUnknown() {
        QsyBand b = SpecialMessage.bandByCode("20");
        assertThat(b).isNotNull();
        assertThat(b.baseKhz).isEqualTo(14000L);
        assertThat(b.meters).isEqualTo("20m");
        assertThat(SpecialMessage.bandByCode("99")).isNull();
        assertThat(SpecialMessage.bandByCode(null)).isNull();
    }

    @Test
    public void maxOffsetForRegion_cappedAt999AndRegionEdge() {
        QsyBand ten = SpecialMessage.bandByCode("10");
        assertThat(ten.maxOffsetForRegion(IaruRegion.REGION_2)).isEqualTo(999);
        QsyBand forty = SpecialMessage.bandByCode("40");
        assertThat(forty.maxOffsetForRegion(IaruRegion.REGION_1)).isEqualTo(200);
        assertThat(forty.maxOffsetForRegion(IaruRegion.REGION_2)).isEqualTo(300);
    }

    // ---- parse: QSY ----

    @Test
    public void parse_recognisesQsyRequest() {
        Parsed p = SpecialMessage.parse("K1ABC.20 074");
        assertThat(p).isNotNull();
        assertThat(p.kind).isEqualTo(Kind.QSY);
        assertThat(p.toCall).isEqualTo("K1ABC");
        assertThat(p.bandCode).isEqualTo("20");
        assertThat(p.meters).isEqualTo("20m");
        assertThat(p.freqKhz).isEqualTo(14074L);
        assertThat(p.freqMhz()).isEqualTo("14.074");
        assertThat(p.summary()).isEqualTo("20m 14.074");
    }

    @Test
    public void parse_handlesPaddedFreeTextField() {
        // getMessageText() right-pads free text to 13 chars; parse must cope.
        Parsed p = SpecialMessage.parse("K1ABC.6 313  ");
        assertThat(p).isNotNull();
        assertThat(p.kind).isEqualTo(Kind.QSY);
        assertThat(p.freqKhz).isEqualTo(50313L);
    }

    @Test
    public void parse_qsyIsCaseInsensitive() {
        Parsed p = SpecialMessage.parse("w5xyz.40 074");
        assertThat(p).isNotNull();
        assertThat(p.toCall).isEqualTo("W5XYZ");
        assertThat(p.freqKhz).isEqualTo(7074L);
    }

    @Test
    public void parse_rejectsUnknownBandCode() {
        assertThat(SpecialMessage.parse("K1ABC.99 074")).isNull();
    }

    @Test
    public void parse_rejectsNonNumericOffset() {
        assertThat(SpecialMessage.parse("K1ABC.20 ABC")).isNull();
    }

    @Test
    public void parse_rejectsImplausibleCall() {
        // No digit in the call field -> not a callsign.
        assertThat(SpecialMessage.parse("ABCDE.20 074")).isNull();
    }

    // ---- parse: canned ----

    @Test
    public void parse_recognisesCanned() {
        Parsed p = SpecialMessage.parse("K1ABC 73 GL");
        assertThat(p).isNotNull();
        assertThat(p.kind).isEqualTo(Kind.CANNED);
        assertThat(p.toCall).isEqualTo("K1ABC");
        assertThat(p.text).isEqualTo("73 GL");
        assertThat(p.summary()).isEqualTo("73 GL");
    }

    @Test
    public void parse_rejectsUnknownCannedPayload() {
        // "HELLO WORLD" is not a known canned message.
        assertThat(SpecialMessage.parse("K1ABC HELLO")).isNull();
    }

    @Test
    public void parse_rejectsNullEmptyAndNoSpace() {
        assertThat(SpecialMessage.parse(null)).isNull();
        assertThat(SpecialMessage.parse("   ")).isNull();
        assertThat(SpecialMessage.parse("K1ABC")).isNull();
        assertThat(SpecialMessage.parse("K1ABC ")).isNull();
    }

    // ---- round trip ----

    @Test
    public void buildThenParse_roundTripsQsy() {
        String msg = SpecialMessage.buildQsyRequest("K1ABC", "15", 74, IaruRegion.REGION_2);
        Parsed p = SpecialMessage.parse(msg);
        assertThat(p.kind).isEqualTo(Kind.QSY);
        assertThat(p.freqKhz).isEqualTo(21074L);
    }

    @Test
    public void buildThenParse_roundTripsCanned() {
        String msg = SpecialMessage.buildCannedMessage("K1ABC", "RR73");
        Parsed p = SpecialMessage.parse(msg);
        assertThat(p.kind).isEqualTo(Kind.CANNED);
        assertThat(p.text).isEqualTo("RR73");
    }

    // ---- parseFor ----

    @Test
    public void parseFor_returnsMessageAddressedToMe() {
        Parsed p = SpecialMessage.parseFor("K1ABC.20 074", "k1abc");
        assertThat(p).isNotNull();
        assertThat(p.kind).isEqualTo(Kind.QSY);
    }

    @Test
    public void parseFor_ignoresMessageForOtherCall() {
        assertThat(SpecialMessage.parseFor("K1ABC.20 074", "W5XYZ")).isNull();
    }

    @Test
    public void parseFor_ignoresNonSpecialAndBlankMyCall() {
        assertThat(SpecialMessage.parseFor("TNX BOB 73", "K1ABC")).isNull();
        assertThat(SpecialMessage.parseFor("K1ABC.20 074", "")).isNull();
    }

    // ---- isCanned ----

    @Test
    public void isCanned_matchesKnownMessages() {
        assertThat(SpecialMessage.isCanned("73")).isTrue();
        assertThat(SpecialMessage.isCanned(" rr73 ")).isTrue();
        assertThat(SpecialMessage.isCanned("not canned")).isFalse();
        assertThat(SpecialMessage.isCanned(null)).isFalse();
    }

    @Test
    public void cannedMessages_allFitWithAShortCall() {
        // Every canned message must fit alongside a 4-char call in the 13-char frame.
        for (String canned : SpecialMessage.CANNED_MESSAGES) {
            assertThat(("K1AB " + canned).length()).isAtMost(SpecialMessage.FREE_TEXT_LIMIT);
        }
    }
}
