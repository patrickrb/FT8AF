package com.k1af.ft8af.log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a fresh {@link InputStream} on demand (e.g. re-opening a {@code
 * content://} URI via {@code ContentResolver.openInputStream}), so an
 * importer can make more than one pass over the same source without ever
 * holding the whole thing in memory to allow "rewinding".
 */
public interface StreamOpener {
    InputStream open() throws IOException;
}
