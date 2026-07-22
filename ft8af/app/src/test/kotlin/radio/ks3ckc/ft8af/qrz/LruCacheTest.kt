package radio.ks3ckc.ft8af.qrz

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM tests for [LruCache], the small thread-safe LRU the QRZ image
 * clients share.
 *
 * The class exists because the QRZ clients previously cleared an
 * access-ordered [LinkedHashMap] from the Settings screen (main thread, on
 * saving credentials) with NO lock, while per-decode avatar lookups read the
 * same map on Dispatchers.IO under a coroutine mutex. An access-ordered
 * LinkedHashMap re-links its internal list on every get(), so an unlocked
 * clear() racing a get() is a structural-modification data race
 * (ConcurrentModificationException / NPE / corruption). LruCache funnels
 * every read, write, and clear through a single monitor so the two callers
 * can never collide. No Android types are touched, so no Robolectric runner.
 */
class LruCacheTest {

    @Test
    fun getReturnsPutValue() {
        val cache = LruCache<String, String>(4)
        cache.put("W5XO", "url")
        assertThat(cache.get("W5XO")).isEqualTo("url")
    }

    @Test
    fun getMissReturnsNull() {
        val cache = LruCache<String, String>(4)
        assertThat(cache.get("missing")).isNull()
    }

    @Test
    fun clearEmptiesTheCache() {
        val cache = LruCache<String, String>(4)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isNull()
        assertThat(cache.size()).isEqualTo(0)
    }

    @Test
    fun evictsEldestWhenOverCapacity() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3) // over capacity: "a" is the least-recently-used, evicted
        assertThat(cache.size()).isEqualTo(2)
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isEqualTo(2)
        assertThat(cache.get("c")).isEqualTo(3)
    }

    @Test
    fun accessOrderProtectsRecentlyReadEntryFromEviction() {
        // Access-order (LRU) semantics: reading "a" makes it most-recent, so
        // the next over-capacity put evicts "b" (now the eldest) instead.
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        assertThat(cache.get("a")).isEqualTo(1) // touch "a"
        cache.put("c", 3)
        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isEqualTo(1)
        assertThat(cache.get("c")).isEqualTo(3)
    }

    /**
     * Documents *why* the cache must be synchronized: an access-ordered
     * LinkedHashMap treats get() as a structural modification (it moves the
     * touched entry to the tail of its list). This is deterministic, single
     * threaded proof that a concurrent unlocked clear() would race a get().
     */
    @Test
    fun rawAccessOrderMap_getIsAStructuralModification() {
        val raw = LinkedHashMap<String, Int>(16, 0.75f, true)
        raw["a"] = 1
        raw["b"] = 2
        raw["c"] = 3
        raw["a"] // touch — reorders in access-order mode
        assertThat(raw.keys.toList()).containsExactly("b", "c", "a").inOrder()
    }

    /**
     * Smoke/stress harness: many reader/writer threads hammer the cache while
     * another repeatedly clears it — exactly the Settings-save-vs-avatar-lookup
     * collision from production. With every op synchronized this must complete
     * with no throwable escaping. (The underlying data race usually manifests as
     * silent map corruption rather than a deterministic exception, so this is a
     * best-effort regression guard, not a proof of the bug — the deterministic
     * proof that get() is a structural modification lives in the test above.)
     */
    @Test
    fun concurrentGetPutAndClear_neverThrows() {
        val cache = LruCache<Int, Int>(64)
        val threads = 8
        val iterations = 20_000
        val start = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                try {
                    var i = 0
                    while (!stop.get() && i < iterations) {
                        val k = i and 0x7f
                        if (t % 4 == 0) {
                            cache.clear()
                        } else if (i and 1 == 0) {
                            cache.put(k, i)
                        } else {
                            cache.get(k)
                        }
                        i++
                    }
                } catch (th: Throwable) {
                    failures.add(th)
                }
            }.also { it.start() }
        }

        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        stop.set(true)

        assertThat(failures).isEmpty()
    }
}
