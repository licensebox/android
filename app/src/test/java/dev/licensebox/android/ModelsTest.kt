package dev.licensebox.android

import dev.licensebox.android.api.AdminApi
import dev.licensebox.android.api.Customer
import dev.licensebox.android.api.Join
import dev.licensebox.android.api.formatCounts
import dev.licensebox.android.api.parseCounts
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

    @Test
    fun seatsAsTheServerWritesThem() {
        fun user(field: String) = User.from(JSONObject("""{"user":"u","email":"a@b.c"$field}"""))
        assertNull(user("").machineLimit) // omitted: the product's
        assertEquals("2, the product's default", user("").seats(2))
        assertEquals("no limit, the product's default", user("").seats(0))
        assertEquals(3, user(""","machine_limit":3""").machineLimit)
        assertEquals("3, for this user", user(""","machine_limit":3""").seats(1))
        assertEquals(0, user(""","machine_limit":0""").machineLimit)
        assertEquals("no limit, for this user", user(""","machine_limit":0""").seats(1))
    }

    @Test
    fun customerAsTheServerWritesIt() {
        val c = Customer.from(
            JSONObject(
                """{"id":"acme","product":"myapp","name":"Acme Ltd","pools":{"sim":2},"created":"2026-11-13T10:00:00Z",
                "members":[{"user":"u_1","email":"ada@example.org","customer":"acme"},
                           {"user":"u_2","email":"","customer":"acme","service":true,"name":"build-1"}],
                "checkouts":[{"id":"co_1","customer":"acme","module":"sim","user":"u_1","machine":"abcdefghijklmnop",
                              "since":"2026-11-13T10:00:00Z","expires":"2026-11-13T10:15:00Z","who":"ada@example.org","machine_id":"m-abc"}]}""",
            ),
        )
        assertEquals("Acme Ltd", c.title)
        assertEquals(mapOf("sim" to 2), c.pools)
        assertEquals(listOf("ada@example.org"), c.people.map { it.email })
        // A service account is named by its name, where admin calls take an email.
        assertEquals(listOf("build-1"), c.services.map { it.email })
        assertEquals("m-abc", c.checkouts.single().machineId)
        assertEquals("ada@example.org", c.checkouts.single().who)
    }

    @Test
    fun heartbeatAndSeatsApart() {
        val p = Product.from(JSONObject("""{"id":"a","heartbeat":{"interval_seconds":300,"grace_seconds":900},"module_seats":{"server":1}}"""))
        assertTrue(p.heartbeat)
        assertEquals(mapOf("server" to 1), p.moduleSeats)
        assertFalse(Product.from(JSONObject("""{"id":"a","heartbeat":{}}""")).heartbeat)
    }

    @Test
    fun countsAsTyped() {
        assertEquals(mapOf("sim" to 2, "cad" to 1), parseCounts("sim=2, CAD=1"))
        assertEquals(emptyMap<String, Int>(), parseCounts("  "))
        assertNull(parseCounts("sim"))
        assertNull(parseCounts("sim=x"))
        assertNull(parseCounts("sim=-1"))
        assertEquals("cad=1, sim=2", formatCounts(mapOf("sim" to 2, "cad" to 1)))
    }

    @Test
    fun customerBodyNamesItsPools() {
        val b = AdminApi("", null).customerBody("acme", "Acme", "", mapOf("sim" to 0))
        assertEquals(0, b.getJSONObject("pools").getInt("sim"))
    }

    @Test
    fun joinAsTheServerWritesIt() {
        // A pending request: no answer yet, so no answered time at all.
        val j = Join.from(
            JSONObject(
                """{"email":"bo@example.org","name":"Bo","about":"A slicer","website":"https://example.org",
                "created":"2026-11-13T19:58:00Z","status":"pending"}""",
            ),
        )
        assertEquals("bo@example.org", j.email)
        assertEquals("pending", j.status)
        assertEquals("", j.answer)
        assertEquals(null, j.answered)
    }
}
