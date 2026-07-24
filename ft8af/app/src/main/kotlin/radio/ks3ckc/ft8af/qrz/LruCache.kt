package radio.ks3ckc.ft8af.qrz

import androidx.annotation.VisibleForTesting

/**
 * A tiny thread-safe LRU cache backed by an access-ordered [LinkedHashMap].
 *
 * The QRZ image clients read this cache from per-decode avatar lookups on
 * Dispatchers.IO while the Settings screen [clear]s it from the main thread
 * when credentials change. An access-ordered LinkedHashMap re-links its
 * internal list on every `get()`, so read and clear MUST be mutually excluded
 * — an unlocked clear() racing a get() is a structural-modification data race
 * (ConcurrentModificationException / NPE / silent corruption). Every operation
 * here is `@Synchronized` on this instance, so the two callers can't collide.
 *
 * The map is capped at [maxSize] entries via [LinkedHashMap.removeEldestEntry];
 * because it is access-ordered, the evicted entry is the least-recently-used.
 */
internal class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @VisibleForTesting
    @Synchronized
    fun size(): Int = map.size
}
