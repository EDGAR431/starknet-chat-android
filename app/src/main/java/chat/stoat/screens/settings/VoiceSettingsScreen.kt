package chat.stoat.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import chat.stoat.R
import chat.stoat.internals.VoiceAudioSettings
import chat.stoat.settings.dsl.SettingsPage
import chat.stoat.settings.dsl.Subcategory
import chat.stoat.settings.dsl.SubcategoryContentInsets

@Composable
fun VoiceSettingsScreen(navController: NavController) {
    val context = LocalContext.current

    var pushToTalkEnabled by remember {
        mutableStateOf(VoiceAudioSettings.isPushToTalkEnabled(context))
    }
    var noiseSuppressionEnabled by remember {
        mutableStateOf(VoiceAudioSettings.isNoiseSuppressionEnabled(context))
    }
    var echoCancellationEnabled by remember {
        mutableStateOf(VoiceAudioSettings.isEchoCancellationEnabled(context))
    }
    var autoGainControlEnabled by remember {
        mutableStateOf(VoiceAudioSettings.isAutoGainControlEnabled(context))
    }

    SettingsPage(
        navController,
        title = {
            Text(
                stringResource(R.string.settings_category_voice),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    ) {
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.settings_voice_push_to_talk))
            },
            supportingContent = {
                Text(text = stringResource(R.string.settings_voice_push_to_talk_description))
            },
            trailingContent = {
                Switch(checked = pushToTalkEnabled, onCheckedChange = null)
            },
            modifier = Modifier.clickable {
                pushToTalkEnabled = !pushToTalkEnabled
                VoiceAudioSettings.setPushToTalkEnabled(context, pushToTalkEnabled)
            }
        )

        Subcategory(
            title = {
                Text(text = stringResource(R.string.settings_voice_audio_processing))
            },
            contentInsets = SubcategoryContentInsets
        ) {
            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.settings_voice_noise_suppression))
                },
                trailingContent = {
                    Switch(checked = noiseSuppressionEnabled, onCheckedChange = null)
                },
                modifier = Modifier.clickable {
                    noiseSuppressionEnabled = !noiseSuppressionEnabled
                    VoiceAudioSettings.setNoiseSuppressionEnabled(context, noiseSuppressionEnabled)
                }
            )
            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.settings_voice_echo_cancellation))
                },
                trailingContent = {
                    Switch(checked = echoCancellationEnabled, onCheckedChange = null)
                },
                modifier = Modifier.clickable {
                    echoCancellationEnabled = !echoCancellationEnabled
                    VoiceAudioSettings.setEchoCancellationEnabled(context, echoCancellationEnabled)
                }
            )
            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.settings_voice_auto_gain_control))
                },
                trailingContent = {
                    Switch(checked = autoGainControlEnabled, onCheckedChange = null)
                },
                modifier = Modifier.clickable {
                    autoGainControlEnabled = !autoGainControlEnabled
                    VoiceAudioSettings.setAutoGainControlEnabled(context, autoGainControlEnabled)
                }
            )
        }
    }
}
