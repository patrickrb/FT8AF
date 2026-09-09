package com.k1af.ft8af.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Small stream helpers.
 */
public final class Streams {

    private Streams() {
    }

    /**
     * Reads an {@link InputStream} to its end into a byte array.
     *
     * <p>The bundled data files (cty.dat, the DXCC/CQ/ITU-zone JSON tables,
     * rigaddress.txt, bands.txt, the help texts) used to be read with
     * {@code new byte[in.available()]} followed by a single {@code in.read(buf)}
     * whose return value was ignored. Neither is reliable: {@code available()}
     * is only a hint, and one {@code read(byte[])} is explicitly permitted to
     * return fewer bytes than requested. Android's {@code AssetInputStream}
     * decompresses on the fly, so for a large compressed asset (cty.dat is
     * ~280&nbsp;KB, the zone tables are 450&nbsp;KB&ndash;740&nbsp;KB) the lone
     * read returns only the first chunk and the tail of the buffer stays as NUL
     * bytes. That silently truncated the callsign&rarr;country/zone database and
     * made the zone/DXCC JSON fail to parse entirely. Draining the stream in a
     * loop until EOF (the same fix already applied to log import in
     * {@code LogFileImport.readFully}) reads the whole file regardless of how the
     * underlying stream chunks it.
     *
     * @param in the stream to drain (not closed here &mdash; the caller owns it)
     * @return the full stream contents
     * @throws IOException if the underlying read fails
     */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        // available() is only a sizing hint; guard against a 0/negative estimate.
        int hint = Math.max(32, in.available());
        ByteArrayOutputStream out = new ByteArrayOutputStream(hint);
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }
}
