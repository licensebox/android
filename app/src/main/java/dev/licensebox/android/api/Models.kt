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
    /** Seats per user of modules counted apart from the product's. */
    val moduleSeats: Map<String, Int> = emptyMap(),
    /** The heartbeat's interval and grace in seconds; 0 when it is off. */
    val heartbeatInterval: Int = 0,
    val heartbeatGrace: Int = 0,
) {
    val heartbeat get() = heartbeatInterval > 0 && heartbeatGrace > 0

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
            moduleSeats = o.counts("module_seats"),
            heartbeatInterval = o.optJSONObject("heartbeat")?.optInt("interval_seconds") ?: 0,
            heartbeatGrace = o.optJSONObject("heartbeat")?.optInt("grace_seconds") ?: 0,
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
    /** The modules counted apart whose seats this machine holds. */
    val seatedModules: List<String> = emptyList(),
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
            seatedModules = o.strings("seated_modules"),
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
    /** This user's own machine seats, 0 for no limit; null is the product's. */
    val machineLimit: Int? = null,
    /** The customer (organisation) the user belongs to, if any. */
    val customer: String = "",
    /** A service account, which [email] then names by its name. */
    val service: Boolean = false,
) {
    /** How many seats this user has, in words, given the product's default. */
    fun seats(productLimit: Int): String = when (machineLimit) {
        null -> (if (productLimit == 0) "no limit" else "$productLimit") + ", the product's default"
        0 -> "no limit, for this user"
        else -> "$machineLimit, for this user"
    }

    companion object {
        fun from(o: JSONObject) = User(
            id = o.optString("user"),
            // Admin calls take a service account's name where an email goes.
            email = if (o.optBoolean("service")) o.optString("name") else o.optString("email"),
            created = o.time("created"),
            purpose = o.optString("purpose"),
            terms = o.optInt("terms"),
            revoked = o.optBoolean("revoked"),
            note = o.optString("note"),
            machines = o.objects("machines").map(Machine::from),
            grants = o.objects("grants").map(Grant::from),
            machineLimit = if (o.isNull("machine_limit")) null else o.optInt("machine_limit"),
            customer = o.optString("customer"),
            service = o.optBoolean("service"),
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

/** A floating copy of a module, checked out by a machine. */
data class Checkout(
    val id: String,
    val customer: String,
    val module: String,
    val who: String,
    val machineId: String,
    val since: Instant?,
    val expires: Instant?,
) {
    companion object {
        fun from(o: JSONObject) = Checkout(
            id = o.optString("id"),
            customer = o.optString("customer"),
            module = o.optString("module"),
            who = o.optString("who"),
            machineId = o.optString("machine_id").ifBlank { o.optString("machine").take(12) },
            since = o.time("since"),
            expires = o.time("expires"),
        )
    }
}

/**
 * A customer, such as a company: its members, the floating copies of
 * modules its pools hold, and the copies checked out now.
 */
data class Customer(
    val id: String,
    val name: String,
    val note: String,
    val pools: Map<String, Int>,
    val created: Instant?,
    val members: List<User>,
    val checkouts: List<Checkout>,
) {
    val title get() = name.ifBlank { id }
    val people get() = members.filter { !it.service }
    val services get() = members.filter { it.service }

    companion object {
        fun from(o: JSONObject) = Customer(
            id = o.optString("id"),
            name = o.optString("name"),
            note = o.optString("note"),
            pools = o.counts("pools"),
            created = o.time("created"),
            members = o.objects("members").map(User::from),
            checkouts = o.objects("checkouts").map(Checkout::from),
        )
    }
}

/** A service account's one-time enrolment token, shown once. */
data class EnrolToken(val token: String, val name: String, val expires: Instant?) {
    companion object {
        fun from(o: JSONObject) = EnrolToken(o.optString("token"), o.optString("name"), o.time("expires"))
    }
}

/** Counts by module, as "sim=2, cad=1"; null if they don't read so. */
fun parseCounts(text: String): Map<String, Int>? {
    val out = linkedMapOf<String, Int>()
    for (part in text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return null
        val n = v.trim().toIntOrNull() ?: return null
        if (k.isBlank() || n < 0) return null
        out[k.trim().lowercase()] = n
    }
    return out
}

fun formatCounts(m: Map<String, Int>) = m.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }

data class Me(val email: String, val operator: Boolean, val mayCreate: Boolean)

/**
 * Someone's request to create products, made on licensebox.dev/join with
 * their address confirmed by email. Operators answer these.
 */
data class Join(
    val email: String,
    val name: String,
    val about: String,
    val website: String,
    val created: Instant?,
    /** "pending", "approved" or "declined". */
    val status: String,
    val answer: String,
    val answeredBy: String,
    val answered: Instant?,
) {
    companion object {
        fun from(o: JSONObject) = Join(
            email = o.optString("email"),
            name = o.optString("name"),
            about = o.optString("about"),
            website = o.optString("website"),
            created = o.time("created"),
            status = o.optString("status"),
            answer = o.optString("answer"),
            answeredBy = o.optString("answered_by"),
            answered = o.time("answered"),
        )
    }
}

data class Session(val token: String, val email: String, val expires: Instant?)

// JSON helpers.

internal fun JSONObject.strings(name: String): List<String> {
    val a = optJSONArray(name) ?: return emptyList()
    return List(a.length()) { a.optString(it) }
}

internal fun JSONObject.counts(name: String): Map<String, Int> =
    optJSONObject(name)?.let { d -> d.keys().asSequence().associateWith { d.optInt(it) } } ?: emptyMap()

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
