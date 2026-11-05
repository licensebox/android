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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.Request
import dev.licensebox.android.notify.RequestWatch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Answering requests, from the product's page or the list of them: which
 * request is being approved or declined, and the dialogs that do it.
 */
@Stable
class Answers(private val model: AppModel, private val product: String, private val after: () -> Unit) {
    var approving by mutableStateOf<Request?>(null)
    var declining by mutableStateOf<Request?>(null)

    @Composable
    fun Dialogs(p: Product?) {
        approving?.let { r ->
            ApproveDialog(r.email, r.modules, r.note, p, onDismiss = { approving = null }) { modules, until, note ->
                approving = null
                model.change("Approved: ${r.email} has ${modules.joinToString(", ")}", after = after) {
                    approve(product, r.id, modules, until, note)
                }
            }
        }
        declining?.let { r ->
            NoteDialog(
                title = "Decline the request?", body = "${r.email} asked for ${r.modules.joinToString(", ")}. They'll be told no.",
                confirm = "Decline", noteLabel = "Note to them (optional)", onDismiss = { declining = null },
            ) { note ->
                declining = null
                model.change("Declined ${r.email}", after = after) { decline(product, r.id, note) }
            }
        }
    }
}

@Composable
fun rememberAnswers(model: AppModel, product: String, after: () -> Unit) = remember(product) { Answers(model, product, after) }

/**
 * A request: who, when, the modules asked for and why; waiting ones with
 * their buttons, answered ones with how they were answered.
 */
@Composable
fun RequestSlip(r: Request, p: Product?, onOpen: () -> Unit, onApprove: () -> Unit, onDecline: () -> Unit, showKeys: Boolean = true) {
    val pending = r.status == "pending"
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.email, size = 28)
            Spacer(Modifier.width(10.dp))
            Text(r.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Muted(r.created?.ago() ?: "")
        }
        Column(Modifier.padding(start = 38.dp, top = 6.dp)) {
            Chips { r.modules.forEach { ModuleChip(it, p?.kindOf(it), muted = !pending && it !in r.granted) } }
            if (r.note.isNotBlank()) {
                Text("“${r.note}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (pending) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pad = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                    Button(onClick = onApprove, contentPadding = pad, modifier = Modifier.height(36.dp)) { Text(if (showKeys) withKey("Approve", "A") else "Approve") }
                    OutlinedButton(onClick = onDecline, contentPadding = pad, modifier = Modifier.height(36.dp)) { Text(if (showKeys) withKey("Decline", "D") else "Decline") }
                }
            } else {
                val x = LocalExtra.current
                val approved = r.status == "approved"
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (approved) x.good else x.warn)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (approved) "Approved" + (if (r.granted.isNotEmpty() && r.granted.toSet() != r.modules.toSet()) ": " + r.granted.joinToString(", ") else "") +
                            (r.until?.let { ", until ${it.date()}" } ?: "")
                        else "Declined",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    )
                }
                val by = listOfNotNull(r.answeredBy.ifBlank { null }, r.answered?.ago()).joinToString(", ")
                if (by.isNotBlank()) Muted("by $by")
                if (r.answer.isNotBlank()) Muted("“${r.answer}”")
            }
        }
    }
}

private data class RequestsPage(val product: Product, val requests: List<Request>)

/** All of a product's requests: waiting, then the latest answered. */
@Composable
fun RequestsScreen(model: AppModel, s: Screen.Requests) {
    val loader = rememberLoader(s.product) {
        val api = model.api
        coroutineScope {
            val p = async { api.product(s.product) }
            RequestsPage(p.await(), api.requests(s.product))
        }
    }
    loader.Run(model::failed)
    val context = LocalContext.current
    val answers = rememberAnswers(model, s.product, loader::reload)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    val all = loader.value?.requests.orEmpty()
    val waiting = all.filter { it.status == "pending" }
    val answered = all.filter { it.status != "pending" }.take(50)
    val list = waiting + answered
    LaunchedEffect(loader.value) { loader.value?.let { RequestWatch.seen(context, s.product, it.requests) } }

    // Items: a heading, the waiting, a heading, the answered.
    fun itemOf(i: Int) = if (i < waiting.size) i + 1 else i + 2

    OnShortcut { k ->
        val r = list.getOrNull(sel.index)?.takeIf { sel.shown }
        when (k.name) {
            "a" -> { (r ?: waiting.firstOrNull())?.takeIf { it.status == "pending" }?.let { answers.approving = it }; true }
            "d" -> { (r ?: waiting.firstOrNull())?.takeIf { it.status == "pending" }?.let { answers.declining = it }; true }
            "r" -> { loader.reload(); true }
            Shortcuts.ENTER, "o" -> { r?.let { model.open(Screen.User(s.product, it.email)) }; true }
            else -> listKeys(k, sel, list.size, state, scope, itemOf = ::itemOf)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Requests", subtitle = loader.value?.product?.title ?: s.product, onBack = { model.back() })
        Refreshable(loader) {
            Loaded(loader) { page ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                        item { SectionTitle(if (waiting.isEmpty()) "Nothing waiting" else "Waiting · ${waiting.size}", Modifier.padding(start = 4.dp)) }
                        items(waiting.size) { i ->
                            val r = waiting[i]
                            GroupedRow(i == 0, i == waiting.lastIndex, sel.isSelected(i)) {
                                RequestSlip(r, page.product, { model.open(Screen.User(s.product, r.email)) }, { answers.approving = r }, { answers.declining = r }, showKeys = i == 0)
                            }
                        }
                        item { if (answered.isNotEmpty()) SectionTitle("Answered", Modifier.padding(start = 4.dp)) }
                        items(answered.size) { i ->
                            val r = answered[i]
                            GroupedRow(i == 0, i == answered.lastIndex, sel.isSelected(waiting.size + i)) {
                                RequestSlip(r, page.product, { model.open(Screen.User(s.product, r.email)) }, {}, {})
                            }
                        }
                    }
                }
            }
        }
    }
    answers.Dialogs(loader.value?.product)
}
