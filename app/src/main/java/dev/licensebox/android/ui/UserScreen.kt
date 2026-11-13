package dev.licensebox.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.ApiError
import dev.licensebox.android.api.EnrolToken
import dev.licensebox.android.api.Grant
import dev.licensebox.android.api.Machine
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.User
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class UserPage(val product: Product, val user: User?)

/** A row of the user's page that can be selected: a grant or a machine. */
private sealed interface Row_ {
    data class G(val grant: Grant) : Row_
    data class M(val machine: Machine) : Row_
}

/**
 * A user of a product: their grants and machines. G grants, X revokes or
 * restores them; Enter on a grant takes it back, on a machine revokes or
 * restores it.
 */
@Composable
fun UserScreen(model: AppModel, s: Screen.User) {
    val loader = rememberLoader(s) {
        val api = model.api
        coroutineScope {
            val p = async { api.product(s.product) }
            // Granted to but not yet signed in: the server knows no user.
            val u = async { runCatching { api.user(s.product, s.email) }.recover { if (it is ApiError && it.status == 404) null else throw it }.getOrThrow() }
            UserPage(p.await(), u.await())
        }
    }
    loader.Run(model::failed)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()

    var granting by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf<Grant?>(null) }
    var machine by remember { mutableStateOf<Machine?>(null) }
    var seating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var issuing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<EnrolToken?>(null) }

    val page = loader.value
    val u = page?.user
    val now = java.time.Instant.now()
    val grants = u?.grants.orEmpty().sortedWith(compareBy({ !it.active(now) }, { it.module }))
    val rows: List<Row_> = grants.map { Row_.G(it) } + u?.machines.orEmpty().sortedByDescending { it.last }.map { Row_.M(it) }

    fun act(r: Row_) = when (r) {
        is Row_.G -> taking = r.grant
        is Row_.M -> machine = r.machine
    }

    OnShortcut { k ->
        when (k.name) {
            "r" -> { loader.reload(); true }
            "g" -> { if (page != null) granting = true; true }
            "s" -> { if (u != null && !u.service) seating = true; true }
            "c" -> { if (u != null) moving = true; true }
            "t" -> { if (u?.service == true) issuing = true; true }
            "x" -> { if (u != null) { if (u.revoked) restoring = true else revoking = true }; true }
            Shortcuts.ENTER -> { rows.getOrNull(sel.index)?.takeIf { sel.shown }?.let(::act); true }
            else -> listKeys(k, sel, rows.size, state, scope, offset = 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = s.email, subtitle = page?.product?.title ?: s.product, onBack = { model.back() })
        Refreshable(loader) {
            Loaded(loader) { pg ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                        item {
                            Summary(
                                pg.user, pg.product.machineLimit, onGrant = { granting = true }, onSeats = { seating = true }, onRevoke = { revoking = true }, onRestore = { restoring = true },
                                onCustomer = { moving = true }, onToken = { issuing = true }, onOpenCustomer = { c -> model.open(Screen.Customer(s.product, c)) },
                            )
                        }
                        itemsIndexed(rows) { i, r ->
                            if (i == 0 && r is Row_.G) SectionTitle("Modules granted")
                            if (r is Row_.M && (i == 0 || rows[i - 1] is Row_.G)) SectionTitle("Machines")
                            when (r) {
                                is Row_.G -> GrantRow(r.grant, pg.product, sel.isSelected(i)) { act(r) }
                                is Row_.M -> MachineRow(r.machine, sel.isSelected(i)) { act(r) }
                            }
                        }
                        item {
                            val free = pg.product.free
                            if (free.isNotEmpty()) {
                                SectionTitle("Free to everyone")
                                Chips { free.forEach { ModuleChip(it, "free") } }
                            }
                        }
                    }
                }
            }
        }
    }

    val p = page?.product
    if (granting && p != null) {
        GrantDialog(p, s.email, held = grants.filter { it.active() && it.until == null }.map { it.module }.toSet(), onDismiss = { granting = false }) { email, modules, until, note ->
            granting = false
            model.change("Granted ${modules.joinToString(", ")}", after = loader::reload) { grant(s.product, email, modules, until, note) }
        }
    }
    taking?.let { g ->
        ConfirmDialog("Take back ${g.module}?", "${s.email} can no longer run ${g.module}, from their app's next check.", "Take back", onDismiss = { taking = null }) {
            taking = null
            model.change("Took back ${g.module}", after = loader::reload) { ungrant(s.product, s.email, listOf(g.module)) }
        }
    }
    if (seating && p != null && u != null) {
        SeatsDialog(s.email, u.machineLimit, p.machineLimit, onDismiss = { seating = false }) { limit ->
            seating = false
            model.change("Seats changed for ${s.email}", after = loader::reload) { setSeats(s.product, s.email, limit) }
        }
    }
    if (moving && u != null) {
        TextDialog(
            if (u.customer.isBlank()) "Add ${s.email} to a customer" else "Move ${s.email}", "Customer id",
            "They share the customer's floating copies. Leave it empty to take them out of ${u.customer.ifBlank { "any" }}.",
            "Save", androidx.compose.ui.text.input.KeyboardType.Ascii, { it.isEmpty() || Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(it) }, { moving = false },
        ) { c ->
            moving = false
            model.change(if (c.isEmpty()) "Out of ${u.customer}" else "In $c", after = loader::reload) { setCustomer(s.product, s.email, c) }
        }
    }
    if (issuing) {
        ConfirmDialog(
            "Issue an enrolment token?", "It enrols one machine for ${s.email}, once, within a week. The machine it enrols replaces the one before.",
            "Issue", destructive = false, onDismiss = { issuing = false },
        ) {
            issuing = false
            model.change(null, after = loader::reload) { token = serviceToken(s.product, s.email) }
        }
    }
    token?.let { TokenDialog(it, model) { token = null } }
    if (revoking) {
        NoteDialog(
            "Revoke ${s.email}?", "Their apps are refused from their next check, and they can't sign in again until restored.",
            "Revoke", "Why (kept with the user, optional)", onDismiss = { revoking = false },
        ) { note ->
            revoking = false
            model.change("Revoked ${s.email}", after = loader::reload) { revoke(s.product, s.email, note) }
        }
    }
    if (restoring) {
        ConfirmDialog("Restore ${s.email}?", "Their apps work again from their next check.", "Restore", destructive = false, onDismiss = { restoring = false }) {
            restoring = false
            model.change("Restored ${s.email}", after = loader::reload) { restore(s.product, s.email) }
        }
    }
    machine?.let { m ->
        if (m.revoked) {
            ConfirmDialog("Restore machine ${m.id}?", "It may run the product again.", "Restore", destructive = false, onDismiss = { machine = null }) {
                machine = null
                model.change("Restored ${m.id}", after = loader::reload) { restoreMachine(s.product, m.id) }
            }
        } else {
            NoteDialog(
                "Revoke machine ${m.id}?", "${machineName(m)}. It is refused from its next check; their other machines keep working.",
                "Revoke", "Why (optional)", onDismiss = { machine = null },
            ) { note ->
                machine = null
                model.change("Revoked ${m.id}", after = loader::reload) { revokeMachine(s.product, m.id, note) }
            }
        }
    }
}

