package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Regression coverage for {@link RadioTcpClient#sendByte(byte[])} dispatch.
 *
 * <p>The Flex command channel writes CAT commands with {@code sendByte()}, which hands the
 * work to a cached thread pool. Before the fix a single long-lived {@link
 * RadioTcpClient.SendByteRunnable} was reused: {@code sendByte()} overwrote its {@code mBuffer}
 * field, then submitted the same object again. Because the pool reads {@code mBuffer}
 * asynchronously, two back-to-back sends could clobber each other — one command dropped and the
 * latest one written twice. Worse than the UDP sibling, the runnable writes to a <em>shared</em>
 * {@link java.io.OutputStream} whose {@code write(byte[])} is not atomic, so two concurrent
 * sends could interleave into a single corrupt command frame.
 *
 * <p>The fix submits a fresh runnable carrying an immutable per-call snapshot and holds a lock
 * across the write+flush. These tests exercise the new {@link RadioTcpClient.SendByteRunnable}
 * directly (pure JDK — the only Android type in {@link RadioTcpClient} is {@code
 * android.util.Log}, stubbed via {@code returnDefaultValues}).
 */
public class RadioTcpClientSendByteTest {

    /**
     * Each send carries its own immutable snapshot. The old shared runnable had its {@code
     * mBuffer} overwritten before every {@code execute()}, so two racing sends could send the
     * wrong (latest) payload. Two runnables built from different calls must retain their own
     * buffer — this is the direct regression for the clobber.
     */
    @Test
    public void eachSend_capturesIndependentSnapshot() {
        RadioTcpClient client = new RadioTcpClient();
        byte[] first = {1, 2, 3};
        byte[] second = {9, 9};

        RadioTcpClient.SendByteRunnable a = new RadioTcpClient.SendByteRunnable(client, first);
        RadioTcpClient.SendByteRunnable b = new RadioTcpClient.SendByteRunnable(client, second);

        assertThat(a.mBuffer).isSameInstanceAs(first);
        // b must not have clobbered a's snapshot.
        assertThat(b.mBuffer).isSameInstanceAs(second);
    }

    /** run() writes exactly its own buffer to the connected OutputStream, then flushes. */
    @Test
    public void run_writesOwnBuffer() {
        RadioTcpClient client = new RadioTcpClient();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        client.primeOutputStreamForTest(sink);

        byte[] first = {1, 2, 3};
        byte[] second = {9, 9};
        // Even though `second` was created "after", running `first`'s runnable must write `first`
        // (pre-fix the shared runnable would have written whatever mBuffer was last set to).
        new RadioTcpClient.SendByteRunnable(client, first).run();
        new RadioTcpClient.SendByteRunnable(client, second).run();

        // Sequential run() → the stream is exactly the two buffers back-to-back, un-torn.
        assertThat(sink.toByteArray()).isEqualTo(new byte[]{1, 2, 3, 9, 9});
    }

    /** A null buffer or an unset OutputStream is a quiet no-op, never a throw. */
    @Test
    public void run_isSafeWithNullBufferOrStream() {
        RadioTcpClient client = new RadioTcpClient();

        // No OutputStream primed (never connected / torn down): must not throw.
        new RadioTcpClient.SendByteRunnable(client, new byte[]{1}).run();

        // Null buffer with a live stream: nothing written, no throw.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        client.primeOutputStreamForTest(sink);
        new RadioTcpClient.SendByteRunnable(client, null).run();

        assertThat(sink.toByteArray()).isEmpty();
    }
}
