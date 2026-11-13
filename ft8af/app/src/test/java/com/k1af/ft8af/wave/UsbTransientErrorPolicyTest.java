package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.wave.UsbTransientErrorPolicy.Kind;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link UsbTransientErrorPolicy} — the transient/fatal
 * classification that lets the TX/RX paths ride out a brief USB glitch instead
 * of tearing the whole session down on the first error.
 */
public class UsbTransientErrorPolicyTest {

    // ---- classify: fatal (device really gone) -------------------------------

    @Test
    public void noDevice_isFatal() {
        assertThat(UsbTransientErrorPolicy.classify(-4)).isEqualTo(Kind.FATAL);
        assertThat(UsbTransientErrorPolicy.isFatal(-4)).isTrue();
        assertThat(UsbTransientErrorPolicy.isTransient(-4)).isFalse();
    }

    @Test
    public void notFound_access_noMem_notSupported_invalidParam_areFatal() {
        assertThat(UsbTransientErrorPolicy.classify(-5)).isEqualTo(Kind.FATAL);  // NOT_FOUND
        assertThat(UsbTransientErrorPolicy.classify(-3)).isEqualTo(Kind.FATAL);  // ACCESS
        assertThat(UsbTransientErrorPolicy.classify(-11)).isEqualTo(Kind.FATAL); // NO_MEM
        assertThat(UsbTransientErrorPolicy.classify(-12)).isEqualTo(Kind.FATAL); // NOT_SUPPORTED
        assertThat(UsbTransientErrorPolicy.classify(-2)).isEqualTo(Kind.FATAL);  // INVALID_PARAM
    }

    @Test
    public void transferNoDevice_isFatal() {
        // The real field drop: RF into the USB link keys the rig off the bus
        // mid-write; the transfer's positive status (5) is fatal.
        assertThat(UsbTransientErrorPolicy.classify(5)).isEqualTo(Kind.FATAL);
    }

    // ---- classify: cancelled (user pressed STOP) ----------------------------

    @Test
    public void transferCancelled_isCancelled() {
        assertThat(UsbTransientErrorPolicy.classify(3)).isEqualTo(Kind.CANCELLED);
        assertThat(UsbTransientErrorPolicy.isTransient(3)).isFalse();
        assertThat(UsbTransientErrorPolicy.isFatal(3)).isFalse();
    }

    // ---- classify: transient (brief stall, recoverable) ---------------------

    @Test
    public void io_timeout_pipe_interrupted_areTransient() {
        assertThat(UsbTransientErrorPolicy.classify(-1)).isEqualTo(Kind.TRANSIENT);  // IO
        assertThat(UsbTransientErrorPolicy.classify(-7)).isEqualTo(Kind.TRANSIENT);  // TIMEOUT
        assertThat(UsbTransientErrorPolicy.classify(-9)).isEqualTo(Kind.TRANSIENT);  // PIPE
        assertThat(UsbTransientErrorPolicy.classify(-10)).isEqualTo(Kind.TRANSIENT); // INTERRUPTED
        assertThat(UsbTransientErrorPolicy.classify(-6)).isEqualTo(Kind.TRANSIENT);  // BUSY
        assertThat(UsbTransientErrorPolicy.classify(-8)).isEqualTo(Kind.TRANSIENT);  // OVERFLOW
    }

    @Test
    public void transferError_timedOut_stall_overflow_areTransient() {
        assertThat(UsbTransientErrorPolicy.classify(1)).isEqualTo(Kind.TRANSIENT); // TRANSFER_ERROR
        assertThat(UsbTransientErrorPolicy.classify(2)).isEqualTo(Kind.TRANSIENT); // TRANSFER_TIMED_OUT
        assertThat(UsbTransientErrorPolicy.classify(4)).isEqualTo(Kind.TRANSIENT); // TRANSFER_STALL
        assertThat(UsbTransientErrorPolicy.classify(6)).isEqualTo(Kind.TRANSIENT); // TRANSFER_OVERFLOW
    }

    // ---- shouldRetryPacket --------------------------------------------------

    @Test
    public void retryPacket_transientWithinBudget_allowed() {
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(Kind.TRANSIENT, 0)).isTrue();
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(
                Kind.TRANSIENT, UsbTransientErrorPolicy.MAX_PACKET_RETRIES - 1)).isTrue();
    }

    @Test
    public void retryPacket_budgetExhausted_denied() {
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(
                Kind.TRANSIENT, UsbTransientErrorPolicy.MAX_PACKET_RETRIES)).isFalse();
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(
                Kind.TRANSIENT, UsbTransientErrorPolicy.MAX_PACKET_RETRIES + 5)).isFalse();
    }

    @Test
    public void retryPacket_fatalOrCancelled_neverRetries() {
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(Kind.FATAL, 0)).isFalse();
        assertThat(UsbTransientErrorPolicy.shouldRetryPacket(Kind.CANCELLED, 0)).isFalse();
    }

    // ---- classifyUsbRequestFailure ------------------------------------------

    @Test
    public void usbRequestFailure_isAlwaysTransient() {
        // A null requestWait() and a queue()==false are both recoverable stalls
        // in the Android UsbRequest fallback path.
        assertThat(UsbTransientErrorPolicy.classifyUsbRequestFailure(true))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(UsbTransientErrorPolicy.classifyUsbRequestFailure(false))
                .isEqualTo(Kind.TRANSIENT);
    }
}
