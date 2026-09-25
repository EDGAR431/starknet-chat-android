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
            val content = message.content ?: ""
            builder.append("[$timestamp] $author: $content\n")
        }

        return builder.toString()
    }

    /** Los IDs de Stoat son ULID — los primeros 48 bits codifican la fecha. */
    private fun ulidToTimestamp(ulid: String): Long? = try {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var value = 0L
        for (c in ulid.take(10)) {
            value = value * 32 + alphabet.indexOf(c.uppercaseChar())
        }
        value
    } catch (e: Exception) {
        null
    }
}
