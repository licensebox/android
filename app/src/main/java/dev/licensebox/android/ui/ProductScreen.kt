package dev.licensebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Event
import dev.licensebox.android.api.Key
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.Request
import dev.licensebox.android.api.Stats
import dev.licensebox.android.api.Usage
import dev.licensebox.android.api.User
import dev.licensebox.android.notify.RequestWatch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** The product's overview, as the console's: what Info shows. */
data class Overview(val stats: Stats?, val usage: Usage?, val keys: List<Key>)

/**
 * A product, in four tabs as the console has them: Requests (waiting first),
 * Users, Activity and Info. A tab's letter opens it; pressed again, it
 * reloads.
 */
@Composable
fun ProductScreen(model: AppModel, s: Screen.Product) {
    val id = s.id
    val product = rememberLoader(id) { model.api.product(id) }
    val requests = rememberLoader(id) { model.api.requests(id) }
    val events = rememberLoader(id) { model.api.events(id, 200) }
    val overview = rememberLoader(id) {
        val api = model.api
        coroutineScope {
            val st = async { runCatching { api.stats(id) }.getOrNull() }
            val us = async { runCatching { api.usage(id, 30) }.getOrNull() }
            val ks = async { runCatching { api.keys(id) }.getOrElse { emptyList() } }
            Overview(st.await(), us.await(), ks.await())
        }
    }
    var query by rememberSaveable(id) { mutableStateOf("") }
    val users = rememberLoader(id, query) {
        delay(if (query.isEmpty()) 0 else 300) // typing: wait for a pause
        model.api.users(id, query)
    }
    product.Run(model::failed)
    requests.Run(model::failed)
    if (s.tab == Tab.Users) users.Run(model::failed)
    if (s.tab == Tab.Activity) events.Run(model::failed)
    if (s.tab == Tab.Info) overview.Run(model::failed)

    val loaders = mapOf(Tab.Requests to requests, Tab.Users to users, Tab.Activity to events, Tab.Info to overview)
    fun go(t: Tab) {
        if (t == s.tab) {
            loaders[t]?.reload()
            if (t == Tab.Info) product.reload()
        } else {
            model.replace(s.copy(tab = t))
        }
    }

    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var granting by remember { mutableStateOf(false) }
    OnShortcut { k ->
        val tab = Tab.entries.firstOrNull { it.key == k.name }
        when {
            tab != null -> { go(tab); true }
            (k.name == "s" || k.name == "/") && s.tab == Tab.Users -> { runCatching { searchFocus.requestFocus() }; true }
            k.name == "s" || k.name == "/" -> { model.replace(s.copy(tab = Tab.Users)); true }
            k.name == "g" && product.value != null -> { granting = true; true }
            else -> false
        }
    }

    val p = product.value
    val waiting = requests.value?.count { it.status == "pending" } ?: 0
    Column(Modifier.fillMaxSize()) {
        Header(title = p?.title ?: id, subtitle = if (p != null && p.name.isNotBlank()) id else null, onBack = { model.back() })
        TabBar(s.tab, waiting, ::go)
        Box(Modifier.weight(1f)) {
            when (s.tab) {
                Tab.Requests -> RequestsTab(model, id, p, requests)
                Tab.Users -> UsersTab(model, id, users, query, { query = it }, searchFocus, focusManager) { granting = true }
                Tab.Activity -> ActivityTab(events)
                Tab.Info -> InfoTab(model, product, overview)
            }
        }
    }
    if (granting && p != null) {
        GrantDialog(p, null, onDismiss = { granting = false }) { email, modules, until, note ->
            granting = false
            model.change("Granted ${modules.joinToString(", ")} to $email", after = users::reload) {
                grant(id, email, modules, until, note)
            }
        }
    }
}

