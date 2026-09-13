package com.regolith.data.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.regolith.domain.security.AuthResult
import com.regolith.domain.security.BiometricAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The system's own "prove it's you" prompt, wrapped as one suspending call.
 *
 * This is the PLATFORM [BiometricPrompt] (`android.hardware.biometrics`),
 * not the AndroidX one. Everything it needs — choosing which
 * authenticators to allow, falling back to the screen lock — landed in API
 * 30, and Regolith's minSdk is 34, so the support library would buy
 * nothing and cost a dependency and a `FragmentActivity` (the AndroidX
 * prompt has no `ComponentActivity` overload, and this app has exactly one
 * Activity, which is not one).
 *
 * [Authenticators.BIOMETRIC_STRONG] `or` [Authenticators.DEVICE_CREDENTIAL]
 * is deliberate: a finger that will not read, a cut plaster, a face in the
 * dark — the PIN is the way back in, and a lock with no way back in is a
 * lock that loses someone their library. It also means the prompt works on
 * a device with no reader at all, which is what the emulator usually is.
 */
@Singleton
class BiometricGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** What the device can do right now — drives whether the Settings switch can be turned on. */
    fun availability(): BiometricAvailability {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return BiometricAvailability.NO_HARDWARE
        return when (manager.canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    /**
     * Show the prompt and wait for it. Cancelling the coroutine takes the
     * prompt down with it, which is what a lock screen leaving the
     * composition should do.
     *
     * A finger that simply did not match is NOT an answer: the system keeps
     * the prompt up and lets the user try again, so `onAuthenticationFailed`
     * is deliberately not handled here.
     */
    suspend fun authenticate(activity: Activity, title: String, subtitle: String): AuthResult =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                // No negative button: setting one is forbidden once
                // DEVICE_CREDENTIAL is allowed, and the system draws its own.
                .setAllowedAuthenticators(ALLOWED)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(
                cancellation,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(AuthResult.Success)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        if (!continuation.isActive) return
                        // There is no negative-button case to handle: setting
                        // one is forbidden once DEVICE_CREDENTIAL is allowed.
                        val dismissed = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        continuation.resume(
                            if (dismissed) AuthResult.Cancelled else AuthResult.Error(message?.toString().orEmpty()),
                        )
                    }
                },
            )
        }

    private companion object {
        const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
