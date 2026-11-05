package dev.licensebox.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.ApiError
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Loading.

/** Something loaded from the server: not yet, loaded, or failed. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** Loads with [fetch]; [reload] loads again, keeping what was shown. */
@Stable
class Loader<T>(private val fetch: suspend () -> T) {
    var state by mutableStateOf<Load<T>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var tick by mutableIntStateOf(0)

    val value get() = (state as? Load.Done<T>)?.value

    fun reload() {
        tick++
    }

    @Composable
    fun Run(onError: (Throwable) -> Unit) {
        // Keyed on the loader too: a new one (a new search) loads afresh.
        LaunchedEffect(this, tick) {
            refreshing = true
            state = try {
                Load.Done(fetch())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onError(e)
                if (state is Load.Done) state else Load.Failed(e)
            }
            refreshing = false
        }
    }
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, fetch: suspend () -> T): Loader<T> = remember(*keys) { Loader(fetch) }

fun describe(e: Throwable): String = when (e) {
    is ApiError -> if (e.signedOut) "Your session has ended. Sign in again." else e.detail.ifBlank { e.code }
    is java.net.UnknownHostException -> "Can't reach the server. Check the connection."
    is java.net.SocketTimeoutException -> "The server took too long to answer."
    is java.net.ConnectException -> "Can't connect to the server."
    is java.net.UnknownServiceException -> "That server uses plain HTTP. licensebox talks to servers over HTTPS only."
    else -> e.message ?: e.javaClass.simpleName
}

/** Shows [content] once loaded, a spinner before, the error if it failed. */
@Composable
fun <T> Loaded(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        is Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        is Load.Failed -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describe(s.error), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = loader::reload) { Text("Try again") }
            }
        }
        is Load.Done -> content(s.value)
    }
}

// Layout.

/** Content no wider than a comfortable column, centred on wide screens. */
fun Modifier.column(): Modifier = this.fillMaxWidth().widthIn(max = 720.dp)

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.column()) { content() }
    }
}

/**
 * A compact header: 48dp, not Material's 64, as a square phone screen
 * is short.
 */
@Composable
fun Header(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                // Not focusable: keyboard users go back with Esc or the Back
                // key, and a focused button would sit highlighted.
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.focusProperties { canFocus = false }) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** A white card, as the console's panels. */
@Composable
fun Card(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** A row of label and value, as in the product's settings. */
@Composable
fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

// Chips.

/** A module as a chip: plain, or marked free, trial or hidden. */
@Composable
fun ModuleChip(name: String, kind: String? = null, muted: Boolean = false) {
    val x = LocalExtra.current
    val accent = when (kind) {
        "free" -> x.free
        "trial" -> x.trial
        "hidden" -> x.hidden
        else -> null
    }
    val fg = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Text(" ", style = MaterialTheme.typography.labelMedium)
        }
        Text(name, style = MaterialTheme.typography.labelMedium, color = fg, fontFamily = PlexMono)
        if (kind != null) Text(" $kind", style = MaterialTheme.typography.labelSmall, color = accent ?: fg)
    }
}

@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** A small badge: a count or a word, in a colour. */
@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.primary, onColor: Color = MaterialTheme.colorScheme.onPrimary) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = onColor,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Muted(label)
    }
}

// Times.

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

fun Instant.date(): String = dateFmt.format(atZone(ZoneId.systemDefault()))
fun Instant.dateTime(): String = dateTimeFmt.format(atZone(ZoneId.systemDefault()))

/** "just now", "5 min ago", "3 h ago", "2 days ago", then the date. */
fun Instant.ago(now: Instant = Instant.now()): String {
    val d = Duration.between(this, now)
    return when {
        d.isNegative -> until(now)
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        d.toDays() < 14 -> "${d.toDays()} day${if (d.toDays() == 1L) "" else "s"} ago"
        else -> date()
    }
}

/** "in 3 days", "in 5 h", or the date when far off. */
fun Instant.until(now: Instant = Instant.now()): String {
    val d = Duration.between(now, this)
    return when {
        d.isNegative -> ago(now)
        d.toHours() < 1 -> "in ${d.toMinutes()} min"
        d.toHours() < 48 -> "in ${d.toHours()} h"
        d.toDays() < 60 -> "in ${d.toDays()} days"
        else -> "on ${date()}"
    }
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
