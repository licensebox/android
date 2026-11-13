package dev.licensebox.android.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Checkout
import dev.licensebox.android.api.Customer
import dev.licensebox.android.api.EnrolToken
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.User
import dev.licensebox.android.api.formatCounts
import dev.licensebox.android.api.parseCounts
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class CustomersPage(val product: Product, val customers: List<Customer>)

/** Customer ids: lower-case letters, digits and -, as the server takes them. */
private val customerId = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

/** Service account names, as the server takes them. */
private val serviceName = Regex("^[a-z0-9][a-z0-9._-]{0,62}$")

/** A product's customers: each with its members and floating copies. A adds one. */
@Composable
fun CustomersScreen(model: AppModel, s: Screen.Customers) {
    val loader = rememberLoader(s) {
        val api = model.api
        coroutineScope {
            val p = async { api.product(s.product) }
            val c = async { api.customers(s.product) }
            CustomersPage(p.await(), c.await().sortedBy { it.title.lowercase() })
        }
    }
    loader.Run(model::failed)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    val list = loader.value?.customers.orEmpty()

    OnShortcut { k ->
        when (k.name) {
            "r" -> { loader.reload(); true }
            "a" -> { if (loader.value != null) adding = true; true }
            Shortcuts.ENTER -> { list.getOrNull(sel.index)?.takeIf { sel.shown }?.let { model.open(Screen.Customer(s.product, it.id)) }; true }
            else -> listKeys(k, sel, list.size, state, scope, offset = 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Customers", subtitle = loader.value?.product?.title ?: s.product, onBack = { model.back() }) {
            if (loader.value != null) CardAction(withKey("Add…", "A")) { adding = true }
        }
        Refreshable(loader) {
            Loaded(loader) { pg ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                        item {
                            Muted(
                                "A customer, such as a company, has members, floating copies of modules its team shares, " +
                                    "and service accounts for its servers." +
                                    if (!pg.product.heartbeat) " Floating copies need the product's heartbeat, set at licensebox.dev/admin." else "",
                                Modifier.padding(bottom = 10.dp),
                            )
                            if (pg.customers.isEmpty()) Muted("No customers yet.")
                        }
                        itemsIndexed(pg.customers) { i, c ->
                            CustomerRow(c, sel.isSelected(i)) { model.open(Screen.Customer(s.product, c.id)) }
                        }
                    }
                }
            }
        }
    }

    val p = loader.value?.product
    if (adding && p != null) {
        AddCustomerDialog(p, onDismiss = { adding = false }) { id, name, pools ->
            adding = false
            model.change("Added $id", after = { model.open(Screen.Customer(s.product, id)) }) { addCustomer(s.product, id, name, "", pools) }
        }
    }
}

@Composable
private fun CustomerRow(c: Customer, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.padding(bottom = 8.dp), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall)
                if (c.name.isNotBlank()) Muted(c.id)
            }
            if (c.checkouts.isNotEmpty()) Badge("${c.checkouts.size} in use")
        }
        Muted(
            listOfNotNull(
                plural(c.people.size, "member"),
                c.services.size.takeIf { it > 0 }?.let { plural(it, "service account") },
                c.pools.takeIf { it.isNotEmpty() }?.let { "copies: " + formatCounts(it) },
            ).joinToString("  ·  "),
            Modifier.padding(top = 4.dp),
        )
    }
}

/** A row of a customer's page that can be selected. */
private sealed interface CRow {
    data class Copy(val checkout: Checkout) : CRow
    data class Member(val user: User) : CRow
}

/**
 * A customer: the floating copies in use (Enter frees one), its members
 * and service accounts (Enter opens one). E edits the pools, M adds a
 * member, V a service account.
 */
