package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-logic coverage for {@link LogHttpServer#uploadedFilePath(Map)}, the guard that keeps
 * the web-logbook {@code /IMPORTLOGDATA} handler from dereferencing a missing upload part.
 *
 * <p>Regression: {@code doImportLogFile} read the multipart upload as
 * {@code files.get("file1").hashCode()}. A POST/PUT to {@code /IMPORTLOGDATA} that carried
 * no {@code file1} file field (a malformed or non-form request, or a bare LAN probe of the
 * always-on logbook port) left {@code files} without the key, so the bare
 * {@code .hashCode()} threw {@link NullPointerException}. That throw escaped {@code serve}
 * before its try/catch — the {@code IMPORTLOGDATA} branch runs in the leading dispatch
 * chain — so NanoHTTPD's worker aborted the request with no useful response. The sibling
 * handlers in the same file were already hardened
 * ({@link LogHttpServer#uriSegment(String[], int)} in PR #584,
 * {@link LogHttpServer#parseQueryInt(String, int)} in PR #585); this pins the matching
 * guard for the upload part.
 *
 * <p>No Android types are touched, so no Robolectric runner is needed (mirrors
 * {@link LogHttpServerUriSegmentTest} / {@link LogHttpServerQueryParamTest}).
 */
public class LogHttpServerUploadPartTest {

    @Test
    public void presentFilePart_isReturned() {
        Map<String, String> files = new HashMap<>();
        files.put("file1", "/data/tmp/NanoHTTPD-123456.tmp");
        assertThat(LogHttpServer.uploadedFilePath(files))
                .isEqualTo("/data/tmp/NanoHTTPD-123456.tmp");
    }

    @Test
    public void missingFilePart_isNull() {
        // The regression: no file1 entry (e.g. a POST/PUT with no file field, or a differently
        // named part). The old files.get("file1").hashCode() NPE'd here.
        assertThat(LogHttpServer.uploadedFilePath(new HashMap<>())).isNull();

        Map<String, String> wrongName = new HashMap<>();
        wrongName.put("someOtherField", "/data/tmp/x.tmp");
        assertThat(LogHttpServer.uploadedFilePath(wrongName)).isNull();
    }

    @Test
    public void nullMap_isNull() {
        // parseBody is only invoked for POST/PUT, but treat a null map defensively so the
        // guard never becomes the crash it is meant to prevent.
        assertThat(LogHttpServer.uploadedFilePath(null)).isNull();
    }

    @Test
    public void emptyStringPath_isReturnedNotSwallowed() {
        // An empty path is still "present": the downstream LogFileImport opens it and fails
        // with a caught FileNotFoundException, so uploadedFilePath must not conflate ""
        // with "absent" (which would silently change the null-only guard's scope).
        Map<String, String> files = new HashMap<>();
        files.put("file1", "");
        assertThat(LogHttpServer.uploadedFilePath(files)).isEqualTo("");
    }
}