@Composable
private fun Summary(
    u: User?, productLimit: Int, onGrant: () -> Unit, onSeats: () -> Unit, onRevoke: () -> Unit, onRestore: () -> Unit,
    onCustomer: () -> Unit, onToken: () -> Unit, onOpenCustomer: (String) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        if (u == null) {
            Muted("Not signed in yet. Modules granted now are theirs when they do.")
        } else {
            if (u.revoked) {
                Text("Revoked" + if (u.note.isNotBlank()) ": ${u.note}" else "", color = LocalExtra.current.warn, fontWeight = FontWeight.SemiBold)
            }
            if (u.purpose.isNotBlank()) Text(u.purpose, style = MaterialTheme.typography.bodyMedium)
            Muted(listOfNotNull(u.created?.let { "Signed up ${it.date()}" }, if (u.terms > 0) "accepted terms v${u.terms}" else null).joinToString(", "))
            if (u.service) Muted("Service account: one machine, enrolled with a one-time token")
            else Muted("Machine seats: ${u.seats(productLimit)}")
            if (u.customer.isNotBlank()) {
                androidx.compose.material3.TextButton(onClick = { onOpenCustomer(u.customer) }, contentPadding = PaddingValues(0.dp)) { Text("Customer: ${u.customer}  ›") }
            }
        }
        // Wraps: three buttons don't fit across a square screen.
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGrant) { Text(withKey("Grant…", "G")) }
            if (u != null) {
                if (u.service) OutlinedButton(onClick = onToken) { Text(withKey("Token…", "T")) }
                else OutlinedButton(onClick = onSeats) { Text(withKey("Seats…", "S")) }
                OutlinedButton(onClick = onCustomer) { Text(withKey("Customer…", "C")) }
                if (u.revoked) OutlinedButton(onClick = onRestore) { Text(withKey("Restore", "X")) }
                else OutlinedButton(onClick = onRevoke) { Text(withKey("Revoke…", "X"), color = LocalExtra.current.warn) }
            }
        }
    }
}

@Composable
private fun GrantRow(g: Grant, p: Product, selected: Boolean, onClick: () -> Unit) {
    val active = g.active()
    Card(Modifier.padding(bottom = 8.dp), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) { ModuleChip(g.module, p.kindOf(g.module).takeIf { it == "hidden" }, muted = !active) }
            Muted(
                when {
                    g.until == null -> "for good"
                    active -> "until ${g.until.date()}"
                    else -> "ended ${g.until.date()}"
                },
            )
        }
        if (g.note.isNotBlank()) Muted(g.note, Modifier.padding(top = 4.dp))
    }
}

private fun machineName(m: Machine) =
    listOf(m.os, m.arch).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown system" } +
        if (m.version.isNotBlank()) ", version ${m.version}" else ""

@Composable
private fun MachineRow(m: Machine, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.padding(bottom = 8.dp), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(machineName(m), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (m.revoked) Badge("revoked", LocalExtra.current.warn, MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.height(2.dp))
        Muted(
            listOfNotNull(
                m.id,
                m.last?.let { "last seen ${it.ago()}" },
                m.first?.let { "first ${it.date()}" },
                if (m.allocated) "holds a seat" else null,
                m.seatedModules.takeIf { it.isNotEmpty() }?.let { "seats of ${it.joinToString(", ")}" },
            ).joinToString("  ·  "),
        )
    }
}