@Composable
fun CustomerScreen(model: AppModel, s: Screen.Customer) {
    val loader = rememberLoader(s) {
        val api = model.api
        coroutineScope {
            val p = async { api.product(s.product) }
            val c = async { api.customer(s.product, s.id) }
            p.await() to c.await()
        }
    }
    loader.Run(model::failed)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    var pools by remember { mutableStateOf(false) }
    var member by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf(false) }
    var freeing by remember { mutableStateOf<Checkout?>(null) }

    val c = loader.value?.second
    val rows: List<CRow> = c?.checkouts.orEmpty().sortedBy { it.module }.map { CRow.Copy(it) } +
        (c?.people.orEmpty().sortedBy { it.email } + c?.services.orEmpty().sortedBy { it.email }).map { CRow.Member(it) }

    fun act(r: CRow) = when (r) {
        is CRow.Copy -> freeing = r.checkout
        is CRow.Member -> model.open(Screen.User(s.product, r.user.email))
    }

    OnShortcut { k ->
        when (k.name) {
            "r" -> { loader.reload(); true }
            "e" -> { if (c != null) pools = true; true }
            "m" -> { if (c != null) member = true; true }
            "v" -> { if (c != null) service = true; true }
            Shortcuts.ENTER -> { rows.getOrNull(sel.index)?.takeIf { sel.shown }?.let(::act); true }
            else -> listKeys(k, sel, rows.size, state, scope, offset = 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = c?.title ?: s.id, subtitle = loader.value?.first?.title ?: s.product, onBack = { model.back() })
        Refreshable(loader) {
            Loaded(loader) { (p, cu) ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                        item { CustomerSummary(p, cu, onPools = { pools = true }, onMember = { member = true }, onService = { service = true }) }
                        if (cu.members.isEmpty()) item {
                            SectionTitle("Members")
                            Muted("None yet. Add people who share ${cu.title}'s copies, or a service account for its servers.")
                        }
                        itemsIndexed(rows) { i, r ->
                            val prev = rows.getOrNull(i - 1)
                            when (r) {
                                is CRow.Copy -> {
                                    if (i == 0) SectionTitle("Copies in use")
                                    CopyRow(r.checkout, sel.isSelected(i)) { act(r) }
                                }
                                is CRow.Member -> {
                                    if (!r.user.service && (prev == null || prev is CRow.Copy)) SectionTitle("Members")
                                    if (r.user.service && (prev !is CRow.Member || !prev.user.service)) SectionTitle("Service accounts")
                                    Card(Modifier.padding(bottom = 8.dp), selected = sel.isSelected(i)) { UserLine(r.user, brief = true) { act(r) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val p = loader.value?.first
    if (pools && c != null && p != null) {
        PoolsDialog(p, c, onDismiss = { pools = false }) { next ->
            pools = false
            // A pool left out is taken away: the server keeps what isn't named.
            val all = c.pools.keys.associateWith { 0 } + next
            model.change("Floating copies saved", after = loader::reload) { updateCustomer(s.product, c.id, c.name, c.note, all) }
        }
    }
    if (member && c != null) {
        TextDialog("Add a member to ${c.title}", "Email", "They share ${c.title}'s floating copies from their app's next check.", "Add", KeyboardType.Email, { it.contains('@') }, { member = false }) { email ->
            member = false
            model.change("$email is in ${c.title}", after = loader::reload) { setCustomer(s.product, email, c.id) }
        }
    }
    if (service && c != null) {
        TextDialog(
            "Add a service account", "Name", "For one of ${c.title}'s servers or build machines, such as build-1. Grant it modules, then issue a token to enrol its machine.",
            "Add", KeyboardType.Ascii, { serviceName.matches(it) }, { service = false },
        ) { name ->
            service = false
            model.change("Added $name", after = { loader.reload(); model.open(Screen.User(s.product, name)) }) { addService(s.product, c.id, name) }
        }
    }
    freeing?.let { x ->
        ConfirmDialog("Free ${x.module}?", "${x.who}'s copy on ${x.machineId} goes back to ${c?.title ?: "the pool"}; its app takes another at its next heartbeat if one is free.", "Free", onDismiss = { freeing = null }) {
            freeing = null
            model.change("Freed ${x.module}", after = loader::reload) { freeCheckout(s.product, x.id) }
        }
    }
}

@Composable
private fun CustomerSummary(p: Product, c: Customer, onPools: () -> Unit, onMember: () -> Unit, onService: () -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        if (c.name.isNotBlank()) Muted(c.id)
        if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodyMedium)
        SectionTitle("Floating copies")
        if (c.pools.isEmpty()) Muted("None. Its members run only what they are granted.")
        else Chips {
            c.pools.entries.sortedBy { it.key }.forEach { (m, n) ->
                val used = c.checkouts.count { it.module == m }
                ModuleChip(m)
                Muted("$used of $n in use", Modifier.padding(end = 12.dp))
            }
        }
        if (!p.heartbeat && c.pools.isNotEmpty()) {
            Text("The product's heartbeat is off, so these copies can't be checked out.", color = LocalExtra.current.warn, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPools) { Text(withKey("Copies…", "E")) }
            OutlinedButton(onClick = onMember) { Text(withKey("Add member…", "M")) }
            OutlinedButton(onClick = onService) { Text(withKey("Service account…", "V")) }
        }
    }
}

@Composable
private fun CopyRow(x: Checkout, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.padding(bottom = 8.dp), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) { ModuleChip(x.module) }
            Muted(x.who)
        }
        Muted(
            listOfNotNull(x.machineId, x.since?.let { "since ${it.ago()}" }, x.expires?.let { "freed ${it.until()} without a heartbeat" }).joinToString("  ·  "),
            Modifier.padding(top = 4.dp),
        )
    }
}

/** A customer's pools: module=copies, for the product's modules. */
@Composable
private fun PoolsDialog(p: Product, c: Customer, onDismiss: () -> Unit, onSave: (Map<String, Int>) -> Unit) {
    var text by remember { mutableStateOf(formatCounts(c.pools)) }
    val parsed = parseCounts(text)
    val unknown = parsed?.keys?.filter { it !in p.modules }.orEmpty()
    val ok = parsed != null && unknown.isEmpty()
    val save = { if (ok) onSave(parsed!!) }
    KeyDialog("Floating copies", "Save", save, onDismiss, confirmEnabled = ok) {
        Text("for ${c.title}", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("Copies") }, singleLine = true, isError = !ok,
            placeholder = { Text("sim=2, cad=1") }, modifier = Modifier.fillMaxWidth().enterTo(save),
        )
        Muted(
            when {
                parsed == null -> "Write module=copies, such as sim=2."
                unknown.isNotEmpty() -> "${unknown.joinToString(", ")}: not a module of ${p.title}."
                else -> "How many machines of ${c.title}'s may run each module at once. Leave one out to take its copies away."
            },
        )
    }
}

/** Adds a customer: an id, a name, and floating copies if any. */
@Composable
private fun AddCustomerDialog(p: Product, onDismiss: () -> Unit, onAdd: (String, String, Map<String, Int>) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pools by remember { mutableStateOf("") }
    val parsed = parseCounts(pools)
    val ok = customerId.matches(id) && parsed != null && parsed.keys.all { it in p.modules } && (parsed.isEmpty() || p.heartbeat)
    val add = { if (ok) onAdd(id, name.trim(), parsed!!) }
    KeyDialog("Add a customer", "Add", add, onDismiss, confirmEnabled = ok) {
        OutlinedTextField(value = name, onValueChange = { v ->
            if (id == slug(name)) id = slug(v)
            name = v
        }, label = { Text("Name") }, singleLine = true, placeholder = { Text("Acme Ltd") }, modifier = Modifier.fillMaxWidth().typing())
        OutlinedTextField(
            value = id, onValueChange = { id = it.lowercase().trim() }, label = { Text("Id") }, singleLine = true, isError = id.isNotEmpty() && !customerId.matches(id),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().typing(),
        )
        OutlinedTextField(
            value = pools, onValueChange = { pools = it }, label = { Text("Floating copies (optional)") }, singleLine = true, isError = parsed == null,
            placeholder = { Text("sim=2") }, modifier = Modifier.fillMaxWidth().enterTo(add),
        )
        Muted(if (!p.heartbeat && parsed?.isNotEmpty() == true) "Floating copies need the product's heartbeat, set at licensebox.dev/admin." else "Lower-case letters, digits and - for the id. Copies as module=count.")
    }
}

private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')

/** A dialog asking for one line of text, valid by [valid]. */
@Composable
fun TextDialog(title: String, label: String, help: String, confirm: String, keyboard: KeyboardType, valid: (String) -> Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = valid(text.trim())
    val done = { if (ok) onDone(text.trim()) }
    KeyDialog(title, confirm, done, onDismiss, confirmEnabled = ok) {
        Muted(help)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().enterTo(done),
        )
    }
}

/** A service account's new token, shown once, to copy to its machine. */
@Composable
fun TokenDialog(t: EnrolToken, model: AppModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("enrolment token", t.token)))
            model.message = "Copied the token"
        }
        Unit
    }
    KeyDialog("Enrolment token", "Copy", copy, onDismiss) {
        Text("for ${t.name}", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(t.token, fontFamily = PlexMono, style = MaterialTheme.typography.bodyMedium) }
        Muted(
            "Shown once. Set it on the machine as LICENSEBOX_ENROL_TOKEN before the app's first run. It works once" +
                (t.expires?.let { ", until ${it.dateTime()}" } ?: "") + ". The machine it enrols replaces the one before.",
        )
        Spacer(Modifier.width(0.dp))
    }
}
