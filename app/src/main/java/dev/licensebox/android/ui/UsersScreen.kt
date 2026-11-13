package dev.licensebox.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.User
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant

enum class UserOrder(val label: String) { Newest("Newest"), Seen("Last seen"), Name("A–Z") }

fun User.lastSeen(): Instant? = machines.mapNotNull { it.last }.maxOrNull()

fun List<User>.ordered(o: UserOrder): List<User> = when (o) {
    UserOrder.Newest -> sortedByDescending { it.created }
    UserOrder.Seen -> sortedByDescending { it.lastSeen() }
    UserOrder.Name -> sortedBy { it.email.lowercase() }
}

/**
 * A user in a list: who, what for, what they hold, and how lately seen.
 * A customer's member list has neither grants nor machines: [brief] shows
 * who and what for alone.
 */
@Composable
fun UserLine(u: User, brief: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Avatar(u.email, dimmed = u.revoked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    u.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (u.service) {
                    Spacer(Modifier.width(8.dp))
                    Badge("service", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (u.revoked) {
                    Spacer(Modifier.width(8.dp))
                    Badge("revoked", LocalExtra.current.warn, MaterialTheme.colorScheme.surface)
                }
            }
            if (u.customer.isNotBlank() && !brief) Muted(u.customer, maxLines = 1)
            if (u.purpose.isNotBlank()) Muted(u.purpose, maxLines = 1)
            if (brief) return@Column
            val held = u.grants.filter { it.active() }
            if (held.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Chips { held.forEach { ModuleChip(it.module) } }
            }
            Spacer(Modifier.height(4.dp))
            Muted(
                listOfNotNull(
                    if (u.machines.isEmpty()) "no machines" else plural(u.machines.size, "machine"),
                    u.lastSeen()?.let { "seen ${it.ago()}" },
                ).joinToString("  ·  "),
            )
        }
    }
}

/** A product's users: search, sort, open one; grant to anyone. */
@Composable
fun UsersScreen(model: AppModel, s: Screen.Users) {
    var query by rememberSaveable(s.product) { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(UserOrder.Newest) }
    val loader = rememberLoader(s.product, query) {
        delay(if (query.isEmpty()) 0 else 300) // typing: wait for a pause
        val api = model.api
        coroutineScope {
            val p = async { api.product(s.product) }
            UsersPage(p.await(), api.users(s.product, query))
        }
    }
    loader.Run(model::failed)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    val search = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var granting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (s.search) runCatching { search.requestFocus() } }

    // While a new search loads, the last results stay.
    var last by remember { mutableStateOf<UsersPage?>(null) }
    loader.value?.let { last = it }
    val users = (loader.value ?: last)?.users.orEmpty().ordered(order)
    OnShortcut { k ->
        when (k.name) {
            "s", "/" -> { runCatching { search.requestFocus() }; true }
            "g" -> { if (loader.value != null) granting = true; true }
            "o" -> { order = UserOrder.entries[(order.ordinal + 1) % UserOrder.entries.size]; true }
            "r" -> { loader.reload(); true }
            Shortcuts.ENTER -> { users.getOrNull(sel.index)?.takeIf { sel.shown }?.let { model.open(Screen.User(s.product, it.email)) }; true }
            else -> listKeys(k, sel, users.size, state, scope, offset = 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Users", subtitle = (loader.value ?: last)?.product?.title ?: s.product, onBack = { model.back() }) {
            CardAction(withKey("Grant…", "G")) { granting = true }
        }
        Refreshable(loader) {
            Centered {
                LazyColumn(state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text(withKey("Search email or purpose", "S")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                                // Enter leaves the field, so J/K and Enter work on the list.
                                modifier = Modifier.fillMaxWidth().focusRequester(search).enterTo {
                                    focus.clearFocus()
                                    sel.index = 0
                                    sel.shown = users.isNotEmpty()
                                },
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(UserOrder.entries) { o ->
                                    FilterChip(selected = o == order, onClick = { order = o }, label = { Text(o.label) })
                                }
                            }
                        }
                    }
                    when (val st = loader.state) {
                        is Load.Loading -> if (last == null) item { Muted("Loading…", Modifier.padding(8.dp)) }
                        is Load.Failed -> item { Muted(describe(st.error), Modifier.padding(8.dp)) }
                        else -> Unit
                    }
                    if (loader.state !is Load.Failed && (loader.value ?: last) != null) {
                        run {
                            if (users.isEmpty()) item {
                                Muted(if (query.isEmpty()) "No users yet." else "No one matches “$query”.", Modifier.padding(8.dp))
                            }
                            items(users.size, key = { users[it].id.ifBlank { users[it].email } }) { i ->
                                val u = users[i]
                                GroupedRow(i == 0, i == users.lastIndex, sel.isSelected(i)) {
                                    UserLine(u) { model.open(Screen.User(s.product, u.email)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val p = (loader.value ?: last)?.product
    if (granting && p != null) {
        GrantDialog(p, null, onDismiss = { granting = false }) { email, modules, until, note ->
            granting = false
            model.change("Granted ${modules.joinToString(", ")} to $email", after = loader::reload) {
                grant(s.product, email, modules, until, note)
            }
        }
    }
}

data class UsersPage(val product: dev.licensebox.android.api.Product, val users: List<User>)
