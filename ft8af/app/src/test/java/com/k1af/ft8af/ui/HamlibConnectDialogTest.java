package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Input validation for the Hamlib connect dialog: a connectable pair needs a
 * non-empty host and a TCP port in 1..65535. Pure static helpers so the gate is
 * tested without inflating the dialog view.
 */
public class HamlibConnectDialogTest {

    @Test
    public void parsePort_acceptsValidRange() {
        assertThat(HamlibConnectDialog.parsePort("4532")).isEqualTo(4532);
        assertThat(HamlibConnectDialog.parsePort(" 1 ")).isEqualTo(1);
        assertThat(HamlibConnectDialog.parsePort("65535")).isEqualTo(65535);
    }

    @Test
    public void parsePort_rejectsOutOfRangeAndGarbage() {
        assertThat(HamlibConnectDialog.parsePort("0")).isEqualTo(-1);
        assertThat(HamlibConnectDialog.parsePort("65536")).isEqualTo(-1);
        assertThat(HamlibConnectDialog.parsePort("-4532")).isEqualTo(-1);
        assertThat(HamlibConnectDialog.parsePort("abc")).isEqualTo(-1);
        assertThat(HamlibConnectDialog.parsePort("")).isEqualTo(-1);
        assertThat(HamlibConnectDialog.parsePort(null)).isEqualTo(-1);
    }

    @Test
    public void isValidInput_requiresHostAndPort() {
        assertThat(HamlibConnectDialog.isValidInput("192.168.1.10", "4532")).isTrue();
        assertThat(HamlibConnectDialog.isValidInput("rig.local", "4532")).isTrue();
    }

    @Test
    public void isValidInput_rejectsMissingHostOrBadPort() {
        assertThat(HamlibConnectDialog.isValidInput("", "4532")).isFalse();
        assertThat(HamlibConnectDialog.isValidInput(null, "4532")).isFalse();
        assertThat(HamlibConnectDialog.isValidInput("192.168.1.10", "")).isFalse();
        assertThat(HamlibConnectDialog.isValidInput("192.168.1.10", "notaport")).isFalse();
    }
}
