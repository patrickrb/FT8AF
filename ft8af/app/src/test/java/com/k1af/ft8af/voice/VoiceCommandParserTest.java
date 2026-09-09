package com.k1af.ft8af.voice;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.voice.VoiceCommandParser.Command;

import org.junit.Test;

/** Pure-JVM tests for {@link VoiceCommandParser} (no Android runtime needed). */
public class VoiceCommandParserTest {

    // ---- ANSWER --------------------------------------------------------

    @Test
    public void answer_plain() {
        assertThat(VoiceCommandParser.parse("answer")).isEqualTo(Command.ANSWER);
    }

    @Test
    public void answer_withNoiseWords() {
        assertThat(VoiceCommandParser.parse("answer him")).isEqualTo(Command.ANSWER);
        assertThat(VoiceCommandParser.parse("answer them")).isEqualTo(Command.ANSWER);
        assertThat(VoiceCommandParser.parse("please answer the station")).isEqualTo(Command.ANSWER);
    }

    @Test
    public void answer_replyVariant() {
        assertThat(VoiceCommandParser.parse("reply")).isEqualTo(Command.ANSWER);
        assertThat(VoiceCommandParser.parse("reply to him")).isEqualTo(Command.ANSWER);
    }

    @Test
    public void answer_capitalizationAndPunctuation() {
        assertThat(VoiceCommandParser.parse("  Answer! ")).isEqualTo(Command.ANSWER);
    }

    // ---- CALL_CQ -------------------------------------------------------

    @Test
    public void callCq_variants() {
        assertThat(VoiceCommandParser.parse("call cq")).isEqualTo(Command.CALL_CQ);
        assertThat(VoiceCommandParser.parse("cq")).isEqualTo(Command.CALL_CQ);
        assertThat(VoiceCommandParser.parse("CQ")).isEqualTo(Command.CALL_CQ);
        assertThat(VoiceCommandParser.parse("start calling CQ")).isEqualTo(Command.CALL_CQ);
    }

    @Test
    public void callCq_recognizerMondegreens() {
        // Recognizers routinely transcribe "CQ" as "seek you" or spelled letters.
        assertThat(VoiceCommandParser.parse("call seek you")).isEqualTo(Command.CALL_CQ);
        assertThat(VoiceCommandParser.parse("call c q")).isEqualTo(Command.CALL_CQ);
    }

    // ---- STOP ----------------------------------------------------------

    @Test
    public void stop_variants() {
        assertThat(VoiceCommandParser.parse("stop")).isEqualTo(Command.STOP);
        assertThat(VoiceCommandParser.parse("stop transmitting")).isEqualTo(Command.STOP);
        assertThat(VoiceCommandParser.parse("halt")).isEqualTo(Command.STOP);
        assertThat(VoiceCommandParser.parse("cancel")).isEqualTo(Command.STOP);
    }

    @Test
    public void stop_beatsCqWhenBothPresent() {
        // "stop calling cq" must stop, not start a CQ run.
        assertThat(VoiceCommandParser.parse("stop calling cq")).isEqualTo(Command.STOP);
    }

    // ---- SKIP ----------------------------------------------------------

    @Test
    public void skip_variants() {
        assertThat(VoiceCommandParser.parse("skip")).isEqualTo(Command.SKIP);
        assertThat(VoiceCommandParser.parse("skip him")).isEqualTo(Command.SKIP);
        assertThat(VoiceCommandParser.parse("next")).isEqualTo(Command.SKIP);
    }

    // ---- LOG -----------------------------------------------------------

    @Test
    public void log_variants() {
        assertThat(VoiceCommandParser.parse("log it")).isEqualTo(Command.LOG);
        assertThat(VoiceCommandParser.parse("log")).isEqualTo(Command.LOG);
        assertThat(VoiceCommandParser.parse("logged")).isEqualTo(Command.LOG);
    }

    // ---- UNKNOWN -------------------------------------------------------

    @Test
    public void unknown_forUnrelatedSpeech() {
        assertThat(VoiceCommandParser.parse("what's the weather")).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("hello world")).isEqualTo(Command.UNKNOWN);
        // "call" alone is not a command — only "call cq" is.
        assertThat(VoiceCommandParser.parse("call")).isEqualTo(Command.UNKNOWN);
    }

    @Test
    public void unknown_forNullEmptyAndPunctuationOnly() {
        assertThat(VoiceCommandParser.parse(null)).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("")).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("   ")).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("!?.,")).isEqualTo(Command.UNKNOWN);
    }

    @Test
    public void unknown_keywordsMustBeWholeTokens() {
        // Keyword embedded in a longer word must not match ("nextel" != "next").
        assertThat(VoiceCommandParser.parse("nextel")).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("catalog")).isEqualTo(Command.UNKNOWN);
        assertThat(VoiceCommandParser.parse("unstoppable")).isEqualTo(Command.UNKNOWN);
    }

    // ---- normalize -----------------------------------------------------

    @Test
    public void normalize_stripsPunctuationAndCollapsesWhitespace() {
        assertThat(VoiceCommandParser.normalize("  Call, CQ!  now ")).isEqualTo("call cq now");
    }
}
