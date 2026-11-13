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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Join
import dev.licensebox.android.notify.RequestWatch

/**
 * Requests to create products, made on licensebox.dev/join: for operators.
 * Approving lets the address create products; either way they're emailed.
 */
@Composable
fun JoinsScreen(model: AppModel) {
    val loader = rememberLoader(Unit) { model.api.joins() }
    loader.Run(model::failed)
    val context = LocalContext.current
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    val all = loader.value.orEmpty()
    val waiting = all.filter { it.status == "pending" }
    val answered = all.filter { it.status != "pending" }.take(50)
    val list = waiting + answered
    var approving by remember { mutableStateOf<Join?>(null) }
    var declining by remember { mutableStateOf<Join?>(null) }
    LaunchedEffect(loader.value) { loader.value?.let { RequestWatch.seenJoins(context, it) } }

    fun itemOf(i: Int) = if (i < waiting.size) i + 1 else i + 2

    OnShortcut { k ->
        val j = list.getOrNull(sel.index)?.takeIf { sel.shown }
        when (k.name) {
            "a" -> { (j ?: waiting.firstOrNull())?.takeIf { it.status == "pending" }?.let { approving = it }; true }
            "d" -> { (j ?: waiting.firstOrNull())?.takeIf { it.status == "pending" }?.let { declining = it }; true }
            "r" -> { loader.reload(); true }
            else -> listKeys(k, sel, list.size, state, scope, itemOf = ::itemOf)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Requests to create products", subtitle = "From licensebox.dev/join", onBack = { model.back() })
        Refreshable(loader) {
            Loaded(loader) { _ ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                        item { SectionTitle(if (waiting.isEmpty()) "Nothing waiting" else "Waiting · ${waiting.size}", Modifier.padding(start = 4.dp)) }
                        items(waiting.size) { i ->
                            val j = waiting[i]
                            GroupedRow(i == 0, i == waiting.lastIndex, sel.isSelected(i)) {
                                JoinSlip(j, { approving = j }, { declining = j }, showKeys = i == 0)
                            }
                        }
                        item { if (answered.isNotEmpty()) SectionTitle("Answered", Modifier.padding(start = 4.dp)) }
                        items(answered.size) { i ->
                            GroupedRow(i == 0, i == answered.lastIndex, sel.isSelected(waiting.size + i)) {
                                JoinSlip(answered[i], {}, {})
                            }
                        }
                    }
                }
            }
        }
    }
    approving?.let { j ->
        NoteDialog(
            title = "Let ${j.name.ifBlank { j.email }} create products?", body = "${j.email} may then create products, and is emailed with your note.",
            confirm = "Approve", noteLabel = "Note to them (optional)", destructive = false, onDismiss = { approving = null },
        ) { note ->
            approving = null
            model.change("Approved: ${j.email} may create products", after = loader::reload) { approveJoin(j.email, note) }
        }
    }
    declining?.let { j ->
        NoteDialog(
            title = "Decline the request?", body = "${j.email} is emailed that it wasn't approved.",
            confirm = "Decline", noteLabel = "Note to them (optional)", onDismiss = { declining = null },
        ) { note ->
            declining = null
            model.change("Declined ${j.email}", after = loader::reload) { declineJoin(j.email, note) }
        }
    }
}

/** A request to create products: who, what they'd license, and the answer. */
@Composable
private fun JoinSlip(j: Join, onApprove: () -> Unit, onDecline: () -> Unit, showKeys: Boolean = true) {
    val pending = j.status == "pending"
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(j.email, size = 28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(j.name.ifBlank { j.email }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (j.name.isNotBlank()) Muted(j.email, maxLines = 1)
            }
            Muted(j.created?.ago() ?: "")
        }
        Column(Modifier.padding(start = 38.dp, top = 6.dp)) {
            if (j.about.isNotBlank()) Text("“${j.about}”", style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (j.website.startsWith("https://") || j.website.startsWith("http://")) {
                Text(
                    j.website, style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.Underline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clickable { runCatching { uri.openUri(j.website) } },
                )
            }
            if (pending) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pad = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                    Button(onClick = onApprove, contentPadding = pad, modifier = Modifier.height(36.dp)) { Text(if (showKeys) withKey("Approve", "A") else "Approve") }
                    OutlinedButton(onClick = onDecline, contentPadding = pad, modifier = Modifier.height(36.dp)) { Text(if (showKeys) withKey("Decline", "D") else "Decline") }
                }
            } else {
                val x = LocalExtra.current
                val approved = j.status == "approved"
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (approved) x.good else x.warn)
                    Spacer(Modifier.width(6.dp))
                    Text(if (approved) "Approved" else "Declined", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                val by = listOfNotNull(j.answeredBy.ifBlank { null }, j.answered?.ago()).joinToString(", ")
                if (by.isNotBlank()) Muted("by $by")
                if (j.answer.isNotBlank()) Muted("“${j.answer}”")
            }
        }
    }
}
