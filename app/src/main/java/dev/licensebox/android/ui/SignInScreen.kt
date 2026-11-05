package dev.licensebox.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.licensebox.android.R
import dev.licensebox.android.api.AdminApi
import dev.licensebox.android.api.DEFAULT_SERVER
import dev.licensebox.android.data.SignedIn
import kotlinx.coroutines.launch

/** On many phone keyboards the digits share letter keys; read those as digits in a code. */
private val keyDigits = mapOf('w' to '1', 'e' to '2', 'r' to '3', 's' to '4', 'd' to '5', 'f' to '6', 'z' to '7', 'x' to '8', 'c' to '9')

internal fun codeDigits(typed: String): String =
    typed.lowercase().mapNotNull { c -> if (c.isDigit()) c else keyDigits[c] }.joinToString("").take(6)

/**
 * Signing in as `licensebox login` does: an email address, then the code
 * the server mails to it.
 */
@Composable
fun SignInScreen(model: AppModel) {
    var server by rememberSaveable { mutableStateOf(model.store.server) }
    var email by rememberSaveable { mutableStateOf(model.store.lastEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var editServer by rememberSaveable { mutableStateOf(server != DEFAULT_SERVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val emailFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }

    fun send() {
        if (busy || !email.contains('@')) return
        busy = true
        error = null
        scope.launch {
            try {
                model.store.server = server.trim().trimEnd('/')
                AdminApi(server.trim(), null).startLogin(email.trim())
                sent = true
                code = ""
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun finish() {
        if (busy || code.length != 6) return
        busy = true
        error = null
        scope.launch {
            try {
                val s = AdminApi(server.trim(), null).finishLogin(email.trim(), code)
                model.signedIn(SignedIn(server.trim().trimEnd('/'), s.email.ifBlank { email.trim() }, s.token, s.expires))
            } catch (e: Exception) {
                error = describe(e)
                code = ""
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(sent) {
        runCatching { if (sent) codeFocus.requestFocus() else emailFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.column(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painterResource(R.drawable.mark), contentDescription = null, modifier = Modifier.size(36.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                )
                Text("licensebox", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                if (sent) "We sent a code to $email. It's good for a few minutes."
                else "Sign in to manage your products: answer requests, grant modules, see who uses what.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!sent) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(emailFocus).enterTo(::send),
                )
                if (editServer) {
                    OutlinedTextField(
                        value = server, onValueChange = { server = it.trim() }, label = { Text("Server") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth().enterTo(::send),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::send, enabled = !busy && email.contains('@')) { Text(if (busy) "Sending…" else "Send me a code") }
                    Spacer(Modifier.weight(1f))
                    if (!editServer) TextButton(onClick = { editServer = true }) { Text("Another server") }
                }
            } else {
                OutlinedTextField(
                    value = code, onValueChange = { code = codeDigits(it); if (code.length == 6) finish() },
                    label = { Text("6-digit code") }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlexMono),
                    // A number pad on touch phones; a hardware keyboard's letter keys
                    // still arrive as letters, read as digits above.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).enterTo(::finish),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::finish, enabled = !busy && code.length == 6) { Text(if (busy) "Signing in…" else "Sign in") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { sent = false; error = null }) { Text("Change email") }
                }
                TextButton(onClick = ::send, enabled = !busy) { Text("Send another code") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (server != DEFAULT_SERVER) Muted("Server: $server")
        }
    }
}
