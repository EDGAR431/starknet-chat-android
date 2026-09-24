package chat.stoat.internals

import android.content.Context

/**
 * Ajustes de procesado de audio para las llamadas de voz — cancelación
 * de eco, supresión de ruido y control automático de ganancia. LiveKit
 * los trae activados por defecto, pero no había ninguna forma de
 * ajustarlos: se dejan aquí como preferencias de ESTE dispositivo en
 * concreto (no se sincronizan), por si algún micrófono/altavoz en
 * concreto da problemas con alguno de ellos.
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
}
