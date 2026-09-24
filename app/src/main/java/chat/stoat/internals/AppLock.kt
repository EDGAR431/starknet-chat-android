package chat.stoat.internals

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Bloqueo de la app con la huella dactilar, la cara o el PIN/patrón
 * del propio dispositivo — la misma pantalla de desbloqueo que ya usa
 * Android para todo lo demás, no una nuestra inventada.
 *
 * Es una preferencia de ESTE dispositivo en concreto — a propósito no
 * se sincroniza con el servidor ni con otros dispositivos, ya que
 * bloquear el móvil de uno no debería afectar al de los demás.
 */
object AppLock {
    private const val PREFS_NAME = "starknet_app_lock"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Si el dispositivo no tiene ninguna huella, cara ni PIN/patrón
     * configurados, no tiene sentido ofrecer esta opción — Android no
     * tendría con qué autenticar.
     */
    fun isAvailableOnDevice(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Se pone a `true` mientras la app está pendiente de desbloquearse. */
    val isLocked = mutableStateOf(false)

    /**
     * Se llama cada vez que la app vuelve a primer plano. Si el
     * bloqueo está activado, se marca como bloqueada y se lanza el
     * diálogo nativo de autenticación de Android.
     */
    fun onAppForegrounded(activity: FragmentActivity, onUnlocked: () -> Unit = {}) {
        if (!isEnabled(activity)) {
            isLocked.value = false
            return
        }

        isLocked.value = true
        promptUnlock(activity, onUnlocked)
    }

    fun promptUnlock(activity: FragmentActivity, onUnlocked: () -> Unit = {}) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    isLocked.value = false
                    onUnlocked()
                }
                // Si falla o se cancela, no hacemos nada — la app se
                // queda bloqueada y el usuario puede volver a intentarlo
                // con el botón de la pantalla de bloqueo.
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(chat.stoat.R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info)
    }

    /** Bloquea la app de inmediato la próxima vez que vuelva a abrirse. */
    fun onAppBackgrounded(context: Context) {
        if (isEnabled(context)) {
            isLocked.value = true
        }
    }
}
