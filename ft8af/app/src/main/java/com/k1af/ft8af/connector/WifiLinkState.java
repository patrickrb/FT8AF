package com.k1af.ft8af.connector;

/**
 * Edge-guarded connection-state machine for the Icom/Xiegu network (WLAN) link (issue #754).
 *
 * <p>The Icom UDP stack signals its progress through repeated packets — a 0x60 login response
 * and a 0x50 status packet can each arrive several times (the X6100 in particular re-fires
 * them), and a dropped link surfaces as a send-side {@code IOException} or an explicit close.
 * Before #754 none of that reached the UI: the CAT status chip stayed grey the entire session
 * even with the radio linked, and a Wi-Fi drop just froze the waterfall with no message.
 *
 * <p>This class turns that stream of raw events into <em>edges</em> — connected announced once,
 * disconnected announced once — so the connector can drive the same
 * {@link OnConnectorStateChanged} pipeline every wired connector already uses. Pure (no Android
 * imports) so it is unit-testable.
 *
 * <p>Thread-safe: login results arrive on the UDP receive worker, send errors on whichever
 * stream's sender failed, and a close from the UI thread — every transition and the
 * {@link #isConnected()} read take the same monitor so two callbacks can't both see
 * {@code terminal == false} and double-announce an edge, and a close can't interleave with a
 * login and leave the chip lit after teardown.
 */
public final class WifiLinkState {

    /** What the connector should emit in response to an event; {@code null} means "nothing". */
    public enum Emit {
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    private boolean announcedConnected = false;
    private boolean terminal = false;

    /** True between a {@link Emit#CONNECTED} and the following disconnect/error. */
    public synchronized boolean isConnected() {
        return announcedConnected && !terminal;
    }

    /**
     * Start a fresh attempt. {@link WifiConnector#connect()} calls this before
     * {@code wifiRig.start()} because a reconnect reuses the same connector instance: without
     * it the {@code terminal} flag left behind by the previous close/error swallowed the next
     * successful login, so the chip stuck on "connecting" and {@link #isConnected()} stayed
     * false for the whole re-established session.
     */
    public synchronized void reset() {
        announcedConnected = false;
        terminal = false;
    }

    /**
     * A 0x60 login response arrived. First success emits {@link Emit#CONNECTED}; later successes
     * are swallowed (the rig re-sends). A failure (bad user/password) is a terminal
     * {@link Emit#ERROR}.
     */
    public synchronized Emit onLoginResult(boolean ok) {
        if (terminal) return null;
        if (ok) {
            if (announcedConnected) return null;
            announcedConnected = true;
            return Emit.CONNECTED;
        }
        terminal = true;
        return Emit.ERROR;
    }

    /**
     * A UDP send failed (network unreachable). After a successful connect this is a link drop
     * ({@link Emit#DISCONNECTED}); before one it is a failed attempt ({@link Emit#ERROR}). Once
     * only — the stack fires it per stream.
     */
    public synchronized Emit onSendError() {
        if (terminal) return null;
        terminal = true;
        return announcedConnected ? Emit.DISCONNECTED : Emit.ERROR;
    }

    /**
     * The link was closed (user disconnect, or teardown after an error already reported).
     * Emits {@link Emit#DISCONNECTED} only if we had announced a connect and haven't already
     * gone terminal.
     */
    public synchronized Emit onClosed() {
        if (terminal) return null;
        terminal = true;
        return announcedConnected ? Emit.DISCONNECTED : null;
    }
}
