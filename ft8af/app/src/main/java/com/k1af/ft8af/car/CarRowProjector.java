package com.k1af.ft8af.car;

import com.k1af.ft8af.Ft8Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure projection of decoded {@link Ft8Message}s into the rows the Android Auto decode
 * screen renders. Extracted from {@code DecodeListScreen} so the formatting + the
 * host-imposed row cap can be unit-tested without a car host (the Screen/template code
 * can't be).
 */
public final class CarRowProjector {
    /** Android Auto list templates only surface a handful of rows while driving. */
    public static final int MAX_ROWS = 6;

    private CarRowProjector() {}

    /** A single rendered row: a non-empty title plus a subtitle line. */
    public static final class CarRow {
        public final String title;
        public final String subtitle;

        public CarRow(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * Project the most recent {@code maxRows} messages, newest first. Null / empty input and
     * null elements are tolerated.
     */
    public static List<CarRow> project(List<Ft8Message> messages, int maxRows) {
        List<CarRow> rows = new ArrayList<>();
        if (messages == null || maxRows <= 0) return rows;
        for (int i = messages.size() - 1; i >= 0 && rows.size() < maxRows; i--) {
            Ft8Message m = messages.get(i);
            if (m == null) continue;
            rows.add(new CarRow(rowTitle(m), rowSubtitle(m)));
        }
        return rows;
    }

    static String rowTitle(Ft8Message m) {
        String from = m.getCallsignFrom();
        if (m.callsignTo != null && m.checkIsCQ()) {
            return ("CQ " + from).trim();
        }
        String to = m.getCallsignTo();
        String title = to.isEmpty() ? from : (from + " → " + to);
        return title.isEmpty() ? "—" : title; // never empty: car rows reject blank titles
    }

    static String rowSubtitle(Ft8Message m) {
        StringBuilder sb = new StringBuilder("SNR ").append(snrText(m));
        if (m.maidenGrid != null && !m.maidenGrid.isEmpty()) {
            sb.append("  ").append(m.maidenGrid);
        }
        return sb.toString();
    }

    static String snrText(Ft8Message m) {
        return m.snr == Ft8Message.SNR_UNKNOWN ? "—" : String.valueOf(m.snr);
    }

    /** Template header: reflects whether we're transmitting and to whom. */
    public static String headerTitle(boolean isTransmitting, String targetCallsign) {
        if (isTransmitting) {
            String t = targetCallsign == null ? "" : targetCallsign.trim();
            return t.isEmpty() ? "TX" : ("TX → " + t);
        }
        return "Receiving";
    }
}
