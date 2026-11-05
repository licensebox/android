package dev.licensebox.android.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.input.key.Key as ComposeKey
import kotlinx.coroutines.launch

/**
 * Keyboard shortcuts, for phones with a keyboard, which may have letters
 * but no arrow keys. The activity offers every key
 * here before Compose sees it, unless a text field is being typed in
 * ([typing]), so typing never fires a shortcut, and Compose never moves
 * focus onto a button that Enter would then press.
 *
 * The keys follow BlackBerry's: Space pages down and Shift+Space up, T and
 * B go to the top and bottom; J/K (or N/P) move through a list, Enter
 * opens what is selected.
 */
object Shortcuts {
    /** A key, as a lowercase letter or digit, or one of the names below. */
    data class Key(val name: String, val shift: Boolean)

    private val handlers = ArrayDeque<(Key) -> Boolean>()

    /** How many text fields have focus (0 or 1, but counted to be safe). */
    private var focusedFields = 0
    val typing get() = focusedFields > 0

    internal fun fieldFocus(gained: Boolean) {
        focusedFields = (focusedFields + if (gained) 1 else -1).coerceAtLeast(0)
    }

    fun push(h: (Key) -> Boolean) = handlers.addLast(h)
    fun remove(h: (Key) -> Boolean) = handlers.remove(h)

    /** Offers the key to the screen in front; true if it was taken. */
    fun dispatch(event: KeyEvent): Boolean {
        val name = nameOf(event) ?: return false
        val k = Key(name, event.isShiftPressed)
        // The screen in front first, then those behind it (the app's own).
        return handlers.toList().asReversed().any { it(k) }
    }

    private fun nameOf(e: KeyEvent): String? = when (e.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> ENTER
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        else -> {
            // The character the layout makes, so Alt-shifted keys on a
            // phone keyboard (its digits and ?) and other layouts come out right.
            val c = e.unicodeChar.takeIf { it != 0 }?.toChar()
                ?: e.getUnicodeChar(0).takeIf { it != 0 }?.toChar()
            when {
                c == null -> null
                c.isLetter() -> c.lowercaseChar().toString()
                c.isDigit() || c in "?/" -> c.toString()
                else -> null
            }
        }
    }

    const val ENTER = "enter"
    const val SPACE = "space"
    const val ESCAPE = "escape"
    const val DOWN = "down"
    const val UP = "up"
    const val HOME = "home"
    const val END = "end"
    const val PAGE_DOWN = "pagedown"
    const val PAGE_UP = "pageup"
}

/**
 * Whether the phone has a hardware keyboard (built in, or plugged in):
 * key hints are shown only then, and are noise on a touch-only phone.
 */
@Composable
fun hasKeyboard(): Boolean = LocalConfiguration.current.let {
    it.keyboard == Configuration.KEYBOARD_QWERTY && it.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}

/** "label  K" with a keyboard, "label" without. */
@Composable
fun withKey(label: String, key: String) = if (hasKeyboard()) "$label  $key" else label

/** Takes keys while this composable is in front. */
@Composable
fun OnShortcut(handler: (Shortcuts.Key) -> Boolean) {
    val current by rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val h: (Shortcuts.Key) -> Boolean = { current(it) }
        Shortcuts.push(h)
        onDispose { Shortcuts.remove(h) }
    }
}

/**
 * A list's selection, moved by keys. Nothing is shown selected until a key
 * moves it, so touch users never see it.
 */
class Selection {
    var index by mutableIntStateOf(0)
    var shown by mutableStateOf(false)

    fun isSelected(i: Int) = shown && i == index
}

@Composable
fun rememberSelection() = remember { Selection() }

/**
 * The keys common to every list: moving the selection, paging, top and
 * bottom. [count] is how many items can be selected; [offset] is how many
 * list items come before them (headers), for scrolling.
 */
fun listKeys(k: Shortcuts.Key, sel: Selection, count: Int, state: LazyListState, scope: CoroutineScope, offset: Int = 0): Boolean {
    fun clamp(i: Int) = i.coerceIn(0, (count - 1).coerceAtLeast(0))
    val page = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2) - 1
    val target = when (k.name) {
        "j", "n", Shortcuts.DOWN -> if (sel.shown) clamp(sel.index + 1) else clamp(state.firstVisibleItemIndex - offset)
        "k", "p", Shortcuts.UP -> if (sel.shown) clamp(sel.index - 1) else clamp(state.firstVisibleItemIndex - offset)
        Shortcuts.SPACE -> clamp(sel.index + if (k.shift) -page else page)
        Shortcuts.PAGE_DOWN -> clamp(sel.index + page)
        Shortcuts.PAGE_UP -> clamp(sel.index - page)
        "t", Shortcuts.HOME -> 0
        "b", Shortcuts.END -> clamp(count - 1)
        else -> return false
    }
    if (count == 0) {
        // Nothing to select: still let T, B and Space move the page.
        scope.launch {
            when (k.name) {
                "t", Shortcuts.HOME -> state.animateScrollToItem(0)
                "b", Shortcuts.END -> state.animateScrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                Shortcuts.SPACE, Shortcuts.PAGE_DOWN, Shortcuts.PAGE_UP -> {
                    val up = k.shift || k.name == Shortcuts.PAGE_UP
                    state.animateScrollToItem((state.firstVisibleItemIndex + if (up) -page else page).coerceAtLeast(0))
                }
            }
        }
        return true
    }
    sel.index = target
    sel.shown = true
    scope.launch { scrollIntoView(state, target + offset) }
    return true
}

private suspend fun scrollIntoView(state: LazyListState, item: Int) {
    val visible = state.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index ?: 0
    val last = visible.lastOrNull()?.index ?: 0
    // The last visible item is often only partly on screen.
    when {
        item <= first -> state.animateScrollToItem(item)
        item >= last -> state.animateScrollToItem((item - visible.size + 2).coerceAtLeast(0))
    }
}

/** Marks a text field, so that keys typed into it aren't shortcuts. */
fun Modifier.typing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (focused) Shortcuts.fieldFocus(false) } }
    onFocusChanged {
        if (it.isFocused != focused) {
            focused = it.isFocused
            Shortcuts.fieldFocus(focused)
        }
    }
}

/**
 * Runs [action] on Enter from a hardware keyboard, before the field takes
 * it: a single-line field on a keyboard phone then submits, as the soft keyboard's
 * action key does.
 */
fun Modifier.enterTo(action: () -> Unit): Modifier = typing().onPreviewKeyEvent { e ->
    val enter = e.key == ComposeKey.Enter || e.key == ComposeKey.NumPadEnter
    if (enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
        action()
        true
    } else {
        enter && e.type == KeyEventType.KeyUp && !e.isShiftPressed
    }
}
