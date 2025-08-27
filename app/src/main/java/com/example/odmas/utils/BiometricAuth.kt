package com.example.odmas.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Simple BiometricPrompt launcher with safe fallbacks.
 * Uses androidx.biometric:biometric:1.1.0 APIs (device credential allowed).
 */
object BiometricAuth {

    /**
     * Try to authenticate user with biometrics or device credential.
     * Returns false if we cannot launch (no activity/capability); true if prompt launched.
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String? = null,
        description: String? = null,
        confirmationRequired: Boolean = false,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        val activity = (context.findActivity() as? FragmentActivity) ?: return false

        // Capability check (legacy API for 1.1.0)
        val can = BiometricManager.from(context).canAuthenticate()
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fall back to device credential if possible via prompt config below
            // If the device has neither biometrics nor credential, we'll still try;
            // BiometricPrompt will surface an error which we map to onFailure/onCancel.
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Treat user cancel separately
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onCancel()
                } else {
                    onFailure()
                }
            }

            override fun onAuthenticationFailed() {
                // Non-fatal failure; allow retries. Surface as failure for now.
                onFailure()
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        // For 1.1.0, prefer enabling device credentials; biometric strength cannot be specified here.
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setConfirmationRequired(confirmationRequired)
            .setDeviceCredentialAllowed(true)

        subtitle?.let { infoBuilder.setSubtitle(it) }
        description?.let { infoBuilder.setDescription(it) }

        val promptInfo = infoBuilder.build()

        prompt.authenticate(promptInfo)
        return true
    }
}

/**
 * Safely unwrap an Activity from a Context.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}