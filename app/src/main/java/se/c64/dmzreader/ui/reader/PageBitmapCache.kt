package se.c64.dmzreader.ui.reader

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache

/**
 * Minnescache för renderade sidor, med uttrycklig frigöring.
 *
 * En bitmaps pixlar ligger utanför Java-heapen, så skräpsamlaren märker inte
 * att minnet tar slut. Bilder som har fallit ur cachen frigörs därför direkt
 * med recycle(), men aldrig medan de visas: varje användare lånar bilden med
 * [acquire] och lämnar tillbaka den med [release].
 */
class PageBitmapCache(maxBytes: Int) {
    private class Entry(val bitmap: Bitmap) {
        var users = 0
        var evicted = false
    }

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    // Alla bilder som lever: de som ligger i cachen och de som har fallit ur men fortfarande visas.
    private val live = HashMap<String, Entry>()

    private val lru = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry): Int = value.bitmap.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Entry, newValue: Entry?) {
            synchronized(lock) {
                oldValue.evicted = true
                recycleIfUnused(key, oldValue)
            }
        }
    }

    /** Lånar bilden om den finns. Ska följas av [release]. */
    fun acquire(key: String): Bitmap? = synchronized(lock) {
        val entry = lru.get(key) ?: live[key]?.takeIf { !it.bitmap.isRecycled }
        entry?.also { it.users++ }?.bitmap
    }

    /** Lägger in en nyrenderad bild och lånar den till anroparen. */
    fun putAndAcquire(key: String, bitmap: Bitmap): Bitmap = synchronized(lock) {
        // Två renderingar av samma sida kan bli klara samtidigt. Den första gäller.
        live[key]?.takeIf { !it.bitmap.isRecycled }?.let { existing ->
            existing.users++
            if (existing.bitmap !== bitmap) bitmap.recycle()
            return existing.bitmap
        }
        val entry = Entry(bitmap).apply { users = 1 }
        live[key] = entry
        lru.put(key, entry)
        bitmap
    }

    fun release(key: String, bitmap: Bitmap) {
        synchronized(lock) {
            val entry = live[key]?.takeIf { it.bitmap === bitmap } ?: return
            entry.users = (entry.users - 1).coerceAtLeast(0)
            recycleIfUnused(key, entry)
        }
    }

    private fun recycleIfUnused(key: String, entry: Entry) {
        if (!entry.evicted || entry.users > 0) return
        if (live[key] === entry) live.remove(key)
        recycleLater(entry.bitmap)
    }

    /** Antal byte som cachen håller, inklusive bilder som har fallit ur men fortfarande visas. */
    fun liveBytes(): Long = synchronized(lock) {
        live.values.filter { !it.bitmap.isRecycled }.sumOf { it.bitmap.allocationByteCount.toLong() }
    }

    fun clear() {
        lru.evictAll()
    }

    /**
     * Frigör en bild som inte längre används. Det sker en kort stund senare, så
     * att en bildruta som redan är på väg att ritas hinner bli klar.
     */
    fun recycleLater(bitmap: Bitmap) {
        handler.postDelayed({ if (!bitmap.isRecycled) bitmap.recycle() }, RECYCLE_DELAY_MILLIS)
    }

    private companion object {
        const val RECYCLE_DELAY_MILLIS = 150L
    }
}