/** Tabs, each with its key letter shown, as a keyboard user needs. */
@Composable
private fun TabBar(tab: Tab, waiting: Int, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).clickable { onTab(t) }.padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (t == Tab.Requests && waiting > 0) {
                        Spacer(Modifier.padding(start = 4.dp))
                        Badge("$waiting")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

// Requests.

@Composable
private fun RequestsTab(model: AppModel, product: String, p: Product?, loader: Loader<List<Request>>) {
    val context = LocalContext.current
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    var approving by remember { mutableStateOf<Request?>(null) }
    var declining by remember { mutableStateOf<Request?>(null) }
    // Waiting first, then the latest answered.
    val list = loader.value.orEmpty().let { all ->
        all.filter { it.status == "pending" } + all.filter { it.status != "pending" }.take(30)
    }
    val waiting = list.count { it.status == "pending" }
    LaunchedEffect(loader.value) { loader.value?.let { RequestWatch.seen(context, product, it) } }

    OnShortcut { k ->
        val r = list.getOrNull(sel.index)?.takeIf { sel.shown }
        when (k.name) {
            "a" -> { (r ?: list.firstOrNull())?.takeIf { it.status == "pending" }?.let { approving = it }; true }
            "d" -> { (r ?: list.firstOrNull())?.takeIf { it.status == "pending" }?.let { declining = it }; true }
            Shortcuts.ENTER, "o" -> { r?.let { model.open(Screen.User(product, it.email)) }; true }
            else -> listKeys(k, sel, list.size, state, scope, offset = 1)
        }
    }

    Refreshable(loader) {
        Loaded(loader) { _ ->
            Centered {
                LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        val keys = hasKeyboard()
                        if (waiting == 0) Muted("Nothing waiting. Requests appear here when users ask for modules in their app.", Modifier.padding(4.dp))
                        else Muted("${plural(waiting, "request")} waiting." + if (keys) " A approves, D declines." else "", Modifier.padding(horizontal = 4.dp))
                    }
                    itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
                        if (i == waiting && i > 0) SectionTitle("Answered")
                        RequestCard(r, p, sel.isSelected(i),
                            onOpen = { model.open(Screen.User(product, r.email)) },
                            onApprove = { approving = r }, onDecline = { declining = r })
                    }
                }
            }
        }
    }

    approving?.let { r ->
        ApproveDialog(r.email, r.modules, r.note, p, onDismiss = { approving = null }) { modules, until, note ->
            approving = null
            model.change("Approved: ${r.email} has ${modules.joinToString(", ")}", after = loader::reload) {
                approve(product, r.id, modules, until, note)
            }
        }
    }
    declining?.let { r ->
        NoteDialog(
            title = "Decline ${r.email}?", body = "They asked for ${r.modules.joinToString(", ")}. They'll be told no.",
            confirm = "Decline", noteLabel = "Note to them (optional)", onDismiss = { declining = null },
        ) { note ->
            declining = null
            model.change("Declined ${r.email}", after = loader::reload) { decline(product, r.id, note) }
        }
    }
}

@Composable
private fun RequestCard(r: Request, p: Product?, selected: Boolean, onOpen: () -> Unit, onApprove: () -> Unit, onDecline: () -> Unit) {
    val pending = r.status == "pending"
    Card(selected = selected, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.email, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(r.created?.ago() ?: "")
        }
        Spacer(Modifier.height(8.dp))
        Chips { r.modules.forEach { ModuleChip(it, p?.kindOf(it), muted = !pending && it !in r.granted) } }
        if (r.note.isNotBlank()) {
            Text("“${r.note}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (pending) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onDecline) { Text("Decline") }
            }
        } else {
            val x = LocalExtra.current
            val how = if (r.status == "approved") {
                "Approved" + (if (r.granted.isNotEmpty() && r.granted.toSet() != r.modules.toSet()) ": " + r.granted.joinToString(", ") else "") +
                    (r.until?.let { ", until ${it.date()}" } ?: "")
            } else "Declined"
            Text(
                how, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                color = if (r.status == "approved") x.good else x.warn, modifier = Modifier.padding(top = 8.dp),
            )
            val by = listOfNotNull(r.answeredBy.ifBlank { null }, r.answered?.ago()).joinToString(", ")
            if (by.isNotBlank()) Muted("by $by")
            if (r.answer.isNotBlank()) Muted("“${r.answer}”")
        }
    }
}

// Users.

