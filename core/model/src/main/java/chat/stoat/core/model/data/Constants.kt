package chat.stoat.core.model.data

const val STOAT_BASE = "https://starknet-chat.tech/api"
const val STOAT_SUPPORT = "https://starknet-chat.tech"
const val STOAT_MARKETING = "https://starknet-chat.tech"
const val STOAT_FILES = "https://starknet-chat.tech/autumn"
const val STOAT_PROXY = "https://starknet-chat.tech/january"
const val STOAT_WEB_APP = "https://starknet-chat.tech"
const val STOAT_BETA_WEB_APP = "https://starknet-chat.tech"
const val STOAT_INVITES = "https://starknet-chat.tech/invite"
const val STOAT_WEBSOCKET = "wss://starknet-chat.tech/ws"
const val STOAT_CHANGELOG = "https://starknet-chat.tech"

/**
 * OAuth 2.0 **Web application** client ID from Google Cloud Console (the
 * same one the web client at starknet-chat.tech uses). Credential Manager's
 * Sign in with Google always issues ID tokens scoped to the *web* client
 * (passed as `setServerClientId`), never to an Android client -- the
 * Android-type OAuth client registered in the same Google Cloud project
 * (package name + signing certificate SHA-1/SHA-256) is only used by Google
 * Play services to confirm which app is allowed to ask for a credential; it
 * never appears in code or in the token itself.
 */
const val GOOGLE_WEB_CLIENT_ID =
    "487698849491-6046vqiiiansu50ri0ggcbdsee6ii6ob.apps.googleusercontent.com"
