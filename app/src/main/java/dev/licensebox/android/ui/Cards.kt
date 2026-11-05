package dev.licensebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The pieces a product's page is made of: cards with a title, a count and
// a way on to the whole list; figures; label-and-value lists; avatars.

/**
 * A card of the product's page: [title], with a [count] badge if any, and
 * at its foot a link to all of it ([more]), with the key that opens it.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    countHighlight: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    more: String? = null,
    moreKey: String? = null,
    onMore: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier, selected = selected) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                if (countHighlight && count > 0) Badge("$count")
                else Text("$count", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        content()
        if (more != null && onMore != null) {
            Spacer(Modifier.height(4.dp))
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onMore).padding(top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(more, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (moreKey != null && hasKeyboard()) Muted(moreKey, Modifier.padding(end = 8.dp))
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A small action in a card's title row. */
@Composable
fun CardAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Figures in a row of tiles: a big number over its label. */
@Composable
fun Tiles(vararg tiles: Pair<String, String>, onClick: ((Int) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEachIndexed { i, (value, label) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (onClick != null) Modifier.clickable { onClick(i) } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Label and value rows, divided: a card's settings. */
@Composable
fun Facts(vararg facts: Pair<String, String>) {
    Column {
        facts.forEachIndexed { i, (label, value) ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
            }
        }
    }
}

private val avatarTones = listOf(
    Color(0xFF1F45D8), Color(0xFF1B7F3B), Color(0xFF8A5A00), Color(0xFF7A3FB8),
    Color(0xFF0F7C8C), Color(0xFFB3261E), Color(0xFF4A5560),
)

/** A round initial for an address, in a colour of its own. */
@Composable
fun Avatar(email: String, size: Int = 36, dimmed: Boolean = false) {
    val tone = avatarTones[Math.floorMod(email.lowercase().hashCode(), avatarTones.size)]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (dimmed) tone.copy(alpha = 0.35f) else tone),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.42).sp,
        )
    }
}

/** A dot of colour, as a status. */
@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
