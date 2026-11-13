package dev.licensebox.android.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Customer
import dev.licensebox.android.api.Event
import dev.licensebox.android.api.formatCounts
import dev.licensebox.android.api.Key
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.Request
import dev.licensebox.android.api.Stats
import dev.licensebox.android.api.Usage
import dev.licensebox.android.api.User
import dev.licensebox.android.notify.RequestWatch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Everything a product's page shows, loaded together. */
data class ProductPage(
    val product: Product,
    val requests: List<Request>,
    val stats: Stats?,
    val users: List<User>,
    val events: List<Event>,
    val usage: Usage?,
    val keys: List<Key>,
    val customers: List<Customer> = emptyList(),
)

private enum class Part { Requests, Figures, Users, Activity, Customers, Modules, Usage, Settings, Admins, Keys }

/**
 * A product, as a page of cards: the requests waiting (answered here),
 * figures, the newest users, the latest activity, then its modules, usage,
 * settings, admins and keys. Requests, users and activity each open in full.
 */
@Composable
fun ProductScreen(model: AppModel, s: Screen.Product) {
    val id = s.id
    val loader = rememberLoader(id) {
        val api = model.api
        coroutineScope {
            // Only the product itself must load; the rest may fail alone.
            val p = async { api.product(id) }
            val rq = async { runCatching { api.requests(id) }.getOrElse { emptyList() } }
            val st = async { runCatching { api.stats(id) }.getOrNull() }
            val us = async { runCatching { api.users(id) }.getOrElse { emptyList() } }
            val ev = async { runCatching { api.events(id, 6) }.getOrElse { emptyList() } }
            val ug = async { runCatching { api.usage(id, 30) }.getOrNull() }
            val ks = async { runCatching { api.keys(id) }.getOrElse { emptyList() } }
            val cs = async { runCatching { api.customers(id) }.getOrElse { emptyList() } }
            ProductPage(p.await(), rq.await(), st.await(), us.await(), ev.await(), ug.await(), ks.await(), cs.await())
        }
    }
    loader.Run(model::failed)
    val context = LocalContext.current
    val answers = rememberAnswers(model, id, loader::reload)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    var addingAdmin by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    val page = loader.value
    val waiting = page?.requests.orEmpty().filter { it.status == "pending" }
    LaunchedEffect(page) { page?.let { RequestWatch.seen(context, id, it.requests) } }
    val parts = Part.entries.filter { it != Part.Usage || (page?.product?.usage == true && page.usage != null) }
    val banner = if (page?.product?.deleteAt != null) 1 else 0

    fun open(part: Part) = when (part) {
        Part.Requests -> model.open(Screen.Requests(id))
        Part.Users, Part.Figures -> model.open(Screen.Users(id))
        Part.Activity -> model.open(Screen.Activity(id))
        Part.Customers -> model.open(Screen.Customers(id))
        else -> Unit
    }

    OnShortcut { k ->
        when (k.name) {
            "r" -> { model.open(Screen.Requests(id)); true }
            "u" -> { model.open(Screen.Users(id)); true }
            "e" -> { model.open(Screen.Activity(id)); true }
            "c" -> { model.open(Screen.Customers(id)); true }
            "s", "/" -> { model.open(Screen.Users(id, search = true)); true }
            "a" -> { waiting.firstOrNull()?.let { answers.approving = it }; true }
            "d" -> { waiting.firstOrNull()?.let { answers.declining = it }; true }
            "g" -> { if (page != null) granting = true; true }
            Shortcuts.ENTER, "o" -> { parts.getOrNull(sel.index)?.takeIf { sel.shown }?.let(::open); true }
            else -> listKeys(k, sel, parts.size, state, scope, offset = banner)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = page?.product?.title ?: id, subtitle = if (page != null && page.product.name.isNotBlank()) id else null, onBack = { model.back() }) {
            if (page != null) CardAction(withKey("Grant…", "G")) { granting = true }
        }
        Refreshable(loader) {
            Loaded(loader) { pg ->
                val p = pg.product
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        p.deleteAt?.let { at ->
                            item {
                                Card {
                                    Text("Deleted ${at.until()}", style = MaterialTheme.typography.titleSmall, color = LocalExtra.current.warn)
                                    Muted("Apps are refused until then. Take it back at licensebox.dev/admin or with `licensebox undelete $id`.")
                                }
                            }
                        }
                        items(parts.size) { i ->
                            val selected = sel.isSelected(i)
                            when (parts[i]) {
                                Part.Requests -> RequestsCard(pg, waiting, selected, model, answers)
                                Part.Figures -> FiguresCard(pg.stats, selected) { model.open(Screen.Users(id)) }
                                Part.Users -> UsersCard(pg, selected, model)
                                Part.Activity -> ActivityCard(pg.events, selected) { model.open(Screen.Activity(id)) }
                                Part.Customers -> CustomersCard(pg.customers, selected) { model.open(Screen.Customers(id)) }
                                Part.Modules -> ModulesCard(p, pg.users, selected)
                                Part.Usage -> pg.usage?.let { UsageCard(it, selected) }
                                Part.Settings -> SettingsCard(p, selected)
                                Part.Admins -> AdminsCard(p, model.session?.email, selected, onAdd = { addingAdmin = true }, onRemove = { removing = it })
                                Part.Keys -> KeysCard(pg.keys, selected, model)
                            }
                        }
                    }
                }
            }
        }
    }

    answers.Dialogs(page?.product)
    val p = page?.product
    if (granting && p != null) {
        GrantDialog(p, null, onDismiss = { granting = false }) { email, modules, until, note ->
            granting = false
            model.change("Granted ${modules.joinToString(", ")} to $email", after = loader::reload) { grant(id, email, modules, until, note) }
        }
    }
    if (addingAdmin && p != null) {
        var email by remember { mutableStateOf("") }
        val add = {
            if (email.contains('@')) {
                addingAdmin = false
                model.change("$email is an admin of ${p.title}", after = loader::reload) { addAdmin(id, email.trim()) }
            }
        }
        KeyDialog("Add an admin", "Add", add, { addingAdmin = false }, confirmEnabled = email.contains('@')) {
            Muted("They'll manage ${p.title} as you do: users, requests, settings.")
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().enterTo(add),
            )
        }
    }
    removing?.let { a ->
        ConfirmDialog("Remove $a?", "They'll no longer manage ${p?.title ?: id}.", "Remove", onDismiss = { removing = null }) {
            removing = null
            model.change("Removed $a", after = loader::reload) { removeAdmin(id, a) }
        }
    }
}

