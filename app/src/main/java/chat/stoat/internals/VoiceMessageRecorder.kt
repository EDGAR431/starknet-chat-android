package chat.stoat.internals

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Graba una nota de voz corta a un archivo temporal — se sube después
 * como un adjunto normal, reutilizando exactamente el mismo mecanismo
 * que ya existe para fotos y documentos.
 */
class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** `null` si no se pudo empezar a grabar (por ejemplo, sin permiso de micrófono). */
    fun start(): Boolean {
        val file = File.createTempFile("voice-message-", ".m4a", context.cacheDir)
        outputFile = file

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            true
        } catch (e: Exception) {
            mediaRecorder.release()
            file.delete()
            outputFile = null
            false
        }
    }

    /**
     * Para la grabación y devuelve el archivo resultante, o `null` si
     * la nota fue demasiado corta (menos de medio segundo — para
     * evitar mandar notas vacías por un toque accidental) o si algo
     * falló.
     */
    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null

        return try {
            mediaRecorder.stop()
            mediaRecorder.release()
            if (file != null && file.length() > 0) file else null
        } catch (e: Exception) {
            // MediaRecorder.stop() lanza una excepción si se para
            // demasiado pronto (grabación demasiado corta) — no es un
            // error real, simplemente se descarta la nota.
            mediaRecorder.release()
            file?.delete()
            null
        }
    }

    /** Descarta la grabación en curso sin guardar nada — por ejemplo, si se cancela. */
    fun cancel() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // ignorar
        }
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
