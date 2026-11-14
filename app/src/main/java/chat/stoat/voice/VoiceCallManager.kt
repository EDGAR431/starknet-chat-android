package chat.stoat.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.stoat.R
import chat.stoat.StoatApplication
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.ChannelUtils
import chat.stoat.api.routes.misc.LiveKitNode
import chat.stoat.api.routes.misc.Root
import chat.stoat.api.routes.misc.getRootRoute
import chat.stoat.api.routes.voice.JoinCallResponse
import chat.stoat.api.routes.voice.joinCall
import chat.stoat.composables.voice.VoiceSound
import chat.stoat.composables.voice.VoiceSoundPlayer
import chat.stoat.internals.VoiceAudioSettings
import chat.stoat.services.OngoingCallService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.ScreenSharePresets
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the lifecycle of the current voice call.
 * The LiveKit [Room] lives here
 */
object VoiceCallManager {
    const val DEAFENED_ATTRIBUTE = "deafened"

    private val context: Context get() = StoatApplication.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var activeChannelId: String? by mutableStateOf(null)
        private set

    var room: Room? by mutableStateOf(null)
        private set

    var errorResource: Int? by mutableStateOf<Int?>(null)
        private set

    var isDeafened: Boolean by mutableStateOf(false)

    /** Volume (0..1) of other people's stream audio, set from the stream viewer. */
    var streamVolume: Float by mutableStateOf(1f)
        private set

    /** Volumen individual (0..2) de cada usuario; se guarda en el dispositivo. */
    private val userVolumes = mutableStateMapOf<String, Float>()

    fun userVolume(userId: String): Float =
        userVolumes[userId] ?: VoiceAudioSettings.getUserVolume(context, userId)

    fun updateUserVolume(userId: String, volume: Float, persist: Boolean = true) {
        val v = volume.coerceIn(0f, 2f)
        userVolumes[userId] = v
        if (persist) VoiceAudioSettings.setUserVolume(context, userId, v)
        room?.let(::applyDeafenState)
    }

    fun updateStreamVolume(volume: Float) {
        streamVolume = volume.coerceIn(0f, 1f)
        room?.let(::applyDeafenState)
    }

    var isSheetVisible: Boolean by mutableStateOf(false)

    var requestedChannelId: String? by mutableStateOf(null)
        private set

    /** Si está activado, el micrófono empieza apagado y solo se activa
     *  mientras se mantiene pulsado el botón de hablar. */
    val isPushToTalkEnabled: Boolean get() = VoiceAudioSettings.isPushToTalkEnabled(context)

    var isPushToTalkActive: Boolean by mutableStateOf(false)
        private set

    /** Ping real en ms a la sala de LiveKit, actualizado cada 2 segundos. */
    var pingMs: Int? by mutableStateOf<Int?>(null)
        private set

    private var pingJob: Job? = null

    fun openSheet(channelId: String) {
        requestedChannelId = channelId
        isSheetVisible = true
    }

    private val probeClient by lazy { HttpClient(OkHttp) }

    private var micWasOnBeforeDeafen = false
    private var soundPlayer: VoiceSoundPlayer? = null
    private var sessionJob: Job? = null
    private var hasPlayedJoinSound = false
    private var hasPlayedLeaveSound = false

    init {
        scope.launch {
            OngoingCallService.hangupEvents.collect { leave() }
        }
    }

    fun join(channelId: String) {
        if (activeChannelId == channelId && room != null) return
        endSession()

        activeChannelId = channelId
        errorResource = null
        hasPlayedJoinSound = false
        hasPlayedLeaveSound = false
        soundPlayer = VoiceSoundPlayer(context)

        val newRoom = LiveKit.create(
            context.applicationContext,
            RoomOptions(
                // Bateria/datos: solo se recibe/decodifica el video a la
                // calidad que realmente se ve, y se pausa si no se ve.
                adaptiveStream = true,
                dynacast = true,
                audioTrackCaptureDefaults = LocalAudioTrackOptions(
                    noiseSuppression = VoiceAudioSettings.isNoiseSuppressionEnabled(context),
                    echoCancellation = VoiceAudioSettings.isEchoCancellationEnabled(context),
                    autoGainControl = VoiceAudioSettings.isAutoGainControlEnabled(context),
                ),
                screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
                    isScreencast = true,
                    captureParams = ScreenSharePresets.H720_FPS30.capture
                )
            )
        )
        room = newRoom

