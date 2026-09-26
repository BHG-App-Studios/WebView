package com.BHG.webapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Loads the launcher-icon preview a build stored on Cloudflare, for the icon slot
 * at the head of a build card (Active Builds, My Apps, Trash).
 *
 * The URL is the build's `previewImage` field, written by the build Worker when it
 * staged the image beside that build's icons.zip: a public
 * `GET /download/{uid}/{buildId}.webp`, addressed by build id and so immutable —
 * which is what makes it safe to cache indefinitely. Builds made before previews
 * existed have no such field, and those cards keep the plain badge they always had.
 *
 * Hand-rolled rather than pulling in an image library: this is the only remote
 * image in the app, each file is ~192px, and nothing here touches the network when
 * the bytes are already on disk. What it guarantees, because a card must never be
 * the thing that breaks a screen:
 *
 *  - every path is wrapped, so a malformed URL, a dead network or an unreadable
 *    cache entry leaves the placeholder in place instead of throwing;
 *  - a response is capped at [MAX_BYTES] and decoded sampled, so a URL that hands
 *    back something enormous cannot exhaust the heap;
 *  - a result is matched back to its row by URL, so a card that scrolled away and
 *    was recycled onto another app never shows the previous app's icon.
 */
object AppIconLoader {

    private const val TAG = "AppIconLoader"

    /** Cap on a single download; the Worker caps a preview upload at the same size. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Longest side a preview is decoded at. The slot is 46dp, so this is headroom. */
    private const val TARGET_PX = 192

    private const val CACHE_DIR = "app_icons"

    /** Cached files kept before the oldest are dropped. */
    private const val MAX_CACHED_FILES = 200

    /** Byte budget for decoded previews held in memory. */
    private const val MEMORY_BYTES = 4 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount.coerceAtLeast(1)
    }

    private val main = Handler(Looper.getMainLooper())

    private val workers = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "app-icon").apply { isDaemon = true }
    }

    /** URLs being fetched right now, so two rows sharing one preview fetch it once. */
    private val fetching = mutableSetOf<String>()

    /** URLs that already failed this session, so a broken one isn't retried on every bind. */
    private val broken = mutableSetOf<String>()

    /**
     * Points [icon] at [url]'s preview, hiding [badge] and [glyph] while it is up so
     * the card reads as the app's own launcher icon. A null, blank or unusable [url]
     * leaves all three exactly as the layout drew them. Safe to call from a bind on
     * the main thread; the image itself arrives later.
     */
    fun bind(icon: ImageView, badge: View, glyph: View, url: String?) {
        val wanted = url?.trim().orEmpty()
        // The tag is the contract with the worker below: it is what says which URL
        // this view is waiting for, so a recycled view ignores a stale result.
        icon.setTag(R.id.tag_icon_url, wanted)

        if (wanted.isEmpty()) {
            showFallback(icon, badge, glyph)
            return
        }
        val cached = memory.get(wanted)
        if (cached != null) {
            show(icon, badge, glyph, cached)
            return
        }
        showFallback(icon, badge, glyph)
        if (!broken.contains(wanted)) enqueue(icon, badge, glyph, wanted)
    }

    private fun showFallback(icon: ImageView, badge: View, glyph: View) {
        icon.visibility = View.GONE
        icon.setImageDrawable(null)
        badge.visibility = View.VISIBLE
        glyph.visibility = View.VISIBLE
    }

    private fun show(icon: ImageView, badge: View, glyph: View, bitmap: Bitmap) {
        icon.setImageBitmap(bitmap)
        icon.visibility = View.VISIBLE
        badge.visibility = View.GONE
        glyph.visibility = View.GONE
    }

    /**
     * Resolves [url] off the main thread — memory was already checked by [bind] —
     * then hands the bitmap back to the view, but only if it is still the URL that
     * view is showing.
     */
    private fun enqueue(icon: ImageView, badge: View, glyph: View, url: String) {
        synchronized(fetching) { if (!fetching.add(url)) return }
        val appContext = icon.context.applicationContext
        workers.execute {
            val bitmap = runCatching { load(appContext, url) }.getOrNull()
            synchronized(fetching) { fetching.remove(url) }
            if (bitmap == null) {
                synchronized(broken) { broken.add(url) }
                return@execute
            }
            memory.put(url, bitmap)
            main.post {
                if (icon.getTag(R.id.tag_icon_url) == url) show(icon, badge, glyph, bitmap)
            }
        }
    }

    /** Disk first, then the network. Blocking — only ever called on a worker thread. */
    private fun load(context: Context, url: String): Bitmap? {
        val file = cacheFile(context, url)
        if (file.isFile && file.length() > 0L) {
            val cached = runCatching { decode(file.readBytes()) }.getOrNull()
            if (cached != null) return cached
            // Unreadable entry: drop it and let the network have another go.
            runCatching { file.delete() }
        }
        val bytes = download(url) ?: return null
        val bitmap = decode(bytes) ?: return null
        writeAtomic(file, bytes)
        return bitmap
    }

    /**
     * [url]'s bytes, or null when it is not a plain web URL, the request fails, or
     * the response is larger than [MAX_BYTES].
     */
    private fun download(url: String): ByteArray? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol != "https" && parsed.protocol != "http") return null

        var conn: HttpURLConnection? = null
        return try {
            conn = (parsed.openConnection() as? HttpURLConnection) ?: return null
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "image/webp,image/*")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            conn.inputStream.use { readCapped(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Preview fetch failed for $url: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * [stream] in full, or null the moment it passes [MAX_BYTES] — so neither a
     * hostile response nor an accidentally enormous one is buffered into memory.
     */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    /** [bytes] decoded at no more than [TARGET_PX] on its longest side, or null. */
    private fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            null
        } else {
            var sample = 1
            while (longest / (sample * 2) >= TARGET_PX) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Preview decode failed", t)
        null
    }

    private fun cacheFile(context: Context, url: String): File =
        File(File(context.cacheDir, CACHE_DIR), sha256(url))

    /** Hex SHA-256 of [url]. Also the fallback the builder itself uses for a key. */
    private fun sha256(value: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) hex.append(String.format("%02x", b.toInt() and 0xFF))
        hex.toString()
    } catch (t: Throwable) {
        // No SHA-256 on Android in practice; a collision would only cost a re-download.
        Log.w(TAG, "sha256() falling back to hashCode", t)
        Integer.toHexString(value.hashCode())
    }

    /**
     * Flush-to-disk write through a temp file renamed over the target — the same
     * rule [LogoStore] follows, so a kill mid-write can only ever leave the previous
     * entry rather than a half-written image.
     */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(dir, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Only reached on a filesystem that refuses to replace on rename.
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return
                }
            }
            trim(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "Preview cache write failed", t)
            runCatching { tmp.delete() }
        }
    }

    /** Drops the oldest half of the cache once it has outgrown [MAX_CACHED_FILES]. */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_CACHED_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_FILES / 2)
            .forEach { runCatching { it.delete() } }
    }
}
