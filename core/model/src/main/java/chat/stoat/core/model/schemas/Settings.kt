package chat.stoat.core.model.schemas

import chat.stoat.core.model.data.OverridableColourScheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OrderingSettings(
    val servers: List<String> = emptyList()
)


@Serializable
data class NotificationSettings(
    val channel: Map<String, String> = emptyMap(),
    val server: Map<String, String> = emptyMap()
)

@Serializable
data class _NotificationSettingsToParse( // quirk
    val channel: Map<String, JsonElement?> = emptyMap(),
    val server: Map<String, JsonElement?> = emptyMap()
)

@Serializable
data class ReleaseNotesSettings(
    val lastSeenId: String? = null,
    val lastSeenAt: String? = null
)

@Serializable
data class AndroidSpecificSettingsSpecialEmbedSettings(
    /**
     * Whether to embed YouTube videos interactively.
     * Boolean.
     */
    val embedYouTube: Boolean = true,
    /**
     * Whether to embed Apple Music albums and tracks interactively.
     * Boolean.
     */
    val embedAppleMusic: Boolean = false
)

@Serializable
data class VoiceSettings(
    /**
     * Same field names as the web client's own "voice" key, so both
     * platforms read/write the same synced data. Only the fields Android
     * actually has a concept of are declared here — the rest of what the
     * web client stores under this key (chosen devices, per-user volumes,
     * screen share settings...) is preserved as opaque JSON when Android
     * writes to this key; see [chat.stoat.api.settings.mergeIntoSyncedKey].
     */
    val echoCancellation: Boolean? = null,
    /**
     * Web stores this as one of `{ disabled, browser, enhanced }`. Android
     * only has a single on/off toggle, so it maps `true` to `"browser"` and
     * `false` to `"disabled"` — it never sets `"enhanced"`, but will leave
     * that value alone if the web client set it and Android hasn't touched
     * this setting since.
     */
    val noiseSupression: String? = null,
    val autoGainControl: Boolean? = null
)

@Serializable
data class LocaleSettings(
    /**
     * BCP-47 language tag, e.g. "es" or "pt-BR" — same format the web
     * client uses for its own "locale" key. Only pushed when the user
     * picks an explicit language; "Auto (system)" is not synced, since
     * what "automatic" means is specific to each device/platform.
     */
    val lang: String? = null
)

@Serializable
data class AndroidSpecificSettings(
    /**
     * The theme to use for the app.
     * Can be one of `{ None, Default, Light, M3Dynamic, Amoled }`
     */
    var theme: String? = null,
    /**
     * The font to use for the app.
     * Can be one of `{ Default, GoogleSansFlex }`
     */
    var font: String? = null,
    /**
     * Colour overrides.
     * Map of `primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint, inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer, outline, outlineVariant, scrim` to int colours.
     */
    var colourOverrides: OverridableColourScheme? = null,
    /**
     * Message reply style.
     * Can be one of `{ None, SwipeFromEnd, DoubleTap }`
     */
    var messageReplyStyle: String? = null,
    /**
     * Avatar radius.
     * Must be integer in range 0..50 inclusive.
     */
    var avatarRadius: Int? = null,
    /**
     * Controls preferences for special embeds.
     * Object; See [AndroidSpecificSettingsSpecialEmbedSettings] for format.
     */
    var specialEmbedSettings: AndroidSpecificSettingsSpecialEmbedSettings? = null
)