        sessionJob = scope.launch {
            launch { watchRoomState(newRoom, channelId) }
            launch { watchRoomEvents(newRoom) }
            launch { watchLocalScreenShare(newRoom) }
            launch { watchLocalMicrophone(newRoom) }

            val connection = fetchVoiceToken(channelId) ?: return@launch
            try {
                newRoom.connect(connection.url, connection.token)
                newRoom.localParticipant.setMicrophoneEnabled(!isPushToTalkEnabled)
                startPingPolling(newRoom)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Could not connect to LiveKit room\n" + e.asLog() }
                errorResource = R.string.voice_error_generic
            }
        }
    }

    fun leave() {
        isSheetVisible = false
        requestedChannelId = null
        endSession()
    }

    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        stopPingPolling()
        isPushToTalkActive = false

        val leftRoom = room
        room = null
        activeChannelId = null
        errorResource = null
        isDeafened = false
        streamVolume = 1f
        micWasOnBeforeDeafen = false

        if (leftRoom != null) {
            if (hasPlayedJoinSound && !hasPlayedLeaveSound) {
                hasPlayedLeaveSound = true
                soundPlayer?.play(VoiceSound.USER_LEAVE)
            }
            leftRoom.disconnect()
            leftRoom.release()
        }
        soundPlayer?.release()
        soundPlayer = null
        OngoingCallService.stop(context)
    }

    fun toggleMicrophone() {
        val room = room ?: return
        val isMicOn = room.localParticipant.isMicrophoneEnabled
        soundPlayer?.play(if (isMicOn) VoiceSound.MUTE else VoiceSound.UNMUTE)
        scope.launch {
            room.localParticipant.setMicrophoneEnabled(!isMicOn)
        }
    }

    /**
     * Activa el micrófono mientras se mantiene pulsado el botón de
     * Push-to-Talk, y lo apaga al soltar — sin los sonidos de
     * silenciar/activar normales, para no ser molesto al hablar a
     * ráfagas.
     */
    fun onPushToTalkPressed() {
        val room = room ?: return
        if (isPushToTalkActive) return
        isPushToTalkActive = true
        scope.launch {
            room.localParticipant.setMicrophoneEnabled(true)
        }
    }

    fun onPushToTalkReleased() {
        val room = room ?: return
        if (!isPushToTalkActive) return
        isPushToTalkActive = false
        scope.launch {
            room.localParticipant.setMicrophoneEnabled(false)
        }
    }

    /**
     * LiveKit no da un número de ping ya calculado — se saca de las
     * estadísticas WebRTC de la propia conexión (no de un track en
     * concreto), así que funciona haya o no micrófono/cámara
     * publicados.
     */
    private fun startPingPolling(room: Room) {
        stopPingPolling()
        pingJob = scope.launch {
            while (true) {
                try {
                    room.getPublisherRTCStats { report ->
                        for (stats in report.statsMap.values) {
                            if (stats.type == "candidate-pair" &&
                                stats.members["state"] == "succeeded"
                            ) {
                                val rtt = stats.members["currentRoundTripTime"] as? Double
                                if (rtt != null) {
                                    val ms = (rtt * 1000).toInt()
                                    scope.launch { pingMs = ms }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "No se pudo obtener el ping real\n" + e.asLog() }
                }
                delay(5000) // bateria: antes 2 s
            }
        }
    }

    private fun stopPingPolling() {
        pingJob?.cancel()
        pingJob = null
        pingMs = null
    }

    fun toggleDeafen() {
        val room = room ?: return
        if (!isDeafened) {
            soundPlayer?.play(VoiceSound.DEAFEN)
            micWasOnBeforeDeafen = room.localParticipant.isMicrophoneEnabled
            isDeafened = true
            applyDeafenState(room)
            publishDeafened(room, true)
            scope.launch {
                room.localParticipant.setMicrophoneEnabled(false)
            }
        } else {
            soundPlayer?.play(VoiceSound.UNDEAFEN)
            isDeafened = false
            applyDeafenState(room)
            publishDeafened(room, false)
            if (micWasOnBeforeDeafen) {
                scope.launch {
                    room.localParticipant.setMicrophoneEnabled(true)
                }
            }
        }
    }

    /** Avisa al resto de la llamada de que nos hemos ensordecido (atributo de LiveKit). */
    private fun publishDeafened(room: Room, deafened: Boolean) {
        try {
            room.localParticipant.updateAttributes(
                mapOf(DEAFENED_ATTRIBUTE to if (deafened) "1" else "0")
            )
        } catch (_: Exception) {
            // Sin permiso en el token (servidor antiguo): solo no se difunde.
        }
    }

    private fun applyDeafenState(room: Room) {
        room.remoteParticipants.values.forEach { participant ->
            participant.audioTrackPublications.forEach { (publication, track) ->
                val volume = when {
                    isDeafened -> 0.0
                    publication.source == Track.Source.SCREEN_SHARE_AUDIO -> streamVolume.toDouble()
                    else -> userVolume(participant.identity?.value ?: "").toDouble()
                }
                (track as? RemoteAudioTrack)?.setVolume(volume)
            }
        }
    }

    private suspend fun watchRoomState(room: Room, channelId: String) {
        room::state.flow.collect { state ->
            when (state) {
                Room.State.CONNECTED -> {
                    if (!hasPlayedJoinSound) {
                        hasPlayedJoinSound = true
                        soundPlayer?.play(VoiceSound.USER_JOIN)
                        OngoingCallService.start(
                            context,
                            channelId,
                            StoatAPI.channelCache[channelId]?.let(ChannelUtils::resolveName)
                        )
                    }
                }

                Room.State.DISCONNECTED -> {
                    if (hasPlayedJoinSound) leave()
                }

                Room.State.CONNECTING, Room.State.RECONNECTING -> Unit
            }
        }
    }

    private suspend fun watchRoomEvents(room: Room) {
        room.events.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> applyDeafenState(room)

                is RoomEvent.DataReceived -> handleSoundboardMessage(
                    event.data,
                    event.participant?.identity?.value,
                )

                is RoomEvent.ParticipantConnected -> soundPlayer?.play(VoiceSound.USER_JOIN)
                is RoomEvent.ParticipantDisconnected -> soundPlayer?.play(VoiceSound.USER_LEAVE)

                is RoomEvent.TrackPublished -> {
                    if (event.participant != room.localParticipant &&
                        event.publication.source == Track.Source.SCREEN_SHARE
                    ) {
                        soundPlayer?.play(VoiceSound.STREAM_START)
                    }
                }

                is RoomEvent.TrackUnpublished -> {
                    if (event.participant != room.localParticipant &&
                        event.publication.source == Track.Source.SCREEN_SHARE
                    ) {
                        soundPlayer?.play(VoiceSound.STREAM_END)
                    }
                }

                else -> Unit
            }
        }
    }

    private val lastSoundboardAt = mutableMapOf<String, Long>()

    /**
     * Otro participante lanzó un sonido del soundboard: llega como orden
     * ({"type":"starknet-soundboard","fileId":...,"volume":...}) y se
     * reproduce en local, respetando ensordecer y el volumen de ese usuario.
     */
    private fun handleSoundboardMessage(data: ByteArray, identity: String?) {
        if (identity == null || isDeafened) return
        val json = try {
            org.json.JSONObject(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        if (json.optString("type") != "starknet-soundboard") return
        val fileId = json.optString("fileId")
        if (!Regex("^[A-Za-z0-9_-]{8,64}$").matches(fileId)) return
        val sent = json.optDouble("volume", 1.0).let {
            if (it.isNaN()) 1.0 else it.coerceIn(0.0, 2.0)
        }

        val now = System.currentTimeMillis()
        if (now - (lastSoundboardAt[identity] ?: 0L) < 250) return
        lastSoundboardAt[identity] = now

        val volume = (sent * userVolume(identity).coerceAtMost(2f)).toFloat().coerceIn(0f, 1f)
        if (volume <= 0f) return

        scope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(context.cacheDir, "sb_$fileId")
                if (!file.exists() || file.length() == 0L) {
                    val res = chat.stoat.api.StoatHttp.get(
                        "${chat.stoat.core.model.data.STOAT_FILES}/attachments/$fileId"
                    )
                    if (!res.isSuccess()) return@launch
                    file.writeBytes(res.body<ByteArray>())
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val player = android.media.MediaPlayer()
                    player.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    player.setDataSource(file.absolutePath)
                    player.setVolume(volume, volume)
                    player.setOnCompletionListener { it.release() }
                    player.setOnErrorListener { mp, _, _ -> mp.release(); true }
                    player.setOnPreparedListener { it.start() }
                    player.prepareAsync()
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Soundboard remoto fallo\n" + e.asLog() }
            }
        }
    }

    private suspend fun watchLocalMicrophone(room: Room) {
        room.localParticipant::isMicrophoneEnabled.flow.collect { enabled ->
            OngoingCallService.updateMicMuted(context, muted = !enabled)
        }
    }

    private suspend fun watchLocalScreenShare(room: Room) {
        var wasSharing: Boolean? = null
        room.localParticipant::isScreenShareEnabled.flow.collect { isSharing ->
            wasSharing?.let { previous ->
                if (isSharing && !previous) soundPlayer?.play(VoiceSound.STREAM_START)
                if (!isSharing && previous) soundPlayer?.play(VoiceSound.STREAM_END)
            }
            wasSharing = isSharing
        }
    }

    private suspend fun fastestNode(nodes: List<LiveKitNode>): LiveKitNode? =
        withTimeoutOrNull(5.seconds) {
            try {
                coroutineScope {
                    val winner = CompletableDeferred<LiveKitNode>()
                    val probes = nodes.map { node ->
                        launch {
                            try {
                                val probeUrl = node.publicUrl
                                    .replaceFirst("wss://", "https://")
                                    .replaceFirst("ws://", "http://")
                                if (probeClient.get(probeUrl).status.isSuccess()) {
                                    winner.complete(node)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    launch {
                        probes.joinAll()
                        winner.completeExceptionally(
                            IllegalStateException("No LiveKit node reachable")
                        )
                    }
                    try {
                        winner.await()
                    } finally {
                        coroutineContext.cancelChildren()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun fetchVoiceToken(channelId: String): JoinCallResponse? {
        val root: Root
        try {
            root = getRootRoute()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Could not get root route\n" + e.asLog() }
            errorResource = R.string.voice_error_generic
            return null
        }

        val lk = root.features.livekit

        if (lk == null) {
            logcat(LogPriority.ERROR) {
                IllegalStateException("LiveKit is not supported by this API version!").asLog()
            }
            errorResource = R.string.voice_error_not_supported
            return null
        }

        if (lk.nodes.isEmpty()) {
            logcat(LogPriority.ERROR) { IllegalStateException("No LiveKit nodes available!").asLog() }
            errorResource = R.string.voice_error_no_nodes
            return null
        }

        val node = fastestNode(lk.nodes)
        if (node == null) {
            logcat(LogPriority.ERROR) { "No LiveKit node responded to probing!" }
            errorResource = R.string.voice_error_no_nodes
            return null
        }

        return try {
            joinCall(channelId, node.name)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Could not get LiveKit token\n" + e.asLog() }
            errorResource = R.string.voice_error_generic
            null
        }
    }
}
