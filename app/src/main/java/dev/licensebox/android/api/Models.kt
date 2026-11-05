package dev.licensebox.android.api

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// The admin API's records, as licensebox.dev/admin (Go) has them. Times the
// server leaves unset come as Go's zero time, read here as null.

data class Product(
    val id: String,
    val name: String,
    val modules: List<String>,
    val free: List<String>,
    val trialModules: List<String>,
    val trialDays: Int,
    val leaseHours: Int,
    val machineLimit: Int,
    val terms: Int,
    val termsUrl: String,
    val contact: String,
    val descriptions: Map<String, String>,
    val hidden: List<String>,
    val usage: Boolean,
    val admins: List<String>,
    val created: Instant?,
    val deleteAt: Instant?,
) {
    val title get() = name.ifBlank { id }

    /** How a module is offered: "free", "trial" or "hidden", or null. */
    fun kindOf(module: String): String? = when (module) {
        in free -> "free"
        in trialModules -> "trial"
        in hidden -> "hidden"
        else -> null
    }

    companion object {
        fun from(o: JSONObject) = Product(
            id = o.optString("id"),
            name = o.optString("name"),
            modules = o.strings("modules"),
            free = o.strings("free"),
            trialModules = o.strings("trial_modules"),
            trialDays = o.optInt("trial_days"),
            leaseHours = o.optInt("lease_hours"),
            machineLimit = o.optInt("machine_limit"),
            terms = o.optInt("terms"),
            termsUrl = o.optString("terms_url"),
            contact = o.optString("contact"),
            descriptions = o.optJSONObject("descriptions")?.let { d ->
                d.keys().asSequence().associateWith { d.optString(it) }
            } ?: emptyMap(),
            hidden = o.strings("hidden"),
            usage = o.optBoolean("usage"),
            admins = o.strings("admins"),
            created = o.time("created"),
            deleteAt = o.time("delete_at"),
        )
    }
}

data class Request(
    val id: String,
    val email: String,
    val modules: List<String>,
    val note: String,
    val created: Instant?,
    /** "pending", "approved" or "declined". */
    val status: String,
    val granted: List<String>,
    val until: Instant?,
    val answer: String,
    val answeredBy: String,
    val answered: Instant?,
) {
    companion object {
        fun from(o: JSONObject) = Request(
            id = o.optString("id"),
            email = o.optString("email"),
            modules = o.strings("modules"),
            note = o.optString("note"),
            created = o.time("created"),
            status = o.optString("status"),
            granted = o.strings("granted"),
            until = o.time("until"),
            answer = o.optString("answer"),
            answeredBy = o.optString("answered_by"),
            answered = o.time("answered"),
        )
    }
}

data class Grant(val module: String, val until: Instant?, val note: String, val created: Instant?) {
    fun active(now: Instant = Instant.now()) = until == null || now.isBefore(until)

    companion object {
        fun from(o: JSONObject) = Grant(o.optString("module"), o.time("until"), o.optString("note"), o.time("created"))
    }
}

data class Machine(
    val id: String,
    val os: String,
    val arch: String,
    val version: String,
    val first: Instant?,
    val last: Instant?,
    val revoked: Boolean,
    val allocated: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Machine(
            id = o.optString("id"),
            os = o.optString("os"),
            arch = o.optString("arch"),
            version = o.optString("version"),
            first = o.time("first"),
            last = o.time("last"),
            revoked = o.optBoolean("revoked"),
            allocated = o.optBoolean("allocated"),
        )
    }
}

data class User(
    val id: String,
    val email: String,
    val created: Instant?,
    val purpose: String,
    val terms: Int,
    val revoked: Boolean,
    val note: String,
    val machines: List<Machine>,
    val grants: List<Grant>,
) {
    companion object {
        fun from(o: JSONObject) = User(
            id = o.optString("user"),
            email = o.optString("email"),
            created = o.time("created"),
            purpose = o.optString("purpose"),
            terms = o.optInt("terms"),
            revoked = o.optBoolean("revoked"),
            note = o.optString("note"),
            machines = o.objects("machines").map(Machine::from),
            grants = o.objects("grants").map(Grant::from),
        )
    }
}

data class Event(val time: Instant?, val kind: String, val user: String, val detail: String, val by: String) {
    companion object {
        fun from(o: JSONObject) = Event(o.time("time"), o.optString("kind"), o.optString("user"), o.optString("detail"), o.optString("by"))
    }
}

data class Stats(val users: Int, val revoked: Int, val machines: Int, val active: Int, val versions: Map<String, Int>) {
    companion object {
        fun from(o: JSONObject) = Stats(
            users = o.optInt("users"),
            revoked = o.optInt("revoked"),
            machines = o.optInt("machines"),
            active = o.optInt("active_machines"),
            versions = o.optJSONObject("versions")?.let { v -> v.keys().asSequence().associateWith { v.optInt(it) } } ?: emptyMap(),
        )
    }
}

data class UsageLine(val name: String, val runs: Int, val failed: Int, val millis: Long, val users: Int) {
    companion object {
        fun from(o: JSONObject) = UsageLine(o.optString("name"), o.optInt("runs"), o.optInt("failed"), o.optLong("ms"), o.optInt("users"))
    }
}

data class Usage(
    val since: String,
    val runs: Int,
    val failed: Int,
    val days: List<UsageLine>,
    val commands: List<UsageLine>,
    val users: List<UsageLine>,
    val versions: List<UsageLine>,
) {
    companion object {
        fun from(o: JSONObject) = Usage(
            since = o.optString("since"),
            runs = o.optInt("runs"),
            failed = o.optInt("failed"),
            days = o.objects("days").map(UsageLine::from),
            commands = o.objects("commands").map(UsageLine::from),
            users = o.objects("users").map(UsageLine::from),
            versions = o.objects("versions").map(UsageLine::from),
        )
    }
}

data class Key(val kid: String, val public: String, val current: Boolean, val created: Instant?) {
    companion object {
        fun from(o: JSONObject) = Key(o.optString("kid"), o.optString("public"), o.optBoolean("current"), o.time("created"))
    }
}

data class Me(val email: String, val operator: Boolean, val mayCreate: Boolean)

data class Session(val token: String, val email: String, val expires: Instant?)

// JSON helpers.

internal fun JSONObject.strings(name: String): List<String> {
    val a = optJSONArray(name) ?: return emptyList()
    return List(a.length()) { a.optString(it) }
}

internal fun JSONObject.objects(name: String): List<JSONObject> = optJSONArray(name)?.objects() ?: emptyList()

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { optJSONObject(it) ?: JSONObject() }

/** A time, or null for none: absent, empty, or Go's zero time (year 1). */
internal fun JSONObject.time(name: String): Instant? {
    val s = optString(name)
    if (s.isEmpty()) return null
    return try {
        // OffsetDateTime, not Instant.parse: older Androids' Instant.parse
        // refuses offsets other than Z.
        OffsetDateTime.parse(s).toInstant().takeIf { it.isAfter(EPOCH_ISH) }
    } catch (_: Exception) {
        null
    }
}

private val EPOCH_ISH = Instant.parse("1971-01-01T00:00:00Z")
