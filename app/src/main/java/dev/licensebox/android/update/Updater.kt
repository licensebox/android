package dev.licensebox.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.licensebox.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A release newer than this app. */
data class Available(val version: String, val apkUrl: String, val sumsUrl: String, val apkName: String) {
    fun toJson(): String = JSONObject().put("version", version).put("apk", apkUrl).put("sums", sumsUrl).put("name", apkName).toString()

    companion object {
        fun fromJson(text: String): Available? = runCatching {
            val o = JSONObject(text)
            Available(o.getString("version"), o.getString("apk"), o.getString("sums"), o.getString("name"))
        }.getOrNull()
    }
}

/** The parts that need no phone. */
internal object Release {
    private const val DOWNLOADS = "https://github.com/licensebox/android/releases/download/"

    private fun numbers(v: String): List<Int>? {
        val parts = v.removePrefix("v").split(".")
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** Whether version [tag] is after [current]; false if either isn't numbers. */
    fun newer(tag: String, current: String): Boolean {
        val a = numbers(tag) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The update in GitHub's answer for the latest release, if it is one. */
    fun parse(json: JSONObject, current: String): Available? {
        val tag = json.optString("tag_name")
        if (!newer(tag, current)) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: Pair<String, String>? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!url.startsWith(DOWNLOADS)) continue
            if (name == "SHA256SUMS") sums = url
            if (name == "licensebox-android-$tag.apk") apk = name to url
        }
        val (name, url) = apk ?: return null
        return Available(tag.removePrefix("v"), url, sums ?: return null, name)
    }

    /** The sum of [name] in a SHA256SUMS file, in lower case. */
    fun sumOf(sums: String, name: String): String? = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)?.lowercase()

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** Checks GitHub's releases for a newer app and installs it, if you confirm. */
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val dir get() = File(context.cacheDir, "updates")

    /** The update found before, unless it has since been installed. */
    fun known(): Available? = prefs.getString("available", null)?.let(Available::fromJson)
        ?.takeIf { Release.newer(it.version, BuildConfig.VERSION_NAME) }

    /** Debug builds are signed differently, so a release can't replace them. */
    val enabled get() = !BuildConfig.DEBUG

    fun due(now: Long) = enabled && now - prefs.getLong("checked", 0) >= DAY_MILLIS

    /** Asks GitHub; remembers the answer, and when it asked. */
    suspend fun check(): Available? {
        val found = Release.parse(JSONObject(String(fetch(LATEST))), BuildConfig.VERSION_NAME)
        prefs.edit().putLong("checked", System.currentTimeMillis()).putString("available", found?.toJson()).apply()
        return found
    }

    /** Downloads the update and checks its sum; answers the file. */
    suspend fun download(a: Available): File = withContext(Dispatchers.IO) {
        val want = Release.sumOf(String(fetch(a.sumsUrl)), a.apkName) ?: throw IOException("the release lists no checksum for its app")
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        open(a.apkUrl).useBody { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        if (Release.hex(digest.digest()) != want) {
            dir.deleteRecursively()
            throw IOException("the download doesn't match its checksum, so it was discarded")
        }
        file
    }

    /** Whether Android lets this app offer installs. */
    fun mayInstall() = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's page for allowing installs from this app. */
    fun askToInstall() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands [apk] to Android's installer, which asks before installing. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) { open(url).useBody { it.readBytes() } }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "licensebox-android")
        if (c.responseCode != 200) {
            val status = c.responseCode
            c.disconnect()
            throw IOException("GitHub answered $status")
        }
        return c
    }

    private inline fun <T> HttpURLConnection.useBody(block: (java.io.InputStream) -> T): T =
        try { inputStream.use(block) } finally { disconnect() }

    private companion object {
        const val LATEST = "https://api.github.com/repos/licensebox/android/releases/latest"
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
