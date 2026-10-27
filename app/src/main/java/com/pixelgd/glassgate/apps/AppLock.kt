package com.pixelgd.glassgate.apps

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast

/**
 * iOS 18's "Require Face ID" for apps, on Android's biometric prompt: face or fingerprint,
 * falling back to the screen lock PIN, pattern or password.
 */
object AppLock {

    /** Shows the system prompt and runs [onSuccess] once the user is verified. */
    fun authenticate(context: Context, appName: String, onSuccess: () -> Unit) {
        val prompt = try {
            BiometricPrompt.Builder(context)
                .setTitle("Unlock $appName")
                .setSubtitle("Glassgate needs to verify it's you")
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
                .build()
        } catch (e: Exception) {
            Toast.makeText(context, "Set up a screen lock to use locked apps", Toast.LENGTH_SHORT).show()
            return
        }
        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) {
                    Toast.makeText(context, "Set up a screen lock to use locked apps", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
