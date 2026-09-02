package radio.ks3ckc.ft8af.ui.pota

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The main-thread guarantee on PotaAdifExporter's result callback (issue #700).
 *
 * shareActivationAdif does its work on Dispatchers.IO, and its callers touch UI
 * in the callback — the POTA screen shows a Toast, which throws "Can't toast on
 * a thread that has not called Looper.prepare()" anywhere but the main thread.
 * Sharing an activation with no QSOs takes the empty-documents exit and crashed
 * the app that way, so what needs pinning is the *thread* the callback arrives
 * on, not the sharing itself (that part is DB/Intent-bound).
 */
@RunWith(RobolectricTestRunner::class)
class PotaAdifExporterThreadingTest {

    @Test
    fun deliverOnMain_fromBackgroundThread_runsCallbackOnMainLooper() {
        val ran = AtomicBoolean(false)
        val callbackLooper = AtomicReference<Looper?>(null)
        val received = AtomicReference<Boolean?>(null)

        // The crashing path: reporting failure from a worker thread, as the IO
        // coroutine does when an activation has no QSOs to share.
        val worker = Thread {
            PotaAdifExporter.deliverOnMain(false) { result ->
                ran.set(true)
                callbackLooper.set(Looper.myLooper())
                received.set(result)
            }
        }
        worker.start()
        worker.join()

        // It must be posted, not run inline on the worker — that inline call is
        // precisely what threw before.
        assertThat(ran.get()).isFalse()

        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ran.get()).isTrue()
        assertThat(callbackLooper.get()).isEqualTo(Looper.getMainLooper())
        assertThat(received.get()).isFalse()
    }

    @Test
    fun deliverOnMain_fromMainThread_runsCallbackImmediately() {
        // Robolectric runs the test body on the main thread, which is also where
        // the null-database early return happens. Posting there would defer a
        // result the caller can have now, so it is delivered inline instead.
        val received = AtomicReference<Boolean?>(null)

        PotaAdifExporter.deliverOnMain(true) { received.set(it) }

        assertThat(received.get()).isTrue()
    }

    @Test
    fun deliverOnMain_passesTheResultThrough() {
        // Both outcomes reach the caller unchanged: the screen only toasts on
        // false, so a flipped value would silently swallow the error message.
        val seen = mutableListOf<Boolean>()
        PotaAdifExporter.deliverOnMain(true) { seen.add(it) }
        PotaAdifExporter.deliverOnMain(false) { seen.add(it) }
        assertThat(seen).containsExactly(true, false).inOrder()
    }
}
