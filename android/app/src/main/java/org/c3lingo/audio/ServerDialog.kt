package org.c3lingo.audio

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Changes the server, for testing: one field, checked while typing.
 *
 * The URL is shown without `https://`; an empty field means the default server. Once
 * the host looks complete, the page is fetched to check that it is a c3lingo frontend,
 * and Save stays disabled until it is. Save always reloads the page: a different server
 * starts over by itself, the same one calls [onReload].
 */
@Composable
fun ServerDialog(settings: ServerSettings, onReload: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable {
        val url = ServerSettings.urlFrom(settings.customURLString)
        mutableStateOf(url?.takeIf { it != settings.defaultURL }?.let(ServerSettings::displayString).orEmpty())
    }
    var status by remember { mutableStateOf<ServerStatus>(ServerStatus.Valid) }
    // Focused right away, which also shows the default server as the placeholder.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val input = draft.trim()
    val canSave = input.isEmpty() || status == ServerStatus.Valid

    LaunchedEffect(input) {
        if (input.isEmpty()) {
            status = ServerStatus.Valid
            return@LaunchedEffect
        }
        status = ServerStatus.Unchecked
        // Wait for typing to pause, so partial host names aren't looked up.
        delay(700)
        val url = ServerSettings.urlFrom(input)
        if (url == null) {
            status = ServerStatus.Problem(context.getString(R.string.server_invalid))
            return@LaunchedEffect
        }
        if (!ServerSettings.looksComplete(url)) return@LaunchedEffect
        status = ServerStatus.Checking
        status = ServerCheck.problem(context, url)?.let(ServerStatus::Problem) ?: ServerStatus.Valid
    }

    fun save() {
        if (!canSave) return
        val url = ServerSettings.urlFrom(input)
        val previous = settings.serverURL.value
        settings.customURLString = url?.takeIf { it != settings.defaultURL }?.toString().orEmpty()
        if (settings.serverURL.value == previous) onReload()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_url_label)) },
                placeholder = { Text(ServerSettings.displayString(settings.defaultURL)) },
                trailingIcon = if (draft.isNotEmpty()) {
                    {
                        IconButton(onClick = { draft = "" }) {
                            Icon(Icons.Filled.Clear, stringResource(R.string.server_use_default))
                        }
                    }
                } else {
                    null
                },
                supportingText = when (val current = status) {
                    is ServerStatus.Problem -> {
                        { Text(current.message) }
                    }
                    ServerStatus.Checking -> {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Text(stringResource(R.string.server_checking))
                            }
                        }
                    }
                    else -> null
                },
                isError = status is ServerStatus.Problem,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = canSave) { Text(stringResource(R.string.server_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private sealed interface ServerStatus {
    data object Valid : ServerStatus
    /** Still being typed, or waiting for typing to pause. */
    data object Unchecked : ServerStatus
    data object Checking : ServerStatus
    data class Problem(val message: String) : ServerStatus
}

/** Checks that a server URL leads to a c3lingo frontend. */
private object ServerCheck {
    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }
    /** Every frontend declares the native bridge version, see FrontendBridge.js. */
    private val bridgeMeta = Regex("""<meta[^>]+name=["']?c3lingo-native-bridge""", RegexOption.IGNORE_CASE)

    /** Returns what's wrong, worded for the user, or null if the page is a c3lingo frontend. */
    suspend fun problem(context: Context, url: Uri): String? = withContext(Dispatchers.IO) {
        try {
            fetch(url).use { response ->
                when {
                    response.code >= 400 -> context.getString(R.string.server_http_error, response.code)
                    !bridgeMeta.containsMatchIn(response.body.string()) -> context.getString(R.string.server_not_c3lingo)
                    else -> null
                }
            }
        } catch (e: UnknownHostException) {
            context.getString(R.string.server_not_found)
        } catch (e: IOException) {
            context.getString(R.string.server_cant_connect, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fetch(url: Uri): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url.toString()).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        })
    }
}
