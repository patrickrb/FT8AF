package com.k1af.ft8af.voice;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, Android-free keyword parser for one-shot voice commands (no NLU, no
 * network). The recognizer's top transcription is normalized (lowercased,
 * punctuation stripped, whitespace collapsed) and matched against a small
 * fixed keyword set; anything else is {@link Command#UNKNOWN}.
 *
 * <p>Match priority when an utterance contains several keywords: STOP beats
 * everything ("stop calling CQ" must stop, not CQ), then ANSWER, SKIP, LOG,
 * and CALL_CQ last. Recognizers often mangle "CQ" into "seek you" or "c q",
 * so those variants are accepted for CALL_CQ.
 */
public final class VoiceCommandParser {
    private VoiceCommandParser() {}

    public enum Command { ANSWER, CALL_CQ, STOP, SKIP, LOG, UNKNOWN }

    private static final Set<String> STOP_WORDS =
            new HashSet<>(Arrays.asList("stop", "halt", "cancel"));
    private static final Set<String> ANSWER_WORDS =
            new HashSet<>(Arrays.asList("answer", "reply"));
    private static final Set<String> SKIP_WORDS =
            new HashSet<>(Arrays.asList("skip", "next"));
    private static final Set<String> LOG_WORDS =
            new HashSet<>(Arrays.asList("log", "logged"));

    /** Parse a recognizer transcription into a {@link Command}. Null-safe. */
    public static Command parse(String utterance) {
        if (utterance == null) return Command.UNKNOWN;
        String norm = normalize(utterance);
        if (norm.isEmpty()) return Command.UNKNOWN;

        Set<String> tokens = new HashSet<>(Arrays.asList(norm.split(" ")));

        if (containsAny(tokens, STOP_WORDS)) return Command.STOP;
        if (containsAny(tokens, ANSWER_WORDS)) return Command.ANSWER;
        if (containsAny(tokens, SKIP_WORDS)) return Command.SKIP;
        if (containsAny(tokens, LOG_WORDS)) return Command.LOG;
        if (isCallCq(norm, tokens)) return Command.CALL_CQ;
        return Command.UNKNOWN;
    }

    /** Lowercase, strip everything but letters/digits, collapse whitespace. */
    static String normalize(String utterance) {
        String lower = utterance.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAny(Set<String> tokens, Set<String> words) {
        for (String w : words) {
            if (tokens.contains(w)) return true;
        }
        return false;
    }

    /**
     * "CQ" arrives from recognizers as the token "cq", the mondegreen
     * "seek you", or two spelled letters "c q" — accept all three.
     */
    private static boolean isCallCq(String norm, Set<String> tokens) {
        if (tokens.contains("cq")) return true;
        if (norm.contains("seek you")) return true;
        return norm.contains("c q");
    }
}
