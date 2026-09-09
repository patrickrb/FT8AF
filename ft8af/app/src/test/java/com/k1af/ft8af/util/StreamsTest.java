package com.k1af.ft8af.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Coverage for {@link Streams#readAllBytes(InputStream)}.
 *
 * <p>The bug this replaces sized a buffer from {@code available()} and did a
 * single {@code read(byte[])}. Both are unreliable: a lone read may return fewer
 * bytes than the buffer length, and {@code available()} is only a hint. Android's
 * compressed {@code AssetInputStream} exhibits exactly this — a big asset is
 * delivered in chunks. {@link ShortReadInputStream} reproduces that behaviour so
 * the fix is exercised without an Android runtime.
 */
public class StreamsTest {

    /**
     * An {@link InputStream} that hands back at most {@code maxPerRead} bytes per
     * {@code read(byte[], int, int)} call and can report an arbitrary (wrong)
     * {@code available()}, mirroring a decompressing asset stream.
     */
    private static final class ShortReadInputStream extends InputStream {
        private final byte[] data;
        private final int maxPerRead;
        private final int reportedAvailable;
        private int pos;

        ShortReadInputStream(byte[] data, int maxPerRead, int reportedAvailable) {
            this.data = data;
            this.maxPerRead = maxPerRead;
            this.reportedAvailable = reportedAvailable;
        }

        @Override
        public int read() {
            if (pos >= data.length) {
                return -1;
            }
            return data[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, maxPerRead), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int available() {
            return reportedAvailable;
        }
    }

    @Test
    public void readAllBytes_returnsExactContentOfPlainStream() throws IOException {
        byte[] src = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] out = Streams.readAllBytes(new ByteArrayInputStream(src));
        assertThat(out).isEqualTo(src);
    }

    @Test
    public void readAllBytes_emptyStreamReturnsEmptyArray() throws IOException {
        byte[] out = Streams.readAllBytes(new ByteArrayInputStream(new byte[0]));
        assertThat(out).hasLength(0);
    }

    @Test
    public void readAllBytes_drainsStreamThatShortReadsOneBytePerCall() throws IOException {
        // 100 KB is larger than the 8 KB working chunk and, more importantly, the
        // stream only yields one byte per read — the exact case the old
        // single-read code truncated. The loop must reassemble every byte.
        byte[] src = new byte[100_000];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 31 + 7);
        }
        byte[] out = Streams.readAllBytes(
                new ShortReadInputStream(src, 1, src.length));
        assertThat(out).isEqualTo(src);
    }

    @Test
    public void readAllBytes_ignoresUnderReportedAvailable() throws IOException {
        // available() lies (says 4) but the stream holds far more. Because we drain
        // to EOF rather than trusting available(), the full payload comes back.
        byte[] src = new byte[20_000];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i & 0x7f);
        }
        byte[] out = Streams.readAllBytes(
                new ShortReadInputStream(src, 4096, 4));
        assertThat(out).isEqualTo(src);
    }
}
