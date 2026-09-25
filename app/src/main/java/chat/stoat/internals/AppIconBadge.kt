package chat.stoat.internals

import android.content.Context
import android.content.Intent

/**
 * Insignia numérica sobre el icono de la app, con la cuenta de
 * mensajes sin leer — Android no tiene una API única para esto (cada
 * fabricante hace lo suyo), así que se manda por difusión a los
 * lanzadores más comunes, la misma técnica que usan la mayoría de
 * apps de mensajería para cubrir el mayor número de móviles posible.
 *
 * El contador es una aproximación: sube con cada notificación nueva,
 * y se pone a cero en cuanto se abre cualquier canal — no un
 * recuento exacto por canal, pero sí fiel a "tienes algo sin leer".
 */
object AppIconBadge {
    private const val PREFS_NAME = "starknet_badge"
    private const val KEY_COUNT = "count"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCount(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    fun increment(context: Context) {
        setCount(context, getCount(context) + 1)
    }

    fun clear(context: Context) {
        setCount(context, 0)
    }

    private fun setCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_COUNT, count).apply()
        applyBadge(context, count)
    }

    private fun applyBadge(context: Context, count: Int) {
        val launcherClassName = getLauncherClassName(context) ?: return

        try {
            // Samsung
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE")
            intent.putExtra("badge_count", count)
            intent.putExtra("badge_count_package_name", context.packageName)
            intent.putExtra("badge_count_class_name", launcherClassName)
            context.sendBroadcast(intent)

            // Sony, HTC, Apex, Nova y similares
            val intentSony = Intent("com.sonyericsson.home.action.UPDATE_BADGE")
            intentSony.putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", launcherClassName)
            intentSony.putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
            intentSony.putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", count.toString())
            intentSony.putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", context.packageName)
            context.sendBroadcast(intentSony)
        } catch (e: Exception) {
            // El lanzador del móvil en cuestión simplemente no soporta
            // insignias — no es un fallo real, se ignora.
        }
    }

    private fun getLauncherClassName(context: Context): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return intent?.component?.className
    }
}
