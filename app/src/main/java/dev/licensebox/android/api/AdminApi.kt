package dev.licensebox.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

const val DEFAULT_SERVER = "https://licensebox.dev"

/** An error the server answered with: its status, code and description. */
class ApiError(val status: Int, val code: String, val detail: String) : IOException(detail.ifBlank { code }) {
    /** The session is gone: expired, signed out elsewhere, or never valid. */
    val signedOut get() = status == 401 && code == "unauthorized"
}

/**
 * A client of licensebox's admin API, the same calls as the Go admin
 * package and the `licensebox` command make.
 */
class AdminApi(server: String, private val token: String?) {
    private val server = server.trimEnd('/').ifBlank { DEFAULT_SERVER }

    // Sign-in.

    /** Mails a sign-in code to an admin's address, if it may sign in. */
    suspend fun startLogin(email: String) {
        call("POST", "login", JSONObject().put("email", email))
    }

    suspend fun finishLogin(email: String, code: String): Session {
        val o = call("POST", "login/finish", JSONObject().put("email", email).put("code", code)) as JSONObject
        return Session(o.optString("token"), o.optString("email"), o.time("expires"))
    }

    suspend fun logout() {
        call("POST", "logout")
    }

    suspend fun me(): Me {
        val o = call("GET", "me") as JSONObject
        return Me(o.optString("email"), o.optBoolean("operator"), o.optBoolean("may_create"))
    }

    // Products.

    suspend fun products(): List<Product> = list("GET", "products").map(Product::from)

    suspend fun product(id: String) = Product.from(call("GET", p("products", id)) as JSONObject)

    suspend fun keys(product: String) = list("GET", p("products", product, "keys")).map(Key::from)

    suspend fun stats(product: String) = Stats.from(call("GET", p("products", product, "stats")) as JSONObject)

    suspend fun usage(product: String, days: Int = 30) =
        Usage.from(call("GET", p("products", product, "usage") + "?days=$days") as JSONObject)

    suspend fun events(product: String, limit: Int = 200) =
        list("GET", p("products", product, "events") + "?limit=$limit").map(Event::from)

    suspend fun addAdmin(product: String, email: String) =
        Product.from(call("POST", p("products", product, "admins"), JSONObject().put("email", email)) as JSONObject)

    suspend fun removeAdmin(product: String, email: String) =
        Product.from(call("DELETE", p("products", product, "admins", email)) as JSONObject)

    // Requests.

    /** The product's requests, newest first, of the status given or all. */
    suspend fun requests(product: String, status: String = "") =
        list("GET", p("products", product, "requests") + "?status=" + q(status)).map(Request::from)

    /** Grants the request's modules, or those given, until a time or for good. */
    suspend fun approve(product: String, id: String, modules: List<String>, until: Instant?, note: String) =
        Request.from(call("POST", p("products", product, "requests", id, "approve"), answer(modules, until, note)) as JSONObject)

    suspend fun decline(product: String, id: String, note: String) =
        Request.from(call("POST", p("products", product, "requests", id, "decline"), answer(emptyList(), null, note)) as JSONObject)

    // Users.

    /** The product's users, those whose email or purpose contains [query]. */
    suspend fun users(product: String, query: String = "") =
        list("GET", p("products", product, "users") + "?q=" + q(query)).map(User::from)

    suspend fun user(product: String, email: String) = User.from(call("GET", p("products", product, "users", email)) as JSONObject)

    /** Grants modules; the address need not have signed in yet. */
    suspend fun grant(product: String, email: String, modules: List<String>, until: Instant?, note: String) =
        (call("POST", p("products", product, "grants"), grantBody(email, modules, until, note)) as JSONArray).objects().map(Grant::from)

    suspend fun ungrant(product: String, email: String, modules: List<String>) =
        (call("POST", p("products", product, "grants", "remove"), grantBody(email, modules, null, "")) as JSONArray).objects().map(Grant::from)

    /** Refuses a user's apps, and their signing in again. */
    suspend fun revoke(product: String, email: String, note: String) {
        call("POST", p("products", product, "revoke"), JSONObject().put("email", email).put("note", note))
    }

    suspend fun restore(product: String, email: String) {
        call("POST", p("products", product, "restore"), JSONObject().put("email", email))
    }

    suspend fun revokeMachine(product: String, machine: String, note: String) {
        call("POST", p("products", product, "revoke"), JSONObject().put("machine", machine).put("note", note))
    }

    suspend fun restoreMachine(product: String, machine: String) {
        call("POST", p("products", product, "restore"), JSONObject().put("machine", machine))
    }

    // Plumbing.

    private fun answer(modules: List<String>, until: Instant?, note: String) = JSONObject().apply {
        put("modules", JSONArray(modules))
        put("note", note)
        until?.let { put("until", it.toString()) }
    }

    private fun grantBody(email: String, modules: List<String>, until: Instant?, note: String) = JSONObject().apply {
        put("email", email)
        put("modules", JSONArray(modules))
        put("note", note)
        until?.let { put("until", it.toString()) }
    }

    private suspend fun list(method: String, path: String): List<JSONObject> =
        when (val v = call(method, path)) {
            is JSONArray -> v.objects()
            else -> emptyList() // the server writes null for none
        }

    /** Calls /v1/admin/[path]; answers the JSON value, or null for none. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null): Any? = withContext(Dispatchers.IO) {
        val c = URL("$server/v1/admin/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "licensebox-android")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status != 200) {
                val e = runCatching { JSONObject(text) }.getOrNull()
                val code = e?.optString("error").orEmpty()
                if (code.isEmpty()) throw ApiError(status, "http_$status", "the server answered $status")
                throw ApiError(status, code, e?.optString("error_description").orEmpty())
            }
            parse(text)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        internal fun parse(text: String): Any? {
            val t = text.trim()
            return when {
                t.isEmpty() || t == "null" -> null
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t)
                else -> null
            }
        }

        /** Path segments, each escaped, joined with slashes. */
        internal fun p(vararg parts: String) = parts.joinToString("/") { pathEscape(it) }

        // URLEncoder is for forms: it writes spaces as +, wrong in a path.
        internal fun pathEscape(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun q(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
