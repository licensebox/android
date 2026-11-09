package dev.licensebox.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import dev.licensebox.android.api.Product
import java.time.Duration
import java.time.Instant

/** How long a grant lasts: for good, or a span from now. */
enum class Span(val label: String, val days: Long?) {
    Forever("For good", null),
    Month("30 days", 30),
    Quarter("90 days", 90),
    Year("1 year", 365);

    fun until(): Instant? = days?.let { Instant.now().plus(Duration.ofDays(it)) }
}

/**
 * A dialog that takes keys from a hardware keyboard: Enter confirms (from
 * anywhere but a multi-line field), Esc cancels, [onKey] has the rest.
 * Its body is focusable and takes focus when it opens, so keys reach it
 * without a field being focused, and no soft keyboard pops up unasked.
 */
@Composable
fun KeyDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    onKey: (Char) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Android's default dialog width leaves a square 480dp screen half
        // empty; use most of a small screen, and no more than 560dp.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier
                    // A square screen is short: keep the buttons on it.
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { if (confirmEnabled) onConfirm(); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> e.utf16CodePoint.takeIf { it > 0 }?.let { onKey(it.toChar().lowercaseChar()) } ?: false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirm, color = if (destructive && confirmEnabled) LocalExtra.current.warn else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NoteField(note: String, onChange: (String) -> Unit, label: String = "Note (optional)", onEnter: () -> Unit) {
    OutlinedTextField(
        value = note, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().enterTo(onEnter),
    )
}

@Composable
fun SpanPicker(span: Span, onChange: (Span) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Span.entries.forEach { s ->
            FilterChip(selected = s == span, onClick = { onChange(s) }, label = { Text(s.label) })
        }
    }
}

/** Modules to tick, numbered for the keyboard: 1 ticks the first. */
@Composable
fun ModulePicks(modules: List<String>, picked: MutableList<String>, product: Product?) {
    val keys = hasKeyboard()
    modules.forEachIndexed { i, m ->
        Row(
            Modifier.fillMaxWidth().clickable { if (m in picked) picked.remove(m) else picked.add(m) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = m in picked, onCheckedChange = { if (it) picked.add(m) else picked.remove(m) })
            Text(m, fontFamily = PlexMono, modifier = Modifier.weight(1f))
            product?.kindOf(m)?.let { Muted(it, Modifier.padding(end = 8.dp)) }
            if (keys && i < 9) Muted("${i + 1}")
        }
    }
}

private fun toggleByDigit(c: Char, modules: List<String>, picked: MutableList<String>): Boolean {
    val i = c.digitToIntOrNull()?.minus(1) ?: return false
    val m = modules.getOrNull(i) ?: return false
    if (m in picked) picked.remove(m) else picked.add(m)
    return true
}

/** Approving a request: which of its modules, for how long, a note to them. */
@Composable
fun ApproveDialog(
    email: String,
    modules: List<String>,
    note: String,
    product: Product?,
    onDismiss: () -> Unit,
    onApprove: (modules: List<String>, until: Instant?, note: String) -> Unit,
) {
    val picked = remember { mutableStateListOf(*modules.toTypedArray()) }
    var span by remember { mutableStateOf(Span.Forever) }
    var answer by remember { mutableStateOf("") }
    val ok = picked.isNotEmpty()
    val approve = { if (ok) onApprove(modules.filter { it in picked }, span.until(), answer.trim()) }
    KeyDialog(
        title = "Approve", confirm = "Approve", onConfirm = approve, onDismiss = onDismiss, confirmEnabled = ok,
        onKey = { toggleByDigit(it, modules, picked) },
    ) {
        Text(email, style = MaterialTheme.typography.titleSmall)
        if (note.isNotBlank()) Text("“$note”", style = MaterialTheme.typography.bodyMedium)
        Column { ModulePicks(modules, picked, product) }
        SpanPicker(span) { span = it }
        NoteField(answer, { answer = it }, "Note to them (optional)", approve)
    }
}

/** A yes-or-no with a note: declining a request, revoking a user. */
@Composable
fun NoteDialog(
    title: String,
    body: String?,
    confirm: String,
    noteLabel: String,
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val go = { onConfirm(note.trim()) }
    KeyDialog(title, confirm, go, onDismiss, destructive = destructive) {
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        NoteField(note, { note = it }, noteLabel, go)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    KeyDialog(title, confirm, onConfirm, onDismiss, destructive = destructive) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Granting modules: to the user given, or to any address (it need not
 * have signed in yet).
 */
@Composable
fun GrantDialog(
    product: Product,
    email: String?,
    held: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onGrant: (email: String, modules: List<String>, until: Instant?, note: String) -> Unit,
) {
    var to by remember { mutableStateOf(email.orEmpty()) }
    // Free modules need no grant; the rest, less what's held for good.
    val offer = product.modules.filter { it !in product.free && it !in held }
    val picked = remember { mutableStateListOf<String>() }
    var span by remember { mutableStateOf(Span.Forever) }
    var note by remember { mutableStateOf("") }
    val ok = picked.isNotEmpty() && to.contains('@')
    val grant = { if (ok) onGrant(to.trim(), offer.filter { it in picked }, span.until(), note.trim()) }
    KeyDialog(
        title = "Grant modules", confirm = "Grant",
        onConfirm = grant, onDismiss = onDismiss, confirmEnabled = ok,
        onKey = { toggleByDigit(it, offer, picked) },
    ) {
        if (email != null) Text("to $email", style = MaterialTheme.typography.titleSmall)
        if (email == null) {
            OutlinedTextField(
                value = to, onValueChange = { to = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().enterTo(grant),
            )
        }
        if (offer.isEmpty()) Muted("Nothing to grant: every module is free or already held.")
        Column { ModulePicks(offer, picked, product) }
        SpanPicker(span) { span = it }
        NoteField(note, { note = it }, onEnter = grant)
    }
}

/** A user's machine seats: a number, 0 for no limit, empty for the product's. */
@Composable
fun SeatsDialog(email: String, current: Int?, productLimit: Int, onDismiss: () -> Unit, onSet: (Int?) -> Unit) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val limit = text.toIntOrNull()
    val ok = text.isEmpty() || limit != null && limit in 0..100
    val set = { if (ok) onSet(limit) }
    val default = if (productLimit == 0) "no limit" else "$productLimit"
    KeyDialog(title = "Machine seats", confirm = "Save", onConfirm = set, onDismiss = onDismiss, confirmEnabled = ok) {
        Text("for $email", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(3) }, label = { Text("Seats") }, singleLine = true,
            placeholder = { Text("Product default ($default)") }, isError = !ok,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().enterTo(set),
        )
        Muted("0 to 100. 0 is no limit; empty uses the product's default ($default).")
    }
}

@Composable
fun DialogLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
