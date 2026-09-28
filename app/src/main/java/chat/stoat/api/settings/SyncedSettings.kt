package chat.stoat.api.settings

import androidx.compose.runtime.mutableStateOf
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.routes.sync.getKeys
import chat.stoat.api.routes.sync.mergeIntoSyncedKey
import chat.stoat.api.routes.sync.setKey
import chat.stoat.core.model.schemas.AndroidSpecificSettings
import chat.stoat.core.model.schemas.LocaleSettings
import chat.stoat.core.model.schemas.NotificationSettings
import chat.stoat.core.model.schemas.OrderingSettings
import chat.stoat.core.model.schemas.ReleaseNotesSettings
import chat.stoat.core.model.schemas.VoiceSettings
import chat.stoat.core.model.schemas._NotificationSettingsToParse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/*
 * - Note: When adding a new key -
 *  1. Add corresponding methods and fields here
 *  2. Add strings for poorly formed keys hint
 *  3. Add UI for resetting the key if it's poorly formed
 */

object SyncedSettings {
    private val _fetchCompleted = CompletableDeferred<Unit>()

    suspend fun awaitFetched() = _fetchCompleted.await()

    private val _ordering = mutableStateOf(OrderingSettings())
    private val _android = mutableStateOf(
        AndroidSpecificSettings(
            theme = "None",
            font = "Default",
            colourOverrides = null,
            messageReplyStyle = "None"
        )
    )
    private val _notifications = mutableStateOf(NotificationSettings())
    private val _releaseNotes = mutableStateOf(ReleaseNotesSettings())
    private val _voice = mutableStateOf(VoiceSettings())
    private val _locale = mutableStateOf(LocaleSettings())

    val ordering: OrderingSettings
        get() = _ordering.value
    val android: AndroidSpecificSettings
        get() = _android.value
    val notifications: NotificationSettings
        get() = _notifications.value
    val releaseNotes: ReleaseNotesSettings
        get() = _releaseNotes.value

    /**
     * These two share their key with the web/desktop client ("voice",
     * "locale") — see [VoiceSettings] and [LocaleSettings] for why writes
     * to them go through [chat.stoat.api.routes.sync.mergeIntoSyncedKey]
     * instead of a plain overwrite.
     */
    val voice: VoiceSettings
        get() = _voice.value
    val locale: LocaleSettings
        get() = _locale.value

