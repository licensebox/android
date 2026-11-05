package dev.licensebox.android

import dev.licensebox.android.api.AdminApi
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.Request
import dev.licensebox.android.api.User
import dev.licensebox.android.ui.codeDigits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelsTest {
    @Test
    fun productAsTheServerWritesIt() {
        val p = Product.from(
            JSONObject(
                """{"id":"myapp","name":"","modules":["core","export","batch"],"free":["core"],
                "trial_modules":["export"],"trial_days":14,"lease_hours":0,"heartbeat":{},"machine_limit":0,
                "locations":{},"terms":1,"terms_url":"","contact":"","descriptions":null,"hidden":null,
                "usage":false,"admins":["admin@example.org"],"created":"2026-11-05T18:00:00.123456789Z"}""",
            ),
        )
        assertEquals("myapp", p.title) // no name: the id
        assertEquals(listOf("core", "export", "batch"), p.modules)
        assertEquals("free", p.kindOf("core"))
        assertEquals("trial", p.kindOf("export"))
        assertNull(p.kindOf("batch"))
        assertTrue(p.hidden.isEmpty() && p.descriptions.isEmpty())
        assertEquals(Instant.parse("2026-11-05T18:00:00.123456789Z"), p.created)
        assertNull(p.deleteAt) // omitted when not set
    }

    @Test
    fun goZeroTimeIsNone() {
        val r = Request.from(
            JSONObject(
                """{"id":"r_1","email":"ada@example.org","modules":["export"],"note":"for the report",
                "created":"2026-11-05T18:00:00+01:00","status":"pending","granted":null,
                "until":"0001-01-01T00:00:00Z","answer":"","answered_by":"","answered":"0001-01-01T00:00:00Z"}""",
            ),
        )
        assertNull(r.until)
        assertNull(r.answered)
        assertEquals(Instant.parse("2026-11-05T17:00:00Z"), r.created) // offsets kept
        assertTrue(r.granted.isEmpty())
    }

    @Test
    fun userWithGrantsAndMachines() {
        val u = User.from(
            JSONObject(
                """{"user":"u_1","product":"myapp","email":"ada@example.org","created":"2026-01-01T00:00:00Z",
                "purpose":"","terms":1,"revoked":false,"note":"",
                "machines":[{"id":"m1","binding":"b","key":"k","os":"linux","arch":"amd64","version":"1.2",
                  "first":"2026-01-01T00:00:00Z","last":"2026-11-01T00:00:00Z","revoked":true,"allocated":true}],
                "grants":[{"module":"export","until":"0001-01-01T00:00:00Z","note":"","created":"2026-01-01T00:00:00Z"},
                  {"module":"batch","until":"2026-02-01T00:00:00Z","note":"trial","created":"2026-01-01T00:00:00Z"}]}""",
            ),
        )
        assertEquals("u_1", u.id)
        assertTrue(u.machines.single().revoked)
        val (forever, ended) = u.grants
        assertTrue(forever.active())
        assertFalse(ended.active(Instant.parse("2026-03-01T00:00:00Z")))
    }

    @Test
    fun nullAndEmptyAnswers() {
        assertNull(AdminApi.parse(""))
        assertNull(AdminApi.parse("null\n"))
        assertTrue(AdminApi.parse("[]") is JSONArray)
        assertTrue(AdminApi.parse("{}") is JSONObject)
    }

    @Test
    fun pathsAreEscapedForAPath() {
        assertEquals("products/my%20app/users/ada%2Bx%40example.org", AdminApi.p("products", "my app", "users", "ada+x@example.org"))
    }

    @Test
    fun digitLetterKeysTypeCodeDigits() {
        assertEquals("123456", codeDigits("123456"))
        assertEquals("123456", codeDigits("werSDF"))
        assertEquals("789", codeDigits("zxc"))
        assertEquals("120", codeDigits("1 2-0q")) // what isn't a digit goes
        assertEquals("123456", codeDigits("1234567"))
    }
}
