package com.k1af.ft8af.log;

/**
 * A minimal previously-logged QSO record used by the decode sheet's
 * "Worked before" history card. Holds only the columns that card needs
 * (date/time/band/mode) so it stays cheap to read out of {@code QSLTable}
 * in bulk. All fields may be null/empty for a sparse log row.
 */
public class PriorQso {
    private final String qsoDate;
    private final String timeOn;
    private final String band;
    private final String mode;

    public PriorQso(String qsoDate, String timeOn, String band, String mode) {
        this.qsoDate = qsoDate;
        this.timeOn = timeOn;
        this.band = band;
        this.mode = mode;
    }

    /** ADIF {@code qso_date}, typically {@code YYYYMMDD}; may be null/empty. */
    public String getQsoDate() {
        return qsoDate;
    }

    /** ADIF {@code time_on}, typically {@code HHMMSS} or {@code HHMM}; may be null. */
    public String getTimeOn() {
        return timeOn;
    }

    /** Band label such as {@code 20m}; may be null/empty. */
    public String getBand() {
        return band;
    }

    /** Mode such as {@code FT8}; may be null/empty. */
    public String getMode() {
        return mode;
    }
}
