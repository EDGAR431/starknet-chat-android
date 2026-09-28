package chat.stoat.api.routes.sync

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.api
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class SyncedSetting(val timestamp: Long, val value: String)

suspend fun getKeys(vararg keys: String, token: String): Map<String, SyncedSetting> {
    val response = StoatHttp.post("/sync/settings/fetch".api()) {
        headers.append(StoatAPI.TOKEN_HEADER_NAME, token)

        // format: {"keys": ["key1", "key2"]}
        setBody(
            StoatJson.encodeToString(
                MapSerializer(
                    String.serializer(),
                    ListSerializer(String.serializer())
                ),
                mapOf("keys" to keys.toList())
            )
        )
    }.bodyAsText()

    return StoatJson.decodeFromString(
        MapSerializer(
            String.serializer(),
            JsonArray.serializer()
        ),
        response
    ).mapValues { (_, value) ->
        SyncedSetting(
            timestamp = value[0].toString().toLong(),
            value = value[1]
                .toString()
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\") // the revolt API is so scuffed i can't even make this up
        )
    }
}

suspend fun getKeys(vararg keys: String): Map<String, SyncedSetting> {
    return getKeys(*keys, token = StoatAPI.sessionToken)
}

suspend fun setKey(key: String, value: String, timestamp: Long = System.currentTimeMillis()) {
    StoatHttp.post("/sync/settings/set".api()) {
        parameter("timestamp", timestamp)

        // format: {"key": "value"}
        setBody(
            StoatJson.encodeToString(
                MapSerializer(
                    String.serializer(),
                    String.serializer()
                ),
                mapOf(key to value)
            )
        )
    }
}

/**
 * Update just a few fields of a synced key shared with other clients
 * (e.g. the web client's own "voice"/"locale" keys hold plenty of fields
 * Android has no concept of, like chosen devices or per-user volumes).
 *
 * A plain [setKey] would upload only what Android knows about and wipe out
 * everything else another client had stored there. This fetches the
 * current value first, overlays [patch] on top of it field-by-field, and
 * uploads the merged result — so unrelated fields survive untouched.
 *
 * @param key Sync key to update
 * @param patch Fields to overwrite — build this with only the fields
 * Android actually wants to change (callers already leave out anything
 * Android has no value for, rather than passing it as JSON null)
 * @return the timestamp the merged value was written with, so callers that
 * track revisions locally (see `SyncedSettings.revisions`) can record it and
 * avoid a later `applyRemoteUpdate` push re-applying an equal-or-older value.
 */
suspend fun mergeIntoSyncedKey(key: String, patch: JsonObject): Long {
    val current = try {
        getKeys(key)[key]?.value?.let { StoatJson.parseToJsonElement(it).jsonObject }
    } catch (e: Exception) {
        null
    } ?: JsonObject(emptyMap())

    val merged = buildJsonObject {
        current.forEach { (k, v) -> put(k, v) }
        patch.forEach { (k, v) -> put(k, v) }
    }

    val timestamp = System.currentTimeMillis()
    setKey(key, StoatJson.encodeToString(JsonObject.serializer(), merged), timestamp)
    return timestamp
}
