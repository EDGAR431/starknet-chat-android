package chat.stoat.internals

import android.content.Context
import chat.stoat.api.settings.SyncedSettings
import chat.stoat.core.model.schemas.VoiceSettings

/**
 * Ajustes de procesado de audio para las llamadas de voz — cancelación
 * de eco, supresión de ruido y control automático de ganancia. LiveKit
 * los trae activados por defecto.
 *
 * Se guardan en local (para leerlos rápido y sin conexión) y también se
 * sincronizan con la cuenta bajo la misma clave "voice" que ya usa el
 * cliente web — así que activarlos aquí también se refleja allí, y al
 * revés. `push_to_talk` es la excepción: es una forma de interactuar
 * ligada a ESTE dispositivo en concreto (pulsar una tecla en el móvil no
 * tiene el mismo sentido en un PC), así que se queda sin sincronizar.
 */
object VoiceAudioSettings {
    private const val PREFS_NAME = "starknet_voice_audio"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isNoiseSuppressionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("noise_suppression", true)

    fun setNoiseSuppressionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("noise_suppression", enabled).apply()
    }

    fun isEchoCancellationEnabled(context: Context): Boolean =
        prefs(context).getBoolean("echo_cancellation", true)

    fun setEchoCancellationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("echo_cancellation", enabled).apply()
    }

    fun isAutoGainControlEnabled(context: Context): Boolean =
        prefs(context).getBoolean("auto_gain_control", true)

    fun setAutoGainControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_gain_control", enabled).apply()
    }

    fun isPushToTalkEnabled(context: Context): Boolean =
        prefs(context).getBoolean("push_to_talk", false)

    fun setPushToTalkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("push_to_talk", enabled).apply()
    }

    /**
     * Sends the three synced toggles up to the account, under the same
     * "voice" key the web client uses (only these fields — see
     * [SyncedSettings.updateVoice]).
     */
    suspend fun pushToSync(context: Context) {
        SyncedSettings.updateVoice(
            VoiceSettings(
                echoCancellation = isEchoCancellationEnabled(context),
                noiseSupression = if (isNoiseSuppressionEnabled(context)) "browser" else "disabled",
                autoGainControl = isAutoGainControlEnabled(context)
            )
        )
    }

    /**
     * Applies whatever another device last synced, called after
     * [SyncedSettings.fetch] completes. A field the web client never set
     * (still `null` here) is left as-is rather than reset to a default.
     */
    fun hydrateWithSettings(context: Context, settings: SyncedSettings) {
        val voice = settings.voice
        voice.echoCancellation?.let { setEchoCancellationEnabled(context, it) }
        voice.autoGainControl?.let { setAutoGainControlEnabled(context, it) }
        // "disabled" -> off, anything else ("browser"/"enhanced") -> on
        voice.noiseSupression?.let { setNoiseSuppressionEnabled(context, it != "disabled") }
    }
}
