package dev.licensebox.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Product
import dev.licensebox.android.api.Stats
import dev.licensebox.android.data.SignedIn
import dev.licensebox.android.notify.RequestWatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A product, with what the home page shows of it. */
data class ProductCard(val product: Product, val waiting: Int?, val stats: Stats?)

/** The home page: the products, and for operators the requests to join waiting. */
data class ProductsPage(val cards: List<ProductCard>, val joins: Int?)

@Composable
fun ProductsScreen(model: AppModel, session: SignedIn) {
    val loader = rememberLoader(session) {
        val api = model.api
        coroutineScope {
            // For operators, the requests to create products waiting.
            val joins = async { runCatching { if (api.me().operator) api.joins("pending").size else null }.getOrNull() }
            val cards = api.products().sortedBy { it.title.lowercase() }.map { p ->
                async {
                    // A product's figures failing should not hide the product.
                    val waiting = async { runCatching { api.requests(p.id, "pending").size }.getOrNull() }
                    val stats = async { runCatching { api.stats(p.id) }.getOrNull() }
                    ProductCard(p, waiting.await(), stats.await())
                }
            }.awaitAll()
            ProductsPage(cards, joins.await())
        }
    }
    loader.Run(model::failed)
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()
    val cards = loader.value?.cards.orEmpty()

    OnShortcut { k ->
        when (k.name) {
            "r" -> { loader.reload(); true }
            "0" -> { loader.value?.joins?.let { model.open(Screen.Joins) }; true }
            Shortcuts.ENTER, "o" -> {
                cards.getOrNull(sel.index)?.takeIf { sel.shown }?.let { model.open(Screen.Product(it.product.id)) }
                true
            }
            in "123456789".map(Char::toString) -> {
                cards.getOrNull(k.name.toInt() - 1)?.let { model.open(Screen.Product(it.product.id)) }
                true
            }
            else -> listKeys(k, sel, cards.size, state, scope)
        }
    }

    NotificationAsk(model)

    Column(Modifier.fillMaxSize()) {
        Header(title = "Products", subtitle = session.email + if (session.server != dev.licensebox.android.api.DEFAULT_SERVER) " · " + session.server else "") {
            Menu(model)
        }
        PullToRefreshBox(isRefreshing = loader.refreshing && loader.value != null, onRefresh = loader::reload, modifier = Modifier.fillMaxSize()) {
            Loaded(loader) { page ->
                val list = page.cards
                Centered {
                    if (list.isEmpty() && page.joins == null) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No products yet", style = MaterialTheme.typography.titleMedium)
                            Muted("You aren't an admin of any product. Create one at licensebox.dev/admin or with `licensebox create`, or ask a product's admin to add you.")
                        }
                    }
                    LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        page.joins?.let { n -> item(key = "joins") { JoinsRow(n, hasKeyboard()) { model.open(Screen.Joins) } } }
                        itemsIndexed(list, key = { _, c -> c.product.id }) { i, c ->
                            ProductRow(c, i, sel.isSelected(i), hasKeyboard()) { model.open(Screen.Product(c.product.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(c: ProductCard, index: Int, selected: Boolean, keys: Boolean, onOpen: () -> Unit) {
    val p = c.product
    Card(selected = selected, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(p.id + if (keys && index < 9) "  ·  key ${index + 1}" else "")
            }
            if ((c.waiting ?: 0) > 0) Badge("${c.waiting} waiting")
        }
        p.deleteAt?.let {
            Spacer(Modifier.padding(top = 6.dp))
            Text("Deleted ${it.until()}: apps are refused", color = LocalExtra.current.warn, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Small(c.stats?.users?.let { plural(it, "user") } ?: "–")
            Small(c.stats?.active?.let { "$it active " + if (it == 1) "machine" else "machines" } ?: "–")
            Small(plural(p.modules.size, "module"))
        }
    }
}

/** For operators: the requests to create products, from licensebox.dev/join. */
@Composable
private fun JoinsRow(waiting: Int, keys: Boolean, onOpen: () -> Unit) {
    Card(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Requests to create products", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted((if (waiting == 0) "None waiting" else "From licensebox.dev/join") + if (keys) "  ·  key 0" else "")
            }
            if (waiting > 0) Badge("$waiting waiting")
        }
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Menu(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var notify by remember { mutableStateOf(RequestWatch.enabled(context)) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("⋯", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (notify) "Notify me of requests: on" else "Notify me of requests: off") },
                onClick = {
                    notify = !notify
                    RequestWatch.setEnabled(context, notify)
                    open = false
                },
            )
            DropdownMenuItem(text = { Text("Check for updates") }, onClick = { open = false; model.checkForUpdate(tell = true) })
            DropdownMenuItem(text = { Text("Keyboard shortcuts") }, onClick = { model.help = true; open = false })
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; model.signOut() })
        }
    }
}

/** Asks once, on Android 13 and later, to let request notifications show. */
@Composable
private fun NotificationAsk(model: AppModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.message = "Request notifications are off. Turn them on in Android's settings."
    }
    LaunchedEffect(Unit) {
        if (RequestWatch.enabled(context) && RequestWatch.shouldAsk(context)) {
            RequestWatch.asked(context)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
