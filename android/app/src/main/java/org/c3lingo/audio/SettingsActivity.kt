package org.c3lingo.audio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The server settings. Also opened from the gear on the system's App info screen, the
 * closest Android has to the iOS Settings app.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            C3lingoTheme {
                SettingsScreen(app.settings, onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: ServerSettings, onBack: () -> Unit) {
    val serverURL by settings.serverURL.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf(settings.customURLString) }
    val parsedDraft = ServerSettings.urlFrom(draft)
    val valid = draft.isBlank() || parsedDraft != null

    fun apply() {
        if (!valid) return
        settings.customURLString = parsedDraft?.toString().orEmpty()
        draft = settings.customURLString
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_url_label)) },
                placeholder = { Text(settings.defaultURL.toString()) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!valid) {
                Text(
                    stringResource(R.string.settings_invalid_url),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.settings_footer, settings.defaultURL.toString(), serverURL.toString()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        draft = ""
                        apply()
                    },
                    enabled = !(settings.isUsingDefault && draft.isEmpty()),
                ) {
                    Text(stringResource(R.string.settings_use_default))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::apply, enabled = valid) {
                    Text(stringResource(R.string.settings_apply))
                }
            }
            val uriHandler = LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri(ServerSettings.PRIVACY_POLICY_URL) }) {
                Text(stringResource(R.string.privacy_policy))
            }
        }
    }
}
