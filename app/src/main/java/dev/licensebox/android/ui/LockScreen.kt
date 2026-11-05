package dev.licensebox.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.licensebox.android.MainActivity
import dev.licensebox.android.R
import dev.licensebox.android.data.AppLock

/**
 * The app lock. It asks at once; Enter, or the button, asks again. A phone
 * with no screen lock is sent to set one: the lock can't be skipped.
 */
@Composable
fun LockScreen(model: AppModel) {
    val activity = LocalContext.current as MainActivity
    val available = model.lockAvailable
    LaunchedEffect(available) { if (available) activity.unlock() }
    OnShortcut { k -> if (k.name == Shortcuts.ENTER) { if (available) activity.unlock() else AppLock.openSettings(activity); true } else false }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Image(
            painterResource(R.drawable.mark), contentDescription = null, modifier = Modifier.size(48.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
        )
        if (available) {
            Text("licensebox is locked", style = MaterialTheme.typography.titleLarge)
            Button(onClick = activity::unlock) { Text("Unlock") }
        } else {
            Text("Set a screen lock first", style = MaterialTheme.typography.titleLarge)
            Text(
                "licensebox opens only behind your fingerprint or screen lock, so no one else holding your phone can manage your products.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Button(onClick = { AppLock.openSettings(activity) }) { Text("Open security settings") }
        }
    }
}
