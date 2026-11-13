package dev.licensebox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.licensebox.android.api.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How an event reads: a few words, a tone, and which filter it's under. */
data class EventLook(val label: String, val tone: Tone, val group: EventGroup)

enum class Tone { Good, Warn, Ask, Plain }

enum class EventGroup(val label: String) { All("All"), Requests("Requests"), Access("Access"), SignIns("Sign-ins"), Admin("Admin") }

/** The server's event kinds, in words. */
fun lookOf(kind: String): EventLook = when (kind) {
    "request" -> EventLook("Asked for modules", Tone.Ask, EventGroup.Requests)
    "request-approved" -> EventLook("Request approved", Tone.Good, EventGroup.Requests)
    "request-declined" -> EventLook("Request declined", Tone.Warn, EventGroup.Requests)
    "grant" -> EventLook("Granted", Tone.Good, EventGroup.Access)
    "ungrant" -> EventLook("Took back", Tone.Warn, EventGroup.Access)
    "trial" -> EventLook("Trial started", Tone.Plain, EventGroup.Access)
    "revoke" -> EventLook("Revoked", Tone.Warn, EventGroup.Access)
    "restore" -> EventLook("Restored", Tone.Good, EventGroup.Access)
    "revoke-machine" -> EventLook("Machine revoked", Tone.Warn, EventGroup.Access)
    "restore-machine" -> EventLook("Machine restored", Tone.Good, EventGroup.Access)
    "sign-in" -> EventLook("Signed in", Tone.Plain, EventGroup.SignIns)
    "accept" -> EventLook("Accepted the terms", Tone.Plain, EventGroup.SignIns)
    "refused" -> EventLook("Refused", Tone.Warn, EventGroup.SignIns)
    "passkey-added" -> EventLook("Added a passkey", Tone.Plain, EventGroup.SignIns)
    "enrol" -> EventLook("Server enrolled", Tone.Plain, EventGroup.SignIns)
    "checkout" -> EventLook("Copy checked out", Tone.Plain, EventGroup.Access)
    "checkin" -> EventLook("Copy checked in", Tone.Plain, EventGroup.Access)
    "checkout-freed" -> EventLook("Copy freed", Tone.Warn, EventGroup.Access)
    "seats" -> EventLook("Seats changed", Tone.Plain, EventGroup.Access)
    "customer-member" -> EventLook("Customer changed", Tone.Plain, EventGroup.Admin)
    "customer-added" -> EventLook("Customer added", Tone.Plain, EventGroup.Admin)
    "customer-changed" -> EventLook("Customer changed", Tone.Plain, EventGroup.Admin)
    "service-added" -> EventLook("Service account added", Tone.Plain, EventGroup.Admin)
    "service-token" -> EventLook("Enrolment token issued", Tone.Plain, EventGroup.Admin)
    "passkey-removed" -> EventLook("Removed a passkey", Tone.Plain, EventGroup.SignIns)
    "login", "admin-sign-in" -> EventLook("Admin signed in", Tone.Plain, EventGroup.Admin)
    "admin-added" -> EventLook("Admin added", Tone.Plain, EventGroup.Admin)
    "admin-removed" -> EventLook("Admin removed", Tone.Plain, EventGroup.Admin)
    "key-added" -> EventLook("Signing key added", Tone.Plain, EventGroup.Admin)
    "key-retired" -> EventLook("Signing key retired", Tone.Plain, EventGroup.Admin)
    "product-created" -> EventLook("Product created", Tone.Plain, EventGroup.Admin)
    "product-changed" -> EventLook("Settings changed", Tone.Plain, EventGroup.Admin)
    "product-deletion-scheduled" -> EventLook("Deletion scheduled", Tone.Warn, EventGroup.Admin)
    "product-deletion-cancelled" -> EventLook("Deletion cancelled", Tone.Good, EventGroup.Admin)
    "product-deleted" -> EventLook("Product deleted", Tone.Warn, EventGroup.Admin)
    else -> EventLook(kind.replace('-', ' ').replaceFirstChar { it.uppercase() }, Tone.Plain, EventGroup.Admin)
}

