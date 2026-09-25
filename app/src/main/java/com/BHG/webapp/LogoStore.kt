package com.BHG.webapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Durable, per-URL storage for custom app logos.
 *
 * Two things are kept per site:
 *
 *  - **draft** — the editable design (foreground + background images and the
 *    size / rotation / colour settings), so reopening the logo creator restores
 *    exactly what the user had.
 *  - **committed** — the generated launcher-icon ZIP and its preview PNG, the
 *    pair that actually goes into a build.
 *
 * Layout: `filesDir/logo_store/<key>/` holds `fg.png`, `bg.png`, `icon.zip` and
 * `preview.png`; a `logo_store` SharedPreferences file holds the per-key
 * settings plus a recency index. `<key>` is a SHA-256 digest of the site's host
 * (see [keyFor]), so a design made for one site can never be picked up by
 * another.
 *
 * Robustness rules this file follows, because a logo has to outlive the process:
 *
 *  - every write goes to a `.tmp` sibling that is flushed and renamed into
 *    place, so a kill mid-write can only ever leave the previous version —
 *    never a half-written image or ZIP;
 *  - the per-key settings are committed *after* the images, and a missing
 *    setting reads as "no saved logo", so a partial write degrades to the
 *    previous state instead of surfacing a broken icon;
 *  - every public call is serialised on one background thread, so a remove can
 *    never race a save, and every read is defensive — a missing, unreadable or
 *    corrupt entry returns null instead of throwing;
 *  - `Bitmap`s are never recycled here; a decode failure simply yields null. The
 *    images are small (≤1024px) and are released by the GC.
 *
 * Callers on the UI thread should use [committed], which only stats the two
 * output files. [loadDraft], [saveDraft], [commit] and [clear] do disk I/O and
 * block the calling thread while serialised (bounded by [IO_TIMEOUT_MS]), so
 * call them off the main thread.
 */
object LogoStore {

    private const val TAG = "LogoStore"

    private const val PREFS = "logo_store"
    private const val ROOT_DIR = "logo_store"

    private const val FILE_FG = "fg.png"
    private const val FILE_BG = "bg.png"
    private const val FILE_ZIP = "icon.zip"
    private const val FILE_PREVIEW = "preview.png"

    /** Recency index, newest first, as a comma-separated list of keys. */
    private const val KEY_ORDER = "order"

    /** How many sites keep a saved logo before the oldest is pruned. */
    private const val MAX_ENTRIES = 8

    /** Upper bound on a single serialised store operation. */
    private const val IO_TIMEOUT_MS = 8_000L

    /** Key used when a URL normalises to nothing (should not normally happen). */
    private const val FALLBACK_KEY = "default"

    // Mirror the logo creator's defaults and its slider ranges, so a restored
    // draft always lands on a value its controls can actually represent.
    private val DEFAULT_BG = 0xFFFAFAFA.toInt()
    private const val DEFAULT_FG_RESIZE = 69
    private const val DEFAULT_BG_RESIZE = 100
    private const val DEFAULT_FG_ROTATION = 0
    private const val FG_RESIZE_MIN = 10
    private const val FG_RESIZE_MAX = 150
    private const val BG_RESIZE_MIN = 10
    private const val BG_RESIZE_MAX = 200
    private const val ROTATION_MIN = -180
    private const val ROTATION_MAX = 180

    private val META_SUFFIXES = listOf(
        "url", "has_bg", "bg_is_color", "bg_color", "fg_resize", "bg_resize", "fg_rot"
    )

    /** The editable design. Immutable, so it is safe to hand between threads. */
    data class Draft(
        val fg: Bitmap,
        val bgImage: Bitmap?,
        val bgIsColor: Boolean,
        val bgColor: Int,
        val fgResize: Int,
        val bgResize: Int,
        val fgRotation: Int
    )

    /** A logo that has been generated and can be attached to a build. */
    data class Saved(val zipPath: String, val previewPath: String)

