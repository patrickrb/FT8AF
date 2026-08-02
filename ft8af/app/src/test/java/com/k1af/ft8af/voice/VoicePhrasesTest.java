package com.k1af.ft8af.voice;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure-JVM tests for {@link VoicePhrases} (no Android runtime needed). */
public class VoicePhrasesTest {

    // ---- spellCallsign ---------------------------------------------------

    @Test
    public void spellCallsign_spacesEveryCharacter() {
        assertThat(VoicePhrases.spellCallsign("K1ABC")).isEqualTo("K 1 A B C");
    }

    @Test
    public void spellCallsign_slashSpokenAsStroke() {
        assertThat(VoicePhrases.spellCallsign("EA8/K1ABC"))
                .isEqualTo("E A 8 stroke K 1 A B C");
    }

    @Test
    public void spellCallsign_nullAndBlankAreEmpty() {
        assertThat(VoicePhrases.spellCallsign(null)).isEmpty();
        assertThat(VoicePhrases.spellCallsign("  ")).isEmpty();
    }

    // ---- snrPhrase ---------------------------------------------------------

    @Test
    public void snrPhrase_negative() {
        assertThat(VoicePhrases.snrPhrase(-5)).isEqualTo("minus 5");
    }

    @Test
    public void snrPhrase_positive() {
        assertThat(VoicePhrases.snrPhrase(3)).isEqualTo("plus 3");
    }

    @Test
    public void snrPhrase_zero() {
        assertThat(VoicePhrases.snrPhrase(0)).isEqualTo("zero");
    }

    @Test
    public void snrPhrase_unknownSentinelIsEmpty() {
        // Decoder can emit a message with no SNR (Integer.MIN_VALUE sentinel);
        // the phrase must not say "minus 2147483648".
        assertThat(VoicePhrases.snrPhrase(VoicePhrases.SNR_UNKNOWN)).isEmpty();
    }

    @Test
    public void snrUnknownSentinelMatchesFt8Message() {
        // VoicePhrases mirrors Ft8Message.SNR_UNKNOWN without importing it
        // (keeps this class Android-free); pin the value so they can't drift.
        assertThat(VoicePhrases.SNR_UNKNOWN).isEqualTo(Integer.MIN_VALUE);
    }

    // ---- announcement phrases ------------------------------------------------

    @Test
    public void callingYou_withSnr() {
        assertThat(VoicePhrases.callingYou("K1ABC", -5))
                .isEqualTo("K 1 A B C calling you, minus 5");
    }

    @Test
    public void callingYou_unknownSnrDropsClause() {
        assertThat(VoicePhrases.callingYou("K1ABC", VoicePhrases.SNR_UNKNOWN))
                .isEqualTo("K 1 A B C calling you");
    }

    @Test
    public void qsoLogged() {
        assertThat(VoicePhrases.qsoLogged("K1ABC"))
                .isEqualTo("QSO with K 1 A B C logged");
    }

    @Test
    public void newCountry_speaksResolvedNameVerbatim() {
        assertThat(VoicePhrases.newCountry("Japan")).isEqualTo("New country: Japan");
    }

    @Test
    public void newPrefix_isSpelled() {
        assertThat(VoicePhrases.newPrefix("W1")).isEqualTo("New prefix: W 1");
    }

    // ---- echo confirmations ---------------------------------------------------

    @Test
    public void echoes() {
        assertThat(VoicePhrases.echoCallingCq()).isEqualTo("Calling CQ");
        assertThat(VoicePhrases.echoStopping()).isEqualTo("Stopping");
        assertThat(VoicePhrases.echoSkipping()).isEqualTo("Back to CQ");
        assertThat(VoicePhrases.echoLogged()).isEqualTo("Logged");
        assertThat(VoicePhrases.echoAnswering("K1ABC")).isEqualTo("Answering K 1 A B C");
    }
}
