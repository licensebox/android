package dev.licensebox.android.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.licensebox.android.api.AdminApi
import dev.licensebox.android.api.ApiError
import dev.licensebox.android.data.SessionStore
import dev.licensebox.android.data.SignedIn
import dev.licensebox.android.notify.RequestWatch
import dev.licensebox.android.update.Available
import dev.licensebox.android.update.Updater
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Products : Screen
    /** A product's page of cards. */
    data class Product(val id: String) : Screen
    data class Requests(val product: String) : Screen
    /** A product's users; [search] puts the cursor in the search field. */
    data class Users(val product: String, val search: Boolean = false) : Screen
    data class Activity(val product: String) : Screen
    data class User(val product: String, val email: String) : Screen
    data class Customers(val product: String) : Screen
    data class Customer(val product: String, val id: String) : Screen
    /** Requests to create products: operators only. */
    data object Joins : Screen
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)

    var session by mutableStateOf(store.load())
        private set

    val stack = mutableStateListOf<Screen>(Screen.Products)
    val screen get() = stack.last()

    /** A short message for the snackbar, shown once. */
    var message by mutableStateOf<String?>(null)

    /** Whether the app lock is up, and whether the phone has a lock to ask for. */
    var locked by mutableStateOf(true)
    var lockAvailable by mutableStateOf(true)

    /** When the app last went to the background; 0 before it first has. */
    var awayAt = 0L

    /** Whether the help sheet of shortcuts is open. */
    var help by mutableStateOf(false)

    private val updater = Updater(app)

    /** A newer release, until it is put off; and whether it is downloading. */
    var update by mutableStateOf(updater.known().takeIf { updater.enabled })
        private set
    var updating by mutableStateOf(false)
        private set

    private var checking = false

    /**
     * Asks GitHub for a newer release: each time the app comes to the
     * front, and from the menu. GitHub allows 60 such unauthenticated
     * requests an hour from one address, far more than a person opens an
     * app. A failed check says nothing unless asked for ([tell]); it's
     * tried again next time.
     */
    fun checkForUpdate(tell: Boolean = false) {
        if (!updater.enabled) {
            if (tell) message = "Debug builds don't update themselves"
            return
        }
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                val found = updater.check()
                update = found
                if (tell && found == null) message = "This is the latest version"
            } catch (e: Exception) {
                if (tell) message = "Couldn't check for updates: ${e.message ?: "try again later"}"
            } finally {
                checking = false
            }
        }
    }

    fun putOffUpdate() {
        update = null
    }

    /** Downloads the update and offers it to Android's installer. */
    fun installUpdate() {
        val a: Available = update ?: return
        if (updating) return
        if (!updater.mayInstall()) {
            message = "Allow installs from this app, then choose Update again"
            updater.askToInstall()
            return
        }
        updating = true
        viewModelScope.launch {
            try {
                updater.install(updater.download(a))
            } catch (e: Exception) {
                message = "Update failed: ${e.message ?: "try again later"}"
            } finally {
                updating = false
            }
        }
    }

    val api get() = session.let { AdminApi(it?.server ?: store.server, it?.token) }

    fun open(s: Screen) {
        stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Opens a product's page, its requests first, as a notification asks. */
    fun openJoins() {
        stack.clear()
        stack.add(Screen.Products)
        stack.add(Screen.Joins)
    }

    fun openRequests(product: String) {
        stack.clear()
        stack.add(Screen.Products)
        stack.add(Screen.Product(product))
    }

    fun signedIn(s: SignedIn) {
        store.save(s)
        session = s
        stack.clear()
        stack.add(Screen.Products)
        RequestWatch.start(getApplication())
    }

    fun signOut() {
        val api = api
        viewModelScope.launch { runCatching { api.logout() } }
        endSession()
    }

    private fun endSession() {
        store.clear()
        session = null
        RequestWatch.stop(getApplication())
    }

    /** What every failed call does: say so, and sign out if the session went. */
    fun failed(e: Throwable) {
        if (e is ApiError && e.signedOut) endSession()
        message = describe(e)
    }

    /** Runs a change on the server, saying [done] after, then [after]. */
    fun change(done: String?, after: () -> Unit = {}, block: suspend AdminApi.() -> Unit) {
        val api = api
        viewModelScope.launch {
            try {
                api.block()
                if (done != null) message = done
                after()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }
}

@Composable
fun App(model: AppModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let {
            snackbar.showSnackbar(it)
            model.message = null
        }
    }
    BackHandler(enabled = model.session != null && model.stack.size > 1) { model.back() }
    OnShortcut { k ->
        when {
            k.name == "h" || k.name == "?" -> { model.help = !model.help; true }
            k.name == "i" && model.update != null && !model.locked -> { model.installUpdate(); true }
            k.name == Shortcuts.ESCAPE -> model.help.also { model.help = false } || model.back()
            else -> false
        }
    }

    // On a keyboard, Compose gives focus to the first button or card when a
    // screen opens, and it sits highlighted. The root takes it instead,
    // unseen; shortcuts don't need focus, and Tab still moves it on.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(model.screen, model.locked, model.session) { runCatching { rootFocus.requestFocus() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).focusRequester(rootFocus).focusable()) {
            val session = model.session
            if (model.locked) {
                LockScreen(model)
                return@Box
            }
            if (session == null) {
                SignInScreen(model)
            } else {
                Column(Modifier.fillMaxSize()) {
                    model.update?.let { UpdateBanner(it, model) }
                    AnimatedContent(model.screen, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                        when (s) {
                            Screen.Products -> ProductsScreen(model, session)
                            is Screen.Product -> ProductScreen(model, s)
                            is Screen.Requests -> RequestsScreen(model, s)
                            is Screen.Users -> UsersScreen(model, s)
                            is Screen.Activity -> ActivityScreen(model, s)
                            is Screen.User -> UserScreen(model, s)
                            is Screen.Customers -> CustomersScreen(model, s)
                            is Screen.Customer -> CustomerScreen(model, s)
                            Screen.Joins -> JoinsScreen(model)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
            if (model.help) HelpSheet(model.screen, session != null) { model.help = false }
        }
    }
}

@Composable
private fun UpdateBanner(a: Available, model: AppModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (model.updating) "Downloading ${a.version}…" else "Version ${a.version} is available",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        if (!model.updating) {
            TextButton(onClick = model::putOffUpdate) { Text("Later") }
            TextButton(onClick = model::installUpdate) { Text(withKey("Update", "I")) }
        }
    }
}