    /** Single worker: serialises every mutation so saves and removes can't interleave. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "logo-store").apply { isDaemon = true }
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * The generated icon set stored for [url], or null. Cheap — a stat of two
     * files — so it is safe to call straight from the UI thread.
     */
    fun committed(context: Context, url: String): Saved? = try {
        val dir = dirFor(context, keyFor(url))
        val zip = File(dir, FILE_ZIP)
        val preview = File(dir, FILE_PREVIEW)
        if (zip.isFile && zip.length() > 0L && preview.isFile && preview.length() > 0L) {
            Saved(zip.absolutePath, preview.absolutePath)
        } else null
    } catch (t: Throwable) {
        Log.w(TAG, "committed() failed", t)
        null
    }

    /**
     * The design saved for [url], or null when that site has none — a design
     * belongs to the site it was made for, so another site's logo is never
     * offered here, not even as a starting point. Never throws.
     */
    fun loadDraft(context: Context, url: String): Draft? =
        onIo { read(context.applicationContext, keyFor(url)) }

    /** Persists the editor state for [url] without touching the generated icon set. */
    fun saveDraft(context: Context, url: String, draft: Draft): Boolean =
        onIo { writeDraft(context.applicationContext, keyFor(url), url, draft) } ?: false

    /**
     * Stores the generated icon set for [url] along with the design that
     * produced it. Returns the stored paths, or null if anything could not be
     * written — in which case the previous entry is left untouched.
     */
    fun commit(
        context: Context, url: String, draft: Draft, zipBytes: ByteArray, previewPng: ByteArray
    ): Saved? = onIo {
        val app = context.applicationContext
        val key = keyFor(url)
        val dir = dirFor(app, key)
        val zipFile = File(dir, FILE_ZIP)
        val previewFile = File(dir, FILE_PREVIEW)

        when {
            !dir.isDirectory && !dir.mkdirs() -> null
            // The icon set is written before the design, so a failure here leaves
            // the previous logo intact rather than a ZIP with no design behind it.
            !writeAtomic(zipFile, zipBytes) -> null
            !writeAtomic(previewFile, previewPng) -> null
            !writeDraft(app, key, url, draft) -> null
            else -> Saved(zipFile.absolutePath, previewFile.absolutePath)
        }
    }

    /**
     * Forgets everything stored for [url] — the design and the generated icons.
     * Removing a logo has to survive a restart too, otherwise it would come back.
     */
    fun clear(context: Context, url: String): Boolean {
        val removed = onIo {
            val app = context.applicationContext
            val key = keyFor(url)
            val dir = dirFor(app, key)
            val deleted = !dir.exists() || dir.deleteRecursively()

            val editor = prefs(app).edit()
                .putString(KEY_ORDER, order(app).filter { it != key }.joinToString(","))
            clearMeta(editor, key)
            editor.commit()

            deleted
        }
        return removed ?: false
    }

