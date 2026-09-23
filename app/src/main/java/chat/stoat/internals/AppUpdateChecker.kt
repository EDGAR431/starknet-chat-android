package chat.stoat.internals

import android.app.Activity
import android.content.IntentSender
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.mutableStateOf
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Comprobación de actualizaciones dentro de la propia app, usando la
 * API oficial de Google Play Core ("In-App Updates") — el mismo
 * mecanismo que usan la mayoría de apps grandes de Play Store.
 *
 * Se usa el tipo "flexible": la actualización se descarga sola en
 * segundo plano sin interrumpir al usuario, y solo se le avisa cuando
 * ya está lista para instalar (con un botón de "Reiniciar ahora"),
 * igual que hace Discord.
 */
class AppUpdateChecker(activity: Activity) {
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    /**
     * Se pone a `true` en cuanto la actualización ya se descargó del
     * todo y solo falta reiniciar la app para aplicarla — la interfaz
     * observa este valor para mostrar el aviso correspondiente.
     */
    val updateReadyToInstall = mutableStateOf(false)

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            updateReadyToInstall.value = true
        }
    }

    init {
        appUpdateManager.registerListener(listener)
    }

    /**
     * Le pregunta a Google Play si hay una versión más reciente que
     * la instalada. Si la hay, empieza a descargarla sola. Pensado
     * para llamarse al abrir la app, y también a mano desde Ajustes.
     */
    fun checkForUpdate(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onResult: (updateAvailable: Boolean) -> Unit = {},
    ) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val updateAvailable =
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

            if (updateAvailable && flexibleAllowed) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                } catch (e: IntentSender.SendIntentException) {
                    // Si algo falla al lanzar el diálogo de Play Store, no
                    // es grave — simplemente no se ofrece la actualización
                    // esta vez, se reintentará la próxima.
                }
            }

            // Si una actualización ya se había descargado en una sesión
            // anterior y nunca se llegó a reiniciar, se lo recordamos ahora.
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                updateReadyToInstall.value = true
            }

            onResult(updateAvailable)
        }.addOnFailureListener {
            onResult(false)
        }
    }

    /** Aplica la actualización ya descargada — esto reinicia la app. */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    /** Hay que llamarlo al destruirse la actividad, para no dejar fugas. */
    fun dispose() {
        appUpdateManager.unregisterListener(listener)
    }
}
