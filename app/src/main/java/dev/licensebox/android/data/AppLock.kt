package dev.licensebox.android.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks for the phone's fingerprint or screen lock when the app opens, and
 * when it comes back after five minutes away: whoever has the unlocked
 * phone in hand can't then manage products as its admin. It can't be
 * turned off; a phone with no screen lock can't use the app until one is
 * set.
 *
 * It guards the screens only. The session token is not bound to the
 * prompt, so the request check can still run in the background.
 */
object AppLock {
    const val GRACE_MILLIS = 5 * 60 * 1000L

    // Weak, not strong, biometrics: Android 9 and 10 can't
    // offer strong biometrics and the screen lock in one prompt.
    private const val HOW = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Whether the phone has a fingerprint or a screen lock to ask for. */
    fun available(c: Context) = BiometricManager.from(c).canAuthenticate(HOW) == BiometricManager.BIOMETRIC_SUCCESS

    /** Opens Android's settings where a screen lock or fingerprint is set. */
    fun openSettings(c: Context) {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, HOW)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock licensebox")
                .setSubtitle("Your products' users and grants are behind this")
                .setAllowedAuthenticators(HOW)
                .build(),
        )
    }
}
