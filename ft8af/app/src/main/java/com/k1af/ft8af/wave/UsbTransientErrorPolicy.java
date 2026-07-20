package com.k1af.ft8af.wave;

/**
 * Classifies USB audio errors as <em>transient</em> (a brief RFI-induced stall,
 * a single dropped/naked isochronous packet, a null {@code requestWait()}) or
 * <em>fatal</em> (the device genuinely left the bus) so the TX/RX paths can ride
 * out a glitch instead of tearing the whole session down on the first error.
 *
 * <p>Pure logic, no Android/JNI types — unit-tested. The JNI and
 * {@code UsbRequest} glue stays a thin wrapper that consults these helpers,
 * exactly like {@link UsbCaptureRetryPolicy} and
 * {@link UsbAudioDevice#describeLibusbWriteError(int)}.
 *
 * <p>The {@code rc} codes here are the same ones
 * {@link UsbAudioDevice#describeLibusbWriteError(int)} labels: a negative
 * {@code libusb_error} for a setup/submit failure, or a positive
 * {@code libusb_transfer_status} ({@code 1..6}) recorded when a transfer dies
 * after streaming began.
 */
public final class UsbTransientErrorPolicy {

    private UsbTransientErrorPolicy() {}

    /** How an error should be handled by the recovery paths. */
    public enum Kind {
        /** Recoverable — retry the packet / rebind quickly; keep the session. */
        TRANSIENT,
        /** The device is gone — tear the session down cleanly. */
        FATAL,
        /** The user pressed STOP — not an error, do not retry or tear down. */
        CANCELLED
    }

    /**
     * Most a single isochronous packet may be re-sent before the TX path gives
     * up on it. FT8 has ~2.36&nbsp;s of cycle slack (see {@code CLAUDE.md} → "FT8
     * TX audio pipeline"), so a handful of ~1&nbsp;ms packet retries is well
     * within budget and never pushes audio off the WSJT-X grid.
     */
    public static final int MAX_PACKET_RETRIES = 3;

    /**
     * Classifies a {@link UsbAudioNative#nativeWrite} / capture return code.
     *
     * <p>Fatal (device really gone — tear down): {@code NO_DEVICE (-4)},
     * {@code NOT_FOUND (-5)}, {@code ACCESS (-3)}, {@code INVALID_PARAM (-2)},
     * {@code NO_MEM (-11)}, {@code NOT_SUPPORTED (-12)} and the transfer-status
     * {@code TRANSFER_NO_DEVICE (5)}. Cancelled: {@code TRANSFER_CANCELLED (3)}.
     * Everything else that is non-zero — {@code IO (-1)}, {@code TIMEOUT (-7)},
     * {@code PIPE (-9)}, {@code INTERRUPTED (-10)}, {@code BUSY (-6)},
     * {@code OVERFLOW (-8)}, and the transfer statuses {@code TRANSFER_ERROR
     * (1)}, {@code TRANSFER_TIMED_OUT (2)}, {@code TRANSFER_STALL (4)},
     * {@code TRANSFER_OVERFLOW (6)} — is transient.
     *
     * @param rc a non-zero libusb code ({@code rc == 0} is success and also maps
     *           to {@link Kind#TRANSIENT} as a harmless default; callers should
     *           not consult this on success)
     */
    public static Kind classify(int rc) {
        switch (rc) {
            case -2:  // INVALID_PARAM (programming error, not recoverable)
            case -3:  // ACCESS
            case -4:  // NO_DEVICE
            case -5:  // NOT_FOUND
            case -11: // NO_MEM
            case -12: // NOT_SUPPORTED
            case 5:   // TRANSFER_NO_DEVICE
                return Kind.FATAL;
            case 3:   // TRANSFER_CANCELLED — user pressed STOP
                return Kind.CANCELLED;
            default:
                return Kind.TRANSIENT;
        }
    }

    /** Convenience: {@code true} when {@link #classify(int)} is transient. */
    public static boolean isTransient(int rc) {
        return classify(rc) == Kind.TRANSIENT;
    }

    /** Convenience: {@code true} when {@link #classify(int)} is fatal. */
    public static boolean isFatal(int rc) {
        return classify(rc) == Kind.FATAL;
    }

    /**
     * Whether the TX fallback loop should re-send an isochronous packet that
     * just failed.
     *
     * <p>Only transient failures are retried, and only within the
     * {@link #MAX_PACKET_RETRIES} budget. A fatal ({@code NO_DEVICE}) or
     * cancelled failure returns {@code false} immediately so the over is dropped
     * without spinning.
     *
     * @param kind          the classification of the failure
     * @param attemptsSoFar how many times this packet has already been retried
     *                      ({@code 0} on the first failure)
     */
    public static boolean shouldRetryPacket(Kind kind, int attemptsSoFar) {
        if (kind != Kind.TRANSIENT) return false;
        return attemptsSoFar < MAX_PACKET_RETRIES;
    }

    /**
     * The {@code UsbRequest} fallback loop signals a failed packet as either a
     * {@code false} return from {@code queue()} or a {@code null} from
     * {@code requestWait()} — neither of which carries a libusb code. Both are
     * usually a brief stall (the kernel drops the URB under RFI) and so are
     * treated as {@link Kind#TRANSIENT}.
     *
     * @param requestWaitWasNull whether {@code connection.requestWait()} returned
     *                           {@code null}
     */
    public static Kind classifyUsbRequestFailure(boolean requestWaitWasNull) {
        // A null requestWait() and a queue()==false are both recoverable stalls.
        return Kind.TRANSIENT;
    }
}
