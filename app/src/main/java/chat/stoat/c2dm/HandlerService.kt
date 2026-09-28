package chat.stoat.c2dm

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import chat.stoat.BuildConfig
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.fetchSingleChannel
import chat.stoat.api.routes.push.subscribePush
import chat.stoat.c2dm.ChannelRegistrator.Companion.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.persistence.Database
import chat.stoat.persistence.KVStorage
import chat.stoat.persistence.SqlStorage
import com.bumptech.glide.Glide
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import kotlin.math.abs

private val LETTER_ICON_COLORS = intArrayOf(
    0xFF1565C0.toInt(),
    0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(),
    0xFFC62828.toInt(),
    0xFF00838F.toInt(),
    0xFFE65100.toInt(),
    0xFF4527A0.toInt(),
    0xFF283593.toInt(),
)

private fun generateLetterBitmap(name: String, sizePx: Int = 256): Bitmap {
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val color = LETTER_ICON_COLORS[abs(name.hashCode()) % LETTER_ICON_COLORS.size]

    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = color
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    paint.color = Color.WHITE
    paint.textSize = sizePx * 0.45f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    val bounds = Rect()
    paint.getTextBounds(letter, 0, 1, bounds)
    canvas.drawText(letter, sizePx / 2f, sizePx / 2f + bounds.height() / 2f - bounds.bottom, paint)

    return bitmap
}

object NotificationID {
    const val NEW_MESSAGE = 0
}

