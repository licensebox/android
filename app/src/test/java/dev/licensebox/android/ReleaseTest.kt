package dev.licensebox.android

import dev.licensebox.android.update.Available
import dev.licensebox.android.update.Release
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val base = "https://github.com/licensebox/android/releases/download/v0.4.0/"

    private fun release(tag: String = "v0.4.0", apk: String = base + "licensebox-android-v0.4.0.apk", sums: String = base + "SHA256SUMS") = JSONObject(
        """{"tag_name":"$tag","assets":[
        {"name":"licensebox-android-$tag.apk","browser_download_url":"$apk"},
        {"name":"SHA256SUMS","browser_download_url":"$sums"}]}""",
    )

    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(Release.newer("v0.10.0", "0.9.0"))
        assertTrue(Release.newer("v1.0", "0.9.9"))
        assertFalse(Release.newer("v0.3.0", "0.3.0"))
        assertFalse(Release.newer("v0.3", "0.3.0"))
        assertFalse(Release.newer("v0.2.9", "0.3.0"))
        assertFalse(Release.newer("nightly", "0.3.0"))
    }

    @Test
    fun findsTheUpdate() {
        val a = Release.parse(release(), "0.3.0")!!
        assertEquals("0.4.0", a.version)
        assertEquals("licensebox-android-v0.4.0.apk", a.apkName)
        assertEquals(a, Available.fromJson(a.toJson()))
    }

    @Test
    fun noUpdateWhenCurrent() = assertNull(Release.parse(release(tag = "v0.3.0"), "0.3.0"))

    @Test
    fun onlyThisRepositorysDownloads() {
        assertNull(Release.parse(release(apk = "https://example.org/licensebox-android-v0.4.0.apk"), "0.3.0"))
        assertNull(Release.parse(release(sums = "https://example.org/SHA256SUMS"), "0.3.0"))
    }

    @Test
    fun readsTheChecksumFile() {
        val sums = "ABC123  licensebox-android-v0.4.0.apk\ndef456 *other.apk\n"
        assertEquals("abc123", Release.sumOf(sums, "licensebox-android-v0.4.0.apk"))
        assertEquals("def456", Release.sumOf(sums, "other.apk"))
        assertNull(Release.sumOf(sums, "missing.apk"))
    }
}
