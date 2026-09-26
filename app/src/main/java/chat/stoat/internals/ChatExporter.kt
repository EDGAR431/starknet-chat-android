package chat.stoat.internals

import chat.stoat.api.routes.channel.fetchMessagesFromChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta el historial de un canal a texto plano legible, paginando
 * hacia atrás con la misma API que ya usa la app para cargar
 * mensajes — no hace falta nada nuevo en el servidor.
 *
 * Limitado a un número razonable de mensajes (por defecto 1000) para
 * que la copia de seguridad no tarde una eternidad ni se dispare de
 * tamaño en un canal muy activo.
 */
object ChatExporter {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    suspend fun exportChannelHistory(
        channelId: String,
        channelName: String,
        maxMessages: Int = 1000,
    ): String {
        val builder = StringBuilder()
        builder.append("Copia de seguridad de #$channelName\n")
        builder.append("Exportado el ${dateFormat.format(Date())}\n")
        builder.append("=".repeat(40)).append("\n\n")

        val usernameById = mutableMapOf<String, String>()
        val allMessages = mutableListOf<chat.stoat.core.model.schemas.Message>()
        var before: String? = null

        while (allMessages.size < maxMessages) {
            val batchSize = minOf(100, maxMessages - allMessages.size)
            val result = fetchMessagesFromChannel(
                channelId = channelId,
                limit = batchSize,
                includeUsers = true,
                before = before,
            )
            val messages = result.messages ?: break
            if (messages.isEmpty()) break

            result.users?.forEach { user ->
                val id = user.id
                if (id != null) {
                    usernameById[id] = user.username ?: id
                }
            }

            allMessages.addAll(messages)
            before = messages.lastOrNull()?.id ?: break

            if (messages.size < batchSize) break // no quedan más mensajes
        }

        // Los mensajes llegan del más nuevo al más antiguo — se
        // invierten para que la copia se lea en orden cronológico.
        for (message in allMessages.asReversed()) {
            val author = message.author?.let { usernameById[it] ?: it } ?: "?"
            val timestamp = message.id?.let { ulidToTimestamp(it) }?.let { dateFormat.format(Date(it)) } ?: ""
            val content = describeMessageContent(message)
            builder.append("[$timestamp] $author: $content\n")
        }

        return builder.toString()
    }

    /**
     * Antes esto era simplemente `message.content ?: ""` — cualquier
     * mensaje sin texto (una imagen, un archivo, un mensaje de voz o
     * un mensaje de sistema como "fulano se unió al canal") se
     * exportaba como una línea en blanco, sin ningún rastro de que
     * ahí había pasado algo. Se pierde información real en la copia
     * de seguridad. Ahora se describe qué había en su lugar.
     */
    private fun describeMessageContent(message: chat.stoat.core.model.schemas.Message): String {
        message.content?.takeIf { it.isNotBlank() }?.let { return it }

        message.system?.let { return describeSystemMessage(it) }

        val attachments = message.attachments
        if (!attachments.isNullOrEmpty()) {
            val names = attachments.joinToString(", ") { it.filename ?: "archivo" }
            return "[Adjunto: $names]"
        }

        return "[Mensaje sin contenido de texto]"
    }

    private fun describeSystemMessage(system: chat.stoat.core.model.schemas.SystemInfo): String {
        val by = system.by ?: "?"
        val id = system.id ?: "?"
        return when (system.type) {
            "user_added" -> "$by añadió a $id"
            "user_remove" -> "$by expulsó a $id"
            "user_banned" -> "$id fue baneado"
            "user_kicked" -> "$id fue expulsado"
            "user_left" -> "$id abandonó el canal"
            "user_joined" -> "$id se unió al canal"
            "channel_renamed" -> "$by renombró el canal a \"${system.name ?: "?"}\""
            "channel_icon_changed" -> "$by cambió el icono del canal"
            "channel_description_changed" -> "$by cambió la descripción del canal"
            "channel_ownership_changed" -> "${system.from ?: "?"} transfirió la propiedad a ${system.to ?: "?"}"
            "message_pinned" -> "$by fijó un mensaje"
            "message_unpinned" -> "$by desfijó un mensaje"
            "call_started" -> "$by inició una llamada"
            "text" -> system.content ?: "[Mensaje de sistema]"
            else -> "[Mensaje de sistema]"
        }
    }

    /** Los IDs de Stoat son ULID — los primeros 48 bits codifican la fecha. */
    private fun ulidToTimestamp(ulid: String): Long? = try {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var value = 0L
        for (c in ulid.take(10)) {
            val index = alphabet.indexOf(c.uppercaseChar())
            if (index < 0) return null // carácter fuera del alfabeto Crockford: ID inválido, no un timestamp real
            value = value * 32 + index
        }
        value
    } catch (e: Exception) {
        null
    }
}