    suspend fun fetch(apiToken: String = StoatAPI.sessionToken) {
        try {
            val settings =
                getKeys(
                    "ordering",
                    "android",
                    "notifications",
                    "release-notes",
                    "voice",
                    "locale",
                    token = apiToken
                )

            settings["ordering"]?.let {
                try {
                    _ordering.value = StoatJson.decodeFromString(
                        OrderingSettings.serializer(),
                        it.value
                    )
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "ordering"
                    e.printStackTrace()
                }
            }

            settings["android"]?.let {
                try {
                    _android.value = StoatJson.decodeFromString(
                        AndroidSpecificSettings.serializer(),
                        it.value
                    )
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "android"
                    e.printStackTrace()
                }
            }

            settings["notifications"]?.let {
                // This is to fix a quirk where the web client sometimes leaves sub-objects in one of the objects
                // Because it is written in typescript and does what it wants
                _notifications.value = parseNotificationSettings(it.value)
            }

            settings["release-notes"]?.let {
                try {
                    _releaseNotes.value = StoatJson.decodeFromString(
                        ReleaseNotesSettings.serializer(),
                        it.value
                    )
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "release-notes"
                    e.printStackTrace()
                }
            }

            // "voice" and "locale" are shared with the web/desktop client and hold
            // plenty of fields Android doesn't declare — ignoreUnknownKeys on
            // StoatJson means decoding only the fields VoiceSettings/LocaleSettings
            // know about is safe and won't throw on the rest.
            settings["voice"]?.let {
                try {
                    _voice.value = StoatJson.decodeFromString(VoiceSettings.serializer(), it.value)
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "voice"
                    e.printStackTrace()
                }
            }

            settings["locale"]?.let {
                try {
                    _locale.value = StoatJson.decodeFromString(LocaleSettings.serializer(), it.value)
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "locale"
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _fetchCompleted.complete(Unit)
        }
    }

    private fun parseNotificationSettings(value: String): NotificationSettings {
        return try {
            var intermediate =
                StoatJson.decodeFromString(_NotificationSettingsToParse.serializer(), value)

            // Throw out any value of intermediate.server and .channel that isn't a string
            intermediate = intermediate.copy(
                server = intermediate.server.filterValues { it != null }
                    .filterValues { it is JsonPrimitive }
                    .filterValues { it!!.jsonPrimitive.isString },
                channel = intermediate.channel.filterValues { it != null }
                    .filterValues { it is JsonPrimitive }
                    .filterValues { it!!.jsonPrimitive.isString }
            )

            // Convert the intermediate to a NotificationSettings
            NotificationSettings(
                server = intermediate.server.mapValues { it.value!!.jsonPrimitive.content },
                channel = intermediate.channel.mapValues { it.value!!.jsonPrimitive.content }
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
            LoadedSettings.poorlyFormedSettingsKeys += "notifications"
            NotificationSettings()
        }
    }

    suspend fun updateOrdering(value: OrderingSettings) {
        _ordering.value = value
        setKey("ordering", StoatJson.encodeToString(OrderingSettings.serializer(), value))
    }

    suspend fun updateAndroid(value: AndroidSpecificSettings) {
        _android.value = value
        setKey("android", StoatJson.encodeToString(AndroidSpecificSettings.serializer(), value))
    }

    /**
     * Updates only the fields Android understands within the shared "voice"
     * key, leaving whatever the web/desktop client stored there (chosen
     * devices, per-user volumes...) untouched — see [mergeIntoSyncedKey].
     */
    suspend fun updateVoice(value: VoiceSettings) {
        _voice.value = value
        mergeIntoSyncedKey(
            "voice",
            buildJsonObject {
                value.echoCancellation?.let { put("echoCancellation", it) }
                value.noiseSupression?.let { put("noiseSupression", it) }
                value.autoGainControl?.let { put("autoGainControl", it) }
            }
        )
    }

    /**
     * Same reasoning as [updateVoice] — "locale" is shared with other
     * clients, so only the field Android sets is merged in, not overwritten
     * wholesale.
     */
    suspend fun updateLocale(value: LocaleSettings) {
        _locale.value = value
        mergeIntoSyncedKey(
            "locale",
            buildJsonObject {
                value.lang?.let { put("lang", it) }
            }
        )
    }

    suspend fun updateNotifications(value: NotificationSettings) {
        _notifications.value = value
        setKey("notifications", StoatJson.encodeToString(NotificationSettings.serializer(), value))
    }

    suspend fun updateReleaseNotes(value: ReleaseNotesSettings) {
        _releaseNotes.value = value
        setKey("release-notes", StoatJson.encodeToString(ReleaseNotesSettings.serializer(), value))
    }

    suspend fun resetOrdering() {
        val default = OrderingSettings()
        _ordering.value = default
        setKey("ordering", StoatJson.encodeToString(OrderingSettings.serializer(), default))
    }

    suspend fun resetAndroid() {
        val default = AndroidSpecificSettings(
            theme = "None",
            font = "Default",
            colourOverrides = null,
            messageReplyStyle = "None"
        )
        _android.value = default
        setKey("android", StoatJson.encodeToString(AndroidSpecificSettings.serializer(), default))
    }

    suspend fun resetNotifications() {
        val default = NotificationSettings()
        _notifications.value = default
        setKey(
            "notifications",
            StoatJson.encodeToString(NotificationSettings.serializer(), default)
        )
    }

    suspend fun resetReleaseNotes() {
        val default = ReleaseNotesSettings()
        _releaseNotes.value = default
        setKey(
            "release-notes",
            StoatJson.encodeToString(ReleaseNotesSettings.serializer(), default)
        )
    }
}