@Composable
private fun RequestsCard(pg: ProductPage, waiting: List<Request>, selected: Boolean, model: AppModel, answers: Answers) {
    val id = pg.product.id
    val answered = pg.requests.size - waiting.size
    SectionCard(
        "Requests", count = waiting.size, countHighlight = true, selected = selected,
        more = when {
            waiting.size > 3 -> "See all ${waiting.size} waiting" + if (answered > 0) " ($answered answered)" else ""
            answered > 0 -> "See all requests ($answered answered)"
            else -> null
        },
        moreKey = "R", onMore = { model.open(Screen.Requests(id)) },
    ) {
        if (waiting.isEmpty()) {
            Muted("Nothing waiting. Requests appear here when users ask for modules in their app.", Modifier.padding(bottom = 4.dp))
        }
        waiting.take(3).forEachIndexed { i, r ->
            if (i > 0) Divider()
            RequestSlip(r, pg.product, { model.open(Screen.User(id, r.email)) }, { answers.approving = r }, { answers.declining = r }, showKeys = i == 0)
        }
    }
}

@Composable
private fun FiguresCard(st: Stats?, selected: Boolean, onUsers: () -> Unit) {
    SectionCard("Figures", selected = selected) {
        Tiles(
            (st?.users?.toString() ?: "–") to "users",
            (st?.active?.toString() ?: "–") to "active machines",
            (st?.machines?.toString() ?: "–") to "machines",
            onClick = { onUsers() },
        )
        val lines = listOfNotNull(
            st?.revoked?.takeIf { it > 0 }?.let { "$it revoked" },
            st?.versions?.takeIf { it.isNotEmpty() }?.let { v ->
                val top = v.maxBy { it.value }
                "most on ${top.key.ifBlank { "an unknown version" }} (${top.value}), ${plural(v.size, "version")} in use"
            },
        )
        if (lines.isNotEmpty()) Muted(lines.joinToString(";  ").replaceFirstChar { it.uppercase() }, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun UsersCard(pg: ProductPage, selected: Boolean, model: AppModel) {
    val id = pg.product.id
    val newest = pg.users.ordered(UserOrder.Newest).take(3)
    SectionCard(
        "Users", count = pg.users.size, selected = selected,
        action = { CardAction(withKey("Search", "S")) { model.open(Screen.Users(id, search = true)) } },
        more = if (pg.users.isEmpty()) null else "All ${plural(pg.users.size, "user")}", moreKey = "U",
        onMore = { model.open(Screen.Users(id)) },
    ) {
        if (newest.isEmpty()) Muted("No one has signed in yet.", Modifier.padding(bottom = 4.dp))
        else Muted("Newest", Modifier.padding(bottom = 2.dp))
        newest.forEachIndexed { i, u ->
            if (i > 0) Divider()
            UserLine(u) { model.open(Screen.User(id, u.email)) }
        }
    }
}

@Composable
private fun ActivityCard(events: List<Event>, selected: Boolean, onAll: () -> Unit) {
    SectionCard("Activity", selected = selected, more = "All activity", moreKey = "E", onMore = onAll) {
        if (events.isEmpty()) Muted("Nothing yet.", Modifier.padding(bottom = 4.dp))
        events.sortedByDescending { it.time }.take(4).forEachIndexed { i, e ->
            if (i > 0) Divider()
            EventLine(e, timeAgo = true)
        }
    }
}

@Composable
private fun CustomersCard(customers: List<Customer>, selected: Boolean, onAll: () -> Unit) {
    val inUse = customers.sumOf { it.checkouts.size }
    SectionCard(
        "Customers", count = customers.size, selected = selected,
        action = if (inUse > 0) ({ Muted("$inUse ${if (inUse == 1) "copy" else "copies"} in use", Modifier.padding(end = 4.dp)) }) else null,
        more = if (customers.isEmpty()) "Add a customer" else "All customers", moreKey = "C", onMore = onAll,
    ) {
        if (customers.isEmpty()) Muted("Organisations, with floating copies their teams share and service accounts for their servers.", Modifier.padding(bottom = 4.dp))
        customers.sortedByDescending { it.checkouts.size }.take(3).forEachIndexed { i, c ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOfNotNull(plural(c.members.size, "member"), c.pools.takeIf { it.isNotEmpty() }?.let { formatCounts(it) }).joinToString("  ·  "))
                }
                if (c.checkouts.isNotEmpty()) Muted("${c.checkouts.size} in use", Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun ModulesCard(p: Product, users: List<User>, selected: Boolean) {
    SectionCard("Modules", count = p.modules.size, selected = selected) {
        p.modules.forEachIndexed { i, m ->
            if (i > 0) Divider()
            val holders = users.count { u -> u.grants.any { it.module == m && it.active() } }
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row { ModuleChip(m, p.kindOf(m)) }
                    p.descriptions[m]?.takeIf { it.isNotBlank() }?.let { Muted(it, Modifier.padding(top = 4.dp, start = 2.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                Muted(
                    when (p.kindOf(m)) {
                        "free" -> "everyone"
                        else -> if (holders == 0) "no grants" else "$holders granted"
                    },
                    Modifier.padding(top = 3.dp),
                )
            }
        }
        if (p.trialModules.isNotEmpty()) {
            Muted("Trial: ${p.trialModules.joinToString(", ")} for ${p.trialDays} days from a user's first sign-in.", Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun UsageCard(u: Usage, selected: Boolean) {
    SectionCard("Usage", selected = selected, action = { Muted("last 30 days", Modifier.padding(end = 4.dp)) }) {
        if (u.runs == 0) {
            Muted("No runs counted yet.")
            return@SectionCard
        }
        Tiles(u.runs.toString() to "runs", u.failed.toString() to "failed", u.users.size.toString() to "users")
        val top = u.commands.sortedByDescending { it.runs }.take(5)
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val most = top.first().runs.coerceAtLeast(1)
            top.forEach { c ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, fontFamily = PlexMono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${c.runs}" + if (c.failed > 0) " · ${c.failed} failed" else "")
                    }
                    // A bar for its share of the most-run command.
                    Box(
                        Modifier.padding(top = 3.dp).fillMaxWidth(c.runs.toFloat() / most).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(p: Product, selected: Boolean) {
    SectionCard("Settings", selected = selected) {
        Facts(
            "Offline for" to "${if (p.leaseHours == 0) 24 else p.leaseHours} hours",
            "Machines per user" to if (p.machineLimit == 0) "no limit" else p.machineLimit.toString(),
            "Seats apart" to if (p.moduleSeats.isEmpty()) "none" else formatCounts(p.moduleSeats),
            "Heartbeat" to if (p.heartbeat) "every ${p.heartbeatInterval} s, ${p.heartbeatGrace} s grace" else "off",
            "Terms" to if (p.terms == 0) "none" else "version ${p.terms}" + if (p.termsUrl.isNotBlank()) "\n${p.termsUrl}" else "",
            "Contact" to p.contact.ifBlank { "–" },
            "Usage counts" to if (p.usage) "on" else "off",
            "Created" to (p.created?.date() ?: "–"),
        )
        Muted("Change these at licensebox.dev/admin or with the licensebox command.", Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AdminsCard(p: Product, me: String?, selected: Boolean, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    SectionCard("Admins", count = p.admins.size, selected = selected, action = { CardAction("Add", onAdd) }) {
        p.admins.forEachIndexed { i, a ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(a, size = 28)
                Spacer(Modifier.width(10.dp))
                Text(a + if (a == me) "  (you)" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a != me && p.admins.size > 1) TextButton(onClick = { onRemove(a) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun KeysCard(keys: List<Key>, selected: Boolean, model: AppModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SectionCard("Signing keys", count = keys.size, selected = selected) {
        keys.sortedByDescending { it.current }.forEachIndexed { i, k ->
            if (i > 0) Divider()
            Column(
                Modifier.fillMaxWidth()
                    .clickable {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", "${k.kid}=${k.public}")))
                            model.message = "Copied ${k.kid}'s public key"
                        }
                    }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.kid, fontFamily = PlexMono, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (k.current) Badge("current")
                }
                Muted(k.public, maxLines = 1)
                Muted((k.created?.let { "Made ${it.date()}  ·  " } ?: "") + "tap to copy")
            }
        }
    }
}
