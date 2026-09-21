package com.BHG.webapp

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Minimal, dependency-free client for the Website APK Builder Worker.
 *
 * Every network call runs on a background executor; results are always posted
 * back on the main thread, so callers can touch UI directly. Callbacks fire at
 * most once. There is no retry here — polling is driven by the caller.
 */
object BuildApi {

    const val BASE_URL = "https://website-apk-builder.iemgurpreets.workers.dev"

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "BuildApi").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Result of a successful POST /api/build. */
    data class BuildResult(
        val buildId: String,
        val appName: String,
        val packageName: String,
        val downloadUrl: String,
        val statusUrl: String,
        val status: String
    )

    /**
     * Dispatches a build.
     *
     * @param request the full JSON body ({url, app_name, package_name,
     *   remove_permissions, and per-option keys}).
     */
    fun build(
        request: JSONObject,
        idToken: String,
        onSuccess: (BuildResult) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val (code, text) = post("$BASE_URL/api/build", request.toString(), idToken)
                val json = parse(text)

                if (code in 200..299 && json != null && json.optBoolean("success", false)) {
                    val result = BuildResult(
                        buildId = json.optString("build_id"),
                        appName = json.optString("app_name"),
                        packageName = json.optString("package_name"),
                        downloadUrl = json.optString("download_url"),
                        statusUrl = json.optString("status_url"),
                        status = json.optString("status")
                    )
                    if (result.buildId.isEmpty() || result.downloadUrl.isEmpty()) {
                        postError(onError, "Malformed server response")
                    } else {
                        mainHandler.post { onSuccess(result) }
                    }
                } else {
                    val serverError = json?.optString("error").orEmpty()
                    postError(onError, serverError.ifEmpty { "Build request failed ($code)" })
                }
            } catch (e: Exception) {
                postError(onError, e.message ?: "Network error")
            }
        }
    }

    private fun postError(onError: (String) -> Unit, message: String) {
        mainHandler.post { onError(message) }
    }

    /**
     * Checks whether [urlString] is reachable, on a background thread, calling back
     * on the main thread. Tries a lightweight HEAD first and falls back to GET (some
     * servers reject HEAD). Any 2xx/3xx — or even a 4xx that isn't 404 — counts as
     * "the site answered", since the goal is only to confirm the host responds.
     * Follows redirects. onResult(true) means reachable.
     */
    fun ping(urlString: String, onResult: (Boolean) -> Unit) {
        executor.execute {
            val reachable = probe(urlString, "HEAD") || probe(urlString, "GET")
            mainHandler.post { onResult(reachable) }
        }
    }

    private fun probe(urlString: String, method: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WebCraft")
            }
            val code = conn.responseCode
            // Host answered with anything other than "not found" → treat as reachable.
            code in 200..399 || (code in 400..499 && code != 404)
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun post(urlString: String, body: String, idToken: String): Pair<Int, String> {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $idToken")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.code() to conn.readBody()
        } finally {
            conn.disconnect()
        }
    }

    private fun HttpURLConnection.code(): Int = responseCode

    /** Reads the response or error stream fully, tolerating either being absent. */
    private fun HttpURLConnection.readBody(): String {
        val stream = try {
            if (responseCode in 200..299) inputStream else errorStream
        } catch (e: Exception) {
            errorStream
        } ?: return ""
        return stream.bufferedReader().use(BufferedReader::readText)
    }

    private fun parse(text: String): JSONObject? =
        try {
            if (text.isBlank()) null else JSONObject(text)
        } catch (e: Exception) {
            null
        }

    /** Convenience for building a JSON array from strings. */
    fun jsonArrayOf(values: Collection<String>): JSONArray =
        JSONArray().apply { values.forEach { put(it) } }
}