@Composable
fun toneColor(t: Tone): Color {
    val x = LocalExtra.current
    return when (t) {
        Tone.Good -> x.good
        Tone.Warn -> x.warn
        Tone.Ask -> MaterialTheme.colorScheme.primary
        Tone.Plain -> MaterialTheme.colorScheme.outline
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** "Today", "Yesterday", or the day: the heading over its events. */
fun dayOf(t: Instant?, today: LocalDate = LocalDate.now()): String {
    if (t == null) return "Undated"
    val d = t.atZone(ZoneId.systemDefault()).toLocalDate()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayFmt.format(d) + if (d.year != today.year) " ${d.year}" else ""
    }
}

/** One event: its tone, what happened, to whom, by whom, and when. */
@Composable
fun EventLine(e: Event, showTime: Boolean = true, timeAgo: Boolean = false) {
    val look = lookOf(e.kind)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.padding(top = 6.dp)) { Dot(toneColor(look.tone)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(look.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (showTime) {
                    Muted(e.time?.let { if (timeAgo) it.ago() else timeFmt.format(it.atZone(ZoneId.systemDefault())) } ?: "")
                }
            }
            if (e.detail.isNotBlank()) {
                Text(e.detail.replace(Regex(",(?=\\S)"), ", "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (e.by.isNotBlank()) Muted("by ${e.by}")
        }
    }
}

/** The shape of a row in a grouped list: rounded at the group's ends. */
fun groupShape(first: Boolean, last: Boolean): Shape {
    val r = 14.dp
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
}

/** A row of a grouped list: white, its group's corners rounded, outlined if selected. */
@Composable
fun GroupedRow(first: Boolean, last: Boolean, selected: Boolean, content: @Composable () -> Unit) {
    val shape = groupShape(first, last)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .padding(horizontal = 14.dp),
    ) {
        content()
        if (!last) Divider()
    }
}

private sealed interface ActivityItem {
    data class Day(val label: String) : ActivityItem
    data class Line(val event: Event, val index: Int, val first: Boolean, val last: Boolean) : ActivityItem
}

/** A product's latest 200 events, by day, with filters. */
@Composable
fun ActivityScreen(model: AppModel, s: Screen.Activity) {
    val loader = rememberLoader(s.product) { model.api.events(s.product, 200) }
    loader.Run(model::failed)
    var group by rememberSaveable { mutableStateOf(EventGroup.All) }
    val state = rememberLazyListState()
    val sel = rememberSelection()
    val scope = rememberCoroutineScope()

    // Newest first, whatever order they came in, so each day is one group.
    val events = loader.value.orEmpty().sortedByDescending { it.time }.filter { group == EventGroup.All || lookOf(it.kind).group == group }
    val items = buildList {
        var day: String? = null
        val today = LocalDate.now()
        events.forEachIndexed { i, e ->
            val d = dayOf(e.time, today)
            val first = d != day
            if (first) add(ActivityItem.Day(d))
            day = d
            val last = i == events.lastIndex || dayOf(events[i + 1].time, today) != d
            add(ActivityItem.Line(e, i, first, last))
        }
    }
    // Item index of each event, for keeping the selection in view.
    val itemOf = items.withIndex().filter { it.value is ActivityItem.Line }.map { it.index }

    OnShortcut { k ->
        when (k.name) {
            "r" -> { loader.reload(); true }
            "f" -> { group = EventGroup.entries[(group.ordinal + 1) % EventGroup.entries.size]; sel.shown = false; true }
            else -> listKeys(k, sel, events.size, state, scope, itemOf = { itemOf.getOrElse(it) { 0 } })
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title = "Activity", subtitle = s.product, onBack = { model.back() })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(EventGroup.entries) { g ->
                FilterChip(selected = g == group, onClick = { group = g; sel.shown = false }, label = { Text(g.label) })
            }
        }
        Refreshable(loader) {
            Loaded(loader) { _ ->
                Centered {
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                        if (events.isEmpty()) item { Muted(if (group == EventGroup.All) "No activity yet." else "Nothing of this kind lately.", Modifier.padding(8.dp)) }
                        items(items.size) { i ->
                            when (val it = items[i]) {
                                is ActivityItem.Day -> SectionTitle(it.label, Modifier.padding(start = 4.dp))
                                is ActivityItem.Line -> GroupedRow(it.first, it.last, sel.isSelected(it.index)) { EventLine(it.event) }
                            }
                        }
                    }
                }
            }
        }
    }
}
