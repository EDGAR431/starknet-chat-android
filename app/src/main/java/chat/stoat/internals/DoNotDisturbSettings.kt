package chat.stoat.internals

import android.content.Context
import java.time.LocalTime

/**
 * Horario de "no molestar" — franja diaria en la que no saltan
 * notificaciones, sin tener que acordarse de activar/desactivar nada
 * a mano cada noche. Se guarda solo en este dispositivo.
 */
object DoNotDisturbSettings {
    private const val PREFS_NAME = "starknet_dnd"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    /** Formato "HH:mm", por ejemplo "23:00". */
    fun getStartTime(context: Context): String =
        prefs(context).getString("start", "23:00") ?: "23:00"

    fun setStartTime(context: Context, time: String) {
        prefs(context).edit().putString("start", time).apply()
    }

    fun getEndTime(context: Context): String =
        prefs(context).getString("end", "08:00") ?: "08:00"

    fun setEndTime(context: Context, time: String) {
        prefs(context).edit().putString("end", time).apply()
    }

    /**
     * Comprueba si el momento actual cae dentro de la franja de no
     * molestar — soporta también franjas que cruzan la medianoche
     * (como 23:00 a 08:00).
     */
    fun isWithinQuietHours(context: Context): Boolean {
        if (!isEnabled(context)) return false

        val now = LocalTime.now()
        val start = parseTimeOrNull(getStartTime(context)) ?: return false
        val end = parseTimeOrNull(getEndTime(context)) ?: return false

        return if (start <= end) {
            now >= start && now < end
        } else {
            // La franja cruza la medianoche (p. ej. 23:00 a 08:00)
            now >= start || now < end
        }
    }

    private fun parseTimeOrNull(value: String): LocalTime? = try {
        LocalTime.parse(value)
    } catch (e: Exception) {
        null
    }
}