    /**
     * Stable storage key for [url] — a digest of its host, so every spelling of
     * the same site (`http`/`https`, `www.`, a trailing slash, a path or query)
     * maps to one logo. Host-level rather than URL-level because that is what
     * the rest of the builder keys an app on: the generated package name comes
     * from the host alone, so two URLs on one host are one app and share a logo.
     */
    fun keyFor(url: String): String {
        val host = hostOf(url)
        if (host.isEmpty()) return FALLBACK_KEY
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(host.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) hex.append(String.format("%02x", b.toInt() and 0xFF))
            hex.substring(0, 32)
        } catch (t: Throwable) {
            // No SHA-256 on Android in practice, but a key derivation should never
            // be the thing that takes the logo creator down.
            Log.w(TAG, "keyFor() falling back to hashCode", t)
            Integer.toHexString(host.hashCode())
        }
    }

    /**
     * Host (plus port) of [url], lowercased and without a leading `www.`.
     *
     * Parsed by hand rather than with `android.net.Uri` so the rule can be unit
     * tested off-device; it mirrors how the wizard reads a host when it derives
     * the app name and package.
     */
    private fun hostOf(url: String): String {
        var s = url.trim().lowercase()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')      // drop any user:password@ prefix
        return s.removePrefix("www.").trimEnd('.', ':')
    }

    // =========================================================================
    //  Internals — all of these run on the single store thread
    // =========================================================================

    /**
     * Runs [block] on the store thread and waits for it, so every operation is
     * ordered against the others. Returns null when it fails, times out or is
     * interrupted — callers treat that as "nothing stored".
     */
    private fun <T> onIo(block: () -> T): T? = try {
        io.submit(Callable<T> { block() }).get(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "store operation interrupted")
        null
    } catch (t: Throwable) {
        Log.w(TAG, "store operation failed", t)
        null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dirFor(context: Context, key: String): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR), key)

    private fun order(context: Context): List<String> =
        prefs(context).getString(KEY_ORDER, "").orEmpty().split(',').filter { it.isNotEmpty() }

    private fun clearMeta(editor: SharedPreferences.Editor, key: String) {
        for (suffix in META_SUFFIXES) editor.remove("${key}_$suffix")
    }

    /** Reads one entry, or null when it is absent or unreadable. */
    private fun read(context: Context, key: String): Draft? = try {
        val p = prefs(context)
        if (p.getString("${key}_url", null) == null) {
            null
        } else {
            val dir = dirFor(context, key)
            val fg = decode(File(dir, FILE_FG))
            if (fg == null) {
                null
            } else {
                val bg = if (p.getBoolean("${key}_has_bg", false)) decode(File(dir, FILE_BG)) else null
                Draft(
                    fg = fg,
                    bgImage = bg,
                    // Without its image an "image" background is meaningless, so fall
                    // back to a colour rather than hand back a draft that can't render.
                    bgIsColor = bg == null || p.getBoolean("${key}_bg_is_color", true),
                    bgColor = p.getInt("${key}_bg_color", DEFAULT_BG),
                    fgResize = p.getInt("${key}_fg_resize", DEFAULT_FG_RESIZE)
                        .coerceIn(FG_RESIZE_MIN, FG_RESIZE_MAX),
                    bgResize = p.getInt("${key}_bg_resize", DEFAULT_BG_RESIZE)
                        .coerceIn(BG_RESIZE_MIN, BG_RESIZE_MAX),
                    fgRotation = p.getInt("${key}_fg_rot", DEFAULT_FG_ROTATION)
                        .coerceIn(ROTATION_MIN, ROTATION_MAX)
                )
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "read($key) failed", t)
        null
    }

    /**
     * Writes the images, then the settings that make them readable, then bumps
     * recency. Settings last is what makes a kill mid-write safe: without them
     * the entry reads as absent rather than as a logo pointing at a stale image.
     */
    private fun writeDraft(context: Context, key: String, url: String, draft: Draft): Boolean {
        val dir = dirFor(context, key)
        if (!dir.isDirectory && !dir.mkdirs()) return false

        var ok = writeAtomic(File(dir, FILE_FG), png(draft.fg))
        if (draft.bgImage != null) {
            ok = writeAtomic(File(dir, FILE_BG), png(draft.bgImage)) && ok
        } else {
            File(dir, FILE_BG).delete()
        }
        if (!ok) return false

        val committed = prefs(context).edit()
            .putString("${key}_url", url)
            .putBoolean("${key}_has_bg", draft.bgImage != null)
            .putBoolean("${key}_bg_is_color", draft.bgIsColor)
            .putInt("${key}_bg_color", draft.bgColor)
            .putInt("${key}_fg_resize", draft.fgResize)
            .putInt("${key}_bg_resize", draft.bgResize)
            .putInt("${key}_fg_rot", draft.fgRotation)
            .commit()
        if (!committed) return false

        touch(context, key)
        return true
    }

    /** Moves [key] to the front of the recency index, pruning the oldest entries. */
    private fun touch(context: Context, key: String) {
        val keys = order(context).toMutableList()
        keys.remove(key)
        keys.add(0, key)

        val editor = prefs(context).edit()
        while (keys.size > MAX_ENTRIES) {
            val evicted = keys.removeAt(keys.size - 1)
            runCatching { dirFor(context, evicted).deleteRecursively() }
            clearMeta(editor, evicted)
        }
        editor.putString(KEY_ORDER, keys.joinToString(",")).commit()
    }

    /** Flush-to-disk write through a temp file that is renamed over the target. */
    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val dir = target.parentFile
        val tmp = File(dir, "${target.name}.tmp")
        return try {
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) return false
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
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "write ${target.name} failed", t)
            runCatching { tmp.delete() }
            false
        }
    }

    private fun decode(file: File): Bitmap? = try {
        if (file.isFile && file.length() > 0L) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (t: Throwable) {
        Log.w(TAG, "decode ${file.name} failed", t)
        null
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