@Composable
private fun UsersTab(
    model: AppModel,
    product: String,
    loader: Loader<List<User>>,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    focusManager: FocusManager,
    onGrant: () -> Unit,
) {
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    val list = loader.value.orEmpty()
    OnShortcut { k ->
        when (k.name) {
            Shortcuts.ENTER, "o" -> { list.getOrNull(sel.index)?.takeIf { sel.shown }?.let { model.open(Screen.User(product, it.email)) }; true }
            else -> listKeys(k, sel, list.size, state, scope, offset = 1)
        }
    }
    Refreshable(loader) {
        Centered {
            LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query, onValueChange = onQuery, singleLine = true,
                            placeholder = { Text(withKey("Search email or purpose", "(S)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
                            // Enter leaves the field, so J/K and Enter work on the list.
                            modifier = Modifier.weight(1f).focusRequester(searchFocus).enterTo {
                                focusManager.clearFocus()
                                sel.index = 0
                                sel.shown = list.isNotEmpty()
                            },
                        )
                        TextButton(onClick = onGrant) { Text(withKey("Grant…", "G")) }
                    }
                }
                when (val st = loader.state) {
                    is Load.Loading -> item { Muted("Loading…", Modifier.padding(8.dp)) }
                    is Load.Failed -> item { Muted(describe(st.error), Modifier.padding(8.dp)) }
                    is Load.Done -> {
                        if (st.value.isEmpty()) item {
                            Muted(if (query.isEmpty()) "No users yet." else "No one matches “$query”.", Modifier.padding(8.dp))
                        }
                        itemsIndexed(st.value, key = { _, u -> u.id.ifBlank { u.email } }) { i, u ->
                            UserRow(u, sel.isSelected(i)) { model.open(Screen.User(product, u.email)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(u: User, selected: Boolean, onOpen: () -> Unit) {
    Card(selected = selected, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(u.email, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (u.revoked) Badge("revoked", LocalExtra.current.warn, MaterialTheme.colorScheme.surface)
        }
        if (u.purpose.isNotBlank()) Muted(u.purpose, maxLines = 1)
        val active = u.grants.filter { it.active() }
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Chips { active.forEach { ModuleChip(it.module) } }
        }
        Muted(
            listOfNotNull(
                plural(u.machines.size, "machine"),
                u.machines.mapNotNull { it.last }.maxOrNull()?.let { "last seen ${it.ago()}" },
                u.created?.let { "since ${it.date()}" },
            ).joinToString("  ·  "),
            Modifier.padding(top = 6.dp),
        )
    }
}

// Activity.

@Composable
private fun ActivityTab(loader: Loader<List<Event>>) {
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    OnShortcut { k -> listKeys(k, sel, loader.value?.size ?: 0, state, scope) }
    Refreshable(loader) {
        Loaded(loader) { list ->
            Centered {
                LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (list.isEmpty()) item { Muted("No activity yet.", Modifier.padding(16.dp)) }
                    itemsIndexed(list) { i, e -> EventRow(e, sel.isSelected(i)) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: Event, selected: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.kind, style = MaterialTheme.typography.labelLarge, fontFamily = PlexMono, modifier = Modifier.weight(1f))
            Muted(e.time?.ago() ?: "")
        }
        if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodyMedium)
        if (e.by.isNotBlank()) Muted("by ${e.by}")
    }
    Divider()
}

// Info.

@Composable
private fun InfoTab(model: AppModel, product: Loader<Product>, overview: Loader<Overview>) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sel = rememberSelection()
    val clipboard = LocalClipboard.current
    var addingAdmin by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    OnShortcut { k -> listKeys(k, sel, 0, state, scope) }
    Refreshable(overview) {
        Loaded(product) { p ->
            val o = overview.value
            Centered {
                LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    item {
                        SectionTitle("Figures")
                        val st = o?.stats
                        Row(Modifier.fillMaxWidth()) {
                            Figure(st?.users?.toString() ?: "–", "users", Modifier.weight(1f))
                            Figure(st?.active?.toString() ?: "–", "active machines", Modifier.weight(1f))
                            Figure(st?.machines?.toString() ?: "–", "machines", Modifier.weight(1f))
                        }
                        if ((st?.revoked ?: 0) > 0) Muted("${st!!.revoked} revoked", Modifier.padding(top = 4.dp))
                        st?.versions?.takeIf { it.isNotEmpty() }?.let { v ->
                            Muted("Versions: " + v.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.ifBlank { "unknown" }} (${it.value})" }, Modifier.padding(top = 6.dp))
                        }
                    }
                    item {
                        SectionTitle("Modules")
                        p.modules.forEach { m ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Chips { ModuleChip(m, p.kindOf(m)) }
                                p.descriptions[m]?.takeIf { it.isNotBlank() }?.let { Muted(it, Modifier.padding(start = 4.dp, top = 2.dp)) }
                            }
                        }
                    }
                    o?.usage?.takeIf { p.usage }?.let { u ->
                        item {
                            SectionTitle("Usage, last 30 days")
                            Row(Modifier.fillMaxWidth()) {
                                Figure(u.runs.toString(), "runs", Modifier.weight(1f))
                                Figure(u.failed.toString(), "failed", Modifier.weight(1f))
                                Figure(u.users.size.toString(), "users", Modifier.weight(1f))
                            }
                            if (u.commands.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                u.commands.sortedByDescending { it.runs }.take(8).forEach { c ->
                                    Fact(c.name, "${c.runs} runs" + if (c.failed > 0) ", ${c.failed} failed" else "")
                                }
                            }
                        }
                    }
                    item {
                        SectionTitle("Settings")
                        Fact("Trial", if (p.trialModules.isEmpty()) "none" else "${p.trialModules.joinToString(", ")} for ${p.trialDays} days")
                        Fact("Offline for", "${if (p.leaseHours == 0) 24 else p.leaseHours} hours")
                        Fact("Machines per user", if (p.machineLimit == 0) "no limit" else p.machineLimit.toString())
                        Fact("Terms", if (p.terms == 0) "none" else "version ${p.terms}" + if (p.termsUrl.isNotBlank()) ", ${p.termsUrl}" else "")
                        Fact("Contact", p.contact.ifBlank { "–" })
                        Fact("Usage counts", if (p.usage) "on" else "off")
                        p.created?.let { Fact("Created", it.date()) }
                        p.deleteAt?.let { Fact("Deleted", "${it.dateTime()} (undo at licensebox.dev/admin)") }
                        Muted("Change settings at licensebox.dev/admin or with the licensebox command.", Modifier.padding(top = 4.dp))
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Admins", Modifier.weight(1f))
                            TextButton(onClick = { addingAdmin = true }) { Text("Add") }
                        }
                        p.admins.forEach { a ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(a, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (a != model.session?.email && p.admins.size > 1) {
                                    TextButton(onClick = { removing = a }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    item {
                        SectionTitle("Signing keys")
                        if (o == null) Muted("Loading…")
                        o?.keys?.forEach { k ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", "${k.kid}=${k.public}")))
                                            model.message = "Copied ${k.kid}'s public key"
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(k.kid, fontFamily = PlexMono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (k.current) Badge("current")
                                }
                                Muted(k.public, maxLines = 1)
                                Muted((k.created?.let { "Made ${it.date()}. " } ?: "") + "Tap to copy the public key.")
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingAdmin) {
        var email by remember { mutableStateOf("") }
        val add = {
            if (email.contains('@')) {
                addingAdmin = false
                model.change("$email is an admin of ${p(product)}", after = product::reload) { addAdmin(product.value!!.id, email.trim()) }
            }
        }
        KeyDialog("Add an admin", "Add", add, { addingAdmin = false }, confirmEnabled = email.contains('@')) {
            Muted("They'll manage this product as you do: users, requests, settings.")
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().enterTo(add),
            )
        }
    }
    removing?.let { a ->
        ConfirmDialog("Remove $a?", "They'll no longer manage ${p(product)}.", "Remove", onDismiss = { removing = null }) {
            removing = null
            model.change("Removed $a", after = product::reload) { removeAdmin(product.value!!.id, a) }
        }
    }
}

private fun p(l: Loader<Product>) = l.value?.title ?: ""

/** Pull to reload, for touch; R or the tab's letter does it from keys. */
@Composable
fun Refreshable(loader: Loader<*>, content: @Composable () -> Unit) {
    PullToRefreshBox(
        isRefreshing = loader.refreshing && loader.value != null,
        onRefresh = loader::reload,
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}
