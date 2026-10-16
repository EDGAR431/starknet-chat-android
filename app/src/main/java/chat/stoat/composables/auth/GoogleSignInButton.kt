package chat.stoat.composables.auth

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import chat.stoat.R
import chat.stoat.core.model.data.GOOGLE_WEB_CLIENT_ID
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

private fun freshHashedNonce(): String {
    val rawNonce = UUID.randomUUID().toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Asks Credential Manager for a Google ID token.
 *
 * On the first attempt, [filterByAuthorizedAccounts] is `true`, which only
 * offers accounts the user has already used with this app -- Google's
 * recommended way to get a near-silent sign-in for returning users. If no
 * such account exists, Credential Manager throws [GetCredentialException]
 * and the caller is expected to retry with `false`, which shows every
 * Google account on the device (and offers to add one).
 */
private suspend fun requestGoogleIdToken(
    context: Context,
    filterByAuthorizedAccounts: Boolean
): String {
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
        .setServerClientId(GOOGLE_WEB_CLIENT_ID)
        .setAutoSelectEnabled(false)
        .setNonce(freshHashedNonce())
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    val credentialManager = CredentialManager.create(context)
    val result = credentialManager.getCredential(context = context, request = request)

    val credential = result.credential
    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    throw GoogleIdTokenParsingException("Unexpected credential type returned: ${credential.type}")
}

/**
 * Tries the silent "already authorized" path first, then falls back to the
 * full account picker if the user hasn't signed in to this app with Google
 * before.
 */
private suspend fun requestGoogleIdTokenWithFallback(context: Context): String {
    return try {
        requestGoogleIdToken(context, filterByAuthorizedAccounts = true)
    } catch (e: GetCredentialException) {
        requestGoogleIdToken(context, filterByAuthorizedAccounts = false)
    }
}

/**
 * Signs the user out of Credential Manager's saved state for this app, so
 * the next sign-in attempt shows the account picker again instead of
 * silently reusing the previous choice.
 */
suspend fun clearGoogleCredentialState(context: Context) {
    try {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    } catch (e: Exception) {
        Log.w("GoogleSignIn", "Failed to clear credential state", e)
    }
}

@Composable
fun GoogleSignInButton(
    onIdToken: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    OutlinedButton(
        onClick = {
            scope.launch {
                try {
                    val idToken = requestGoogleIdTokenWithFallback(context)
                    onIdToken(idToken)
                } catch (e: GetCredentialException) {
                    Log.e("GoogleSignIn", "Credential Manager error", e)
                    onError(e.message ?: "Google sign-in failed")
                } catch (e: GoogleIdTokenParsingException) {
                    Log.e("GoogleSignIn", "Failed to parse Google ID token", e)
                    onError(e.message ?: "Google sign-in failed")
                }
            }
        },
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(),
        modifier = modifier
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(R.drawable.ic_google_logo),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = stringResource(R.string.continue_with_google))
    }
}
