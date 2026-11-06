package chat.stoat.composables.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.ChannelUtils
import chat.stoat.api.settings.NotificationSettingsProvider
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.voice.VoiceCallManager
import kotlinx.coroutines.delay
import logcat.LogPriority
import logcat.logcat
import java.time.OffsetDateTime

/** Stop ringing after this long, like a missed call. */
private const val RING_TIMEOUT_MS = 45_000L

private fun parseJoinedAtMillis(value: String?): Long? =
    value?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

/**
 * Rings and shows an "incoming call" banner when someone starts a call in a
 * DM or group that we are not part of. Only works while the app is open;
 * calls that were already ongoing when the app started are ignored.
 */
@Composable
fun IncomingCallHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Calls the user declined/answered: don't ring again until that call ends
    var handled by remember { mutableStateOf(setOf<String>()) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    // Forget handled calls once nobody is left in them
    LaunchedEffect(StoatAPI.voiceStateCache.toMap()) {
        handled = handled.filter { id ->
            StoatAPI.voiceStateCache[id]?.participants?.isNotEmpty() == true
        }.toSet()
    }

    val selfId = StoatAPI.selfId
    val busy = StoatAPI.userCache[selfId]?.status?.presence == "Busy"

    val ringing: String? =
        if (selfId == null || busy || VoiceCallManager.activeChannelId != null) {
            null
        } else {
            StoatAPI.voiceStateCache.entries
                .mapNotNull { (channelId, state) ->
                    if (channelId in handled) return@mapNotNull null
                    val channel = StoatAPI.channelCache[channelId] ?: return@mapNotNull null
                    if (channel.channelType != ChannelType.DirectMessage &&
                        channel.channelType != ChannelType.Group
                    ) return@mapNotNull null
                    if (state.participants.isEmpty()) return@mapNotNull null
                    if (state.participants.any { it.id == selfId }) return@mapNotNull null
                    if (NotificationSettingsProvider.isChannelMuted(channelId, null)) {
                        return@mapNotNull null
                    }

                    val since = state.participants
                        .mapNotNull { parseJoinedAtMillis(it.joinedAt) }
                        .maxOrNull() ?: return@mapNotNull null
                    if (now - since >= RING_TIMEOUT_MS) return@mapNotNull null
                    channelId to since
                }
                .maxByOrNull { it.second }
                ?.first
        }

    // Ringtone, only while the banner is showing (respects silent/vibrate mode)
    DisposableEffect(ringing) {
        var player: MediaPlayer? = null
        if (ringing != null) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                try {
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        context.resources.openRawResourceFd(R.raw.sfx_ringtone_incoming).use {
                            setDataSource(it.fileDescriptor, it.startOffset, it.length)
                        }
                        isLooping = true
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Could not play ringtone: ${e.message}" }
                    player = null
                }
            }
        }
        onDispose {
            try {
                player?.stop()
            } catch (_: IllegalStateException) {
            }
            player?.release()
        }
    }

    // Stop showing (and ringing) after the timeout even if nothing else changes
    LaunchedEffect(ringing) {
        if (ringing != null) {
            delay(RING_TIMEOUT_MS)
            handled = handled + ringing
        }
    }

    AnimatedVisibility(
        visible = ringing != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        // keep the last id while animating out
        val channelId = ringing ?: return@AnimatedVisibility
        val channel = StoatAPI.channelCache[channelId]
        val name = channel?.let(ChannelUtils::resolveName)
            ?: stringResource(R.string.voice_notification_fallback_channel_name)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_voice_chat_24dp),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.voice_incoming_call),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { handled = handled + channelId },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.voice_incoming_call_decline))
                    }
                    Button(
                        onClick = {
                            handled = handled + channelId
                            VoiceCallManager.openSheet(channelId)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.voice_incoming_call_accept))
                    }
                }
            }
        }
    }
}
