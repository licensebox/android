package dev.licensebox.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.licensebox.android.MainActivity
import dev.licensebox.android.R
import dev.licensebox.android.api.AdminApi
import dev.licensebox.android.api.ApiError
import dev.licensebox.android.api.Join
import dev.licensebox.android.api.Request
import dev.licensebox.android.data.SessionStore
import java.util.concurrent.TimeUnit

/**
 * Notifies the admin of new requests in their products. The server mails
 * admins too; this is for those who'd rather see it on the phone. WorkManager
 * checks every 15 minutes at most (Android's shortest), when online.
 */
object RequestWatch {
    const val EXTRA_PRODUCT = "product"
    /** A notification's tap that opens the requests to create products. */
    const val EXTRA_JOINS = "joins"
    private const val WORK = "request-watch"
    private const val CHANNEL = "requests"
    private const val GROUP = "requests"
    private const val SUMMARY_ID = 1
    private const val JOINS_ID = 3

    private fun prefs(c: Context) = c.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit { putBoolean("enabled", on) }
        if (on) start(c) else stop(c)
    }

    /** Whether to ask Android 13+ for leave to notify: once, if not given. */
    fun shouldAsk(c: Context) = !prefs(c).getBoolean("asked", false) && !allowed(c)
    fun asked(c: Context) = prefs(c).edit { putBoolean("asked", true) }

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun start(c: Context) {
        if (!enabled(c)) return
        val work = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun stop(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }

    /** Requests the admin has seen in the app need no notification. */
    fun seen(c: Context, product: String, requests: List<Request>) {
        val ids = requests.filter { it.status == "pending" }.map { "$product/${it.id}" }
        val p = prefs(c)
        p.edit { putStringSet("seen", p.getStringSet("seen", emptySet())!! + ids) }
        val nm = NotificationManagerCompat.from(c)
        nm.cancel(idOf(product))
        // A summary over one notification, or none, says nothing.
        val left = c.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id != SUMMARY_ID && it.id != idOf(product) }
        if (left < 2) nm.cancel(SUMMARY_ID)
    }

    /** Requests to create products seen in the app need no notification. */
    fun seenJoins(c: Context, joins: List<Join>) {
        val ids = joins.filter { it.status == "pending" }.map { "join:${it.email}" }
        val p = prefs(c)
        p.edit { putStringSet("seen", p.getStringSet("seen", emptySet())!! + ids) }
        NotificationManagerCompat.from(c).cancel(JOINS_ID)
    }

    /** A product's notification id: never the summary's or the joins'. */
    private fun idOf(product: String) = product.hashCode().let { if (it == SUMMARY_ID || it == JOINS_ID) 2 else it }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext
            val session = SessionStore(c).load() ?: return Result.success().also { stop(c) }
            // Not allowed to notify (yet: Android 13 asks after sign-in):
            // check nothing, so what waits is still told of once allowed.
            if (!allowed(c)) return Result.success()
            val api = AdminApi(session.server, session.token)
            val seen = prefs(c).getStringSet("seen", emptySet())!!
            val pending = mutableSetOf<String>()
            val told = mutableListOf<String>()
            try {
                for (p in api.products()) {
                    val waiting = api.requests(p.id, "pending")
                    pending += waiting.map { "${p.id}/${it.id}" }
                    val fresh = waiting.filter { "${p.id}/${it.id}" !in seen }
                    if (fresh.isNotEmpty()) {
                        notify(c, p.id, p.title, fresh, waiting.size)
                        told += "${p.title}: ${fresh.size} new"
                    }
                }
                // Operators hear of requests to create products too.
                if (api.me().operator) {
                    val waiting = api.joins("pending")
                    pending += waiting.map { "join:${it.email}" }
                    val fresh = waiting.filter { "join:${it.email}" !in seen }
                    if (fresh.isNotEmpty()) {
                        notifyJoins(c, fresh, waiting.size)
                        told += "Create products: ${fresh.size} new"
                    }
                }
                summarize(c, told)
            } catch (e: ApiError) {
                if (e.signedOut) stop(c)
                return Result.success()
            } catch (_: Exception) {
                return Result.retry()
            }
            // Everything waiting has now been told of; what was answered
            // drops out, so the set stays small.
            prefs(c).edit { putStringSet("seen", pending) }
            return Result.success()
        }
    }

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = c.getString(R.string.channel_requests_about) },
            )
        }
    }

    /**
     * With requests in more than one product, a summary over their
     * notifications, as Android groups them: tapped, it opens the products.
     */
    private fun summarize(c: Context, told: List<String>) {
        // Those just posted may not be listed as active yet: count them too.
        val shown = c.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id != SUMMARY_ID }
        if (maxOf(shown, told.size) < 2) return
        val open = PendingIntent.getActivity(
            c, SUMMARY_ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { s -> told.forEach(s::addLine) }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Requests waiting")
            .setContentText(told.joinToString(", ").ifEmpty { "In more than one product" })
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(SUMMARY_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun notify(c: Context, product: String, name: String, fresh: List<Request>, waiting: Int) {
        channel(c)
        val open = PendingIntent.getActivity(
            c, idOf(product),
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PRODUCT, product)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val first = fresh.first()
        val title = if (fresh.size == 1) "${first.email} asks for ${first.modules.joinToString(", ")}"
        else "${fresh.size} new requests in $name"
        val text = if (fresh.size == 1) name + if (first.note.isNotBlank()) ": “${first.note}”" else ""
        else fresh.joinToString(", ") { it.email }
        val style = NotificationCompat.InboxStyle().also { s ->
            fresh.take(6).forEach { s.addLine("${it.email}: ${it.modules.joinToString(", ")}") }
            if (waiting > fresh.size) s.setSummaryText("$waiting waiting in $name")
        }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (fresh.size == 1) NotificationCompat.BigTextStyle().bigText(text) else style)
            .setNumber(waiting)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(idOf(product), n)
        } catch (_: SecurityException) {
            // Leave to notify was taken back between the check and now.
        }
    }

    private fun notifyJoins(c: Context, fresh: List<Join>, waiting: Int) {
        channel(c)
        val open = PendingIntent.getActivity(
            c, JOINS_ID,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_JOINS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val first = fresh.first()
        val title = if (fresh.size == 1) "${first.name.ifBlank { first.email }} asks to create products"
        else "${fresh.size} new requests to create products"
        val text = if (fresh.size == 1) first.email + if (first.about.isNotBlank()) ": “${first.about}”" else ""
        else fresh.joinToString(", ") { it.email }
        val style = NotificationCompat.InboxStyle().also { s ->
            fresh.take(6).forEach { s.addLine("${it.email}: ${it.about.take(60)}") }
            if (waiting > fresh.size) s.setSummaryText("$waiting waiting")
        }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (fresh.size == 1) NotificationCompat.BigTextStyle().bigText(text) else style)
            .setNumber(waiting)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(JOINS_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
