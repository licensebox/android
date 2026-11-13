package dev.licensebox.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import dev.licensebox.android.data.AppLock
import dev.licensebox.android.notify.RequestWatch
import dev.licensebox.android.ui.App
import dev.licensebox.android.ui.AppModel
import dev.licensebox.android.ui.LicenseboxTheme
import dev.licensebox.android.ui.Shortcuts

// A FragmentActivity, as BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        if (model.session != null) RequestWatch.start(this)
        setContent { LicenseboxTheme { App(model) } }
    }

    // The lock: on every start, and on coming back after five minutes away.
    override fun onStart() {
        super.onStart()
        model.lockAvailable = AppLock.available(this)
        val away = model.awayAt
        if (away == 0L || System.currentTimeMillis() - away > AppLock.GRACE_MILLIS) model.locked = true
        model.checkForUpdate()
    }

    override fun onStop() {
        super.onStop()
        model.awayAt = System.currentTimeMillis()
    }

    fun unlock() {
        if (model.lockAvailable) AppLock.prompt(this) { model.locked = false }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification's tap: open the product's requests. */
    private fun handle(intent: Intent?) {
        val product = intent?.getStringExtra(RequestWatch.EXTRA_PRODUCT) ?: return
        if (model.session != null) model.openRequests(product)
    }

    // Keys go to the screen's shortcuts first, unless a text field is being
    // typed in; a key taken here never reaches Compose, so it can't move
    // focus onto a button (which Enter would then press). Its key-up is
    // swallowed too.
    private var taken = -1

    // Activity.dispatchKeyEvent is public Android API. Lint objects only
    // because androidx's ComponentActivity redeclares it as restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == taken) {
            taken = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !Shortcuts.typing && !event.isCtrlPressed && !event.isMetaPressed &&
            (event.repeatCount == 0 || event.keyCode in repeatable) && Shortcuts.dispatch(event)
        ) {
            taken = event.keyCode
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val repeatable = setOf(
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_P,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
    )
}
