package dev.licensebox.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn

private val everywhere = listOf(
    "J / K  or  N / P" to "next, previous",
    "Space, Shift+Space" to "page down, up",
    "T, B" to "top, bottom",
    "Enter" to "open what's selected",
    "H  or  ?" to "these shortcuts",
)

private fun forScreen(s: Screen): Pair<String, List<Pair<String, String>>> = when (s) {
    Screen.Products -> "Products" to listOf("1 – 9" to "open that product", "R" to "reload")
    is Screen.Product -> "A product" to listOf(
        "R  U  E" to "all requests, users, activity",
        "A, D" to "approve, decline the first request waiting",
        "S  or  /" to "search users",
        "G" to "grant modules to anyone",
        "J / K, Enter" to "move between cards, open one",
        "1 – 9 in a dialog" to "tick that module",
    )
    is Screen.Requests -> "Requests" to listOf(
        "A, D" to "approve, decline the selected request (or the first)",
        "Enter" to "open the user",
        "R" to "reload",
    )
    is Screen.Users -> "Users" to listOf(
        "S  or  /" to "search; Enter ends it",
        "O" to "change the order",
        "G" to "grant modules to anyone",
        "R" to "reload",
    )
    is Screen.Activity -> "Activity" to listOf(
        "F" to "next filter",
        "R" to "reload",
    )
    is Screen.User -> "A user" to listOf(
        "G" to "grant modules",
        "X" to "revoke, or restore",
        "Enter on a grant" to "take it back",
        "Enter on a machine" to "revoke or restore it",
        "R" to "reload",
    )
}

/** The keyboard shortcuts: this screen's, then those that work everywhere. */
@Composable
fun HelpSheet(screen: Screen, signedIn: Boolean, onDismiss: () -> Unit) {
    val (title, keys) = if (signedIn) forScreen(screen) else "Sign-in" to listOf("Enter" to "send, sign in")
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(title, Modifier.padding(top = 0.dp))
                keys.forEach { KeyLine(it) }
                SectionTitle("Everywhere")
                everywhere.forEach { KeyLine(it) }
                Muted("Where digits and ? are on Alt, the digit keys type digits without it in a sign-in code.", Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun KeyLine(k: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k.first, fontFamily = PlexMono, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(150.dp))
        Text(k.second, style = MaterialTheme.typography.bodySmall)
    }
}