class HandlerService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runBlocking {
            subscribePush(auth = token)
        }
    }

    override fun onMessageReceived(fcmMessage: RemoteMessage) {
        val data = fcmMessage.data

        val type = data["type"]
        if (type != "push.message") {
            logcat(LogPriority.ERROR) { "Unknown message type: $type, abort" }
            return
        }

        val authorId = data["author"] ?: run {
            logcat(LogPriority.ERROR) { "No author in message, abort" }
            return
        }

        chat.stoat.internals.AppIconBadge.increment(applicationContext)

        val body = data["body"] ?: run {
            logcat(LogPriority.ERROR) { "No body in message, abort" }
            return
        }

        // El avatar es cosmetico, no esencial: si el payload de FCM no lo
        // trae (avatar por defecto, mensaje de un webhook, o cualquier
        // caso que el backend no cubra), antes se abortaba la notificacion
        // ENTERA por esto - el usuario se quedaba sin avisar de un mensaje
        // real por culpa de un campo puramente decorativo. Ahora, igual
        // que ya se hace mas abajo con "authorBitmap", si no hay imagen
        // simplemente se deja en null y "loadBitmap"/el fallback de letra
        // se encargan de generar un avatar sustituto.
        val image = data["image"]

        val authorName = data["author_name"] ?: run {
            logcat(LogPriority.ERROR) { "No author name in message, abort" }
            return
        }

        val channelId = data["channel"] ?: run {
            logcat(LogPriority.ERROR) { "No channel in message, abort" }
            return
        }

        val messageId = data["message"] ?: run {
            logcat(LogPriority.ERROR) { "No message ID in message, abort" }
            return
        }

        val messageTimestamp = ULID.asTimestamp(messageId)

        val db = Database(SqlStorage.driver)

        fun serverPrefix(serverId: String?): String? {
            if (serverId == null) return null
            return db.serverQueries.findById(serverId).executeAsOneOrNull()?.name
        }

        fun formatChannelName(type: String, name: String?, serverId: String?): String {
            val base = when (type) {
                "DirectMessage" -> return authorName
                "TextChannel" -> "#${name}"
                else -> name ?: return authorName
            }
            val prefix = serverPrefix(serverId) ?: return base
            return "$prefix · $base"
        }

        fun loadBitmap(url: String) = Glide.with(this)
            .asBitmap()
            .load(url)
            .circleCrop()
            .submit()
            .get()

        val kv = KVStorage(this)
        val selfId = runBlocking { kv.get("selfId") }.orEmpty()
        val selfName = runBlocking { kv.get("selfName") }.orEmpty()
        val selfAvatarUrl = runBlocking { kv.get("selfAvatarUrl") }

        val dbChannel = db.channelQueries.findById(channelId).executeAsOneOrNull()

        val serverIconUrl = when (dbChannel?.channelType) {
            "TextChannel", "VoiceChannel" ->
                dbChannel.server?.let { db.serverQueries.findById(it).executeAsOneOrNull() }
                    ?.iconId?.let { "$STOAT_FILES/icons/$it" }

            "Group" -> dbChannel.iconId?.let { "$STOAT_FILES/icons/$it" }
            else -> null
        }

        // Antes, tanto el nombre del canal (si no estaba ya en la base de
        // datos local) como las tres imagenes (avatar propio, avatar del
        // autor, icono de servidor/canal) se resolvian UNA DETRAS DE OTRA,
        // cada una bloqueando hasta terminar - con red lenta eso sumaba
        // varios segundos de retraso antes de que apareciera siquiera la
        // notificacion. Ahora se piden todas EN PARALELO y con un limite de
        // tiempo corto cada una: si alguna tarda demasiado, se usa el valor
        // de repuesto (letra de color para el avatar, el nombre del autor
        // para el canal) en vez de seguir esperando - la notificacion no
        // deberia notarse mas lenta que la propia llegada del push.
        val avatarTimeoutMs = 1200L

        data class DatosResueltos(
            val channelName: String,
            val selfBitmap: Bitmap,
            val authorBitmap: Bitmap,
            val conversationIconBitmap: Bitmap?,
        )

        val resuelto = runBlocking {
            coroutineScope {
                val channelNameDeferred = async(Dispatchers.IO) {
                    dbChannel?.let { formatChannelName(it.channelType, it.name, it.server) }
                        ?: withTimeoutOrNull(avatarTimeoutMs) {
                            runCatching { fetchSingleChannel(channelId) }.getOrNull()?.let {
                                formatChannelName(it.channelType?.value ?: "", it.name, it.server)
                            }
                        }
                        ?: authorName
                }
                val selfDeferred = async(Dispatchers.IO) {
                    if (!selfAvatarUrl.isNullOrEmpty()) {
                        withTimeoutOrNull(avatarTimeoutMs) {
                            runCatching { loadBitmap(selfAvatarUrl) }.getOrNull()
                        }
                    } else {
                        null
                    }
                }
                val authorDeferred = async(Dispatchers.IO) {
                    if (!image.isNullOrEmpty()) {
                        withTimeoutOrNull(avatarTimeoutMs) {
                            runCatching { loadBitmap(image) }.getOrNull()
                        }
                    } else {
                        null
                    }
                }
                val serverIconDeferred = async(Dispatchers.IO) {
                    serverIconUrl?.let {
                        withTimeoutOrNull(avatarTimeoutMs) { runCatching { loadBitmap(it) }.getOrNull() }
                    }
                }

                DatosResueltos(
                    channelName = channelNameDeferred.await(),
                    selfBitmap = selfDeferred.await() ?: generateLetterBitmap(selfName.ifEmpty { "?" }),
                    authorBitmap = authorDeferred.await() ?: generateLetterBitmap(authorName),
                    conversationIconBitmap = serverIconDeferred.await(),
                )
            }
        }

        val channelName = resuelto.channelName
        val selfBitmap = resuelto.selfBitmap
        val authorBitmap = resuelto.authorBitmap

        val self = Person.Builder()
            .setBot(false)
            .setKey(selfId.ifEmpty { "self" })
            .setIcon(IconCompat.createWithBitmap(selfBitmap))
            .setName(selfName.ifEmpty { "Me" })
            .build()

        val conversationBitmap: Bitmap = resuelto.conversationIconBitmap
            ?: when (dbChannel?.channelType) {
                "TextChannel", "VoiceChannel" -> generateLetterBitmap(
                    dbChannel.server?.let { db.serverQueries.findById(it).executeAsOneOrNull()?.name }
                        ?: channelName
                )

                "Group" -> generateLetterBitmap(dbChannel.name ?: channelName)
                else -> authorBitmap
            }

        val author = Person.Builder()
            .setBot(false)
            .setKey(authorId)
            .setIcon(IconCompat.createWithBitmap(authorBitmap))
            .setName(authorName)
            .build()

        val shortcutId = "${BuildConfig.APPLICATION_ID}.channel.$channelId"

        val conversationIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("channelId", channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(channelName)
            .setLongLabel(channelName)
            .setIcon(IconCompat.createWithBitmap(conversationBitmap))
            .setIntent(conversationIntent)
            .setLongLived(true)
            .setPerson(author)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        val remoteInput = RemoteInput.Builder("content").run {
            setLabel(getString(R.string.message_context_sheet_actions_reply))
            build()
        }

        val replyIntent = Intent(this, ReplyReceiver::class.java).apply {
            putExtra("channelId", channelId)
        }

        val replyAction: NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                R.drawable.ic_reply_24dp,
                getString(R.string.message_context_sheet_actions_reply),
                PendingIntent.getBroadcast(
                    this,
                    channelId.hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
                .addRemoteInput(remoteInput)
                .build()

        val markAsReadIntent = Intent(this, MarkAsReadReceiver::class.java).apply {
            putExtra("channelId", channelId)
            putExtra("messageId", messageId)
        }

        val markAsReadAction: NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                R.drawable.ic_mark_chat_read_24dp,
                getString(R.string.channel_context_sheet_actions_mark_read),
                PendingIntent.getBroadcast(
                    this,
                    channelId.hashCode() xor 1,
                    markAsReadIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
                .build()

        val contentIntent = PendingIntent.getActivity(
            this,
            channelId.hashCode(),
            conversationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val existingStyle = NotificationManagerCompat.from(this)
            .activeNotifications
            .firstOrNull { it.tag == channelId && it.id == NotificationID.NEW_MESSAGE }
            ?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

        val messagingStyle = (existingStyle ?: NotificationCompat.MessagingStyle(self))
            .setGroupConversation(dbChannel?.channelType != "DirectMessage")
            .setConversationTitle(channelName)
            .addMessage(body, messageTimestamp, author)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES)
            .setSmallIcon(R.drawable.ic_starknet_notification)
            .setContentTitle(authorName)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(messagingStyle)
            .addAction(replyAction)
            .addAction(markAsReadAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Android 11 bubbles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setShortcutId(shortcutId)
            builder.setLocusId(LocusIdCompat(shortcutId))

            val bubbleIntent = PendingIntent.getActivity(
                this,
                channelId.hashCode(),
                conversationIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                bubbleIntent,
                IconCompat.createWithBitmap(conversationBitmap)
            )
                .setDesiredHeight(600)
                .setAutoExpandBubble(false)
                .setSuppressNotification(false)
                .build()

            builder.setBubbleMetadata(bubbleMetadata)
        }

        NotificationManagerCompat.from(this).apply {
            if (ActivityCompat.checkSelfPermission(
                    this@HandlerService,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (chat.stoat.internals.DoNotDisturbSettings.isWithinQuietHours(this@HandlerService)) {
                // Sigue llegando a la bandeja para verla más tarde,
                // pero sin sonido, vibración ni aviso emergente.
                builder.setSilent(true)
            }
            notify(channelId, NotificationID.NEW_MESSAGE, builder.build())
        }
    }
}
