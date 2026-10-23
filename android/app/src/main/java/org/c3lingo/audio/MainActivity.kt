package org.c3lingo.audio

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var frontend: Frontend
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys control playback volume even while nothing is playing.
        volumeControlStream = AudioManager.STREAM_MUSIC

        frontend = Frontend(this, app.player, lifecycleScope)
        applyLaunchOptions(intent)
        setContent {
            C3lingoTheme {
                AppScreen(frontend, app.player, app.settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
        }

        lifecycleScope.launch {
            var first = true
            app.settings.serverURL.collect { url ->
                if (!first) app.player.stop()
                first = false
                frontend.load(url)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchOptions(intent)
    }

    override fun onStart() {
        super.onStart()
        // Connecting to the session starts the playback service, which then keeps itself
        // running in the foreground while a channel is playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        frontend.destroy()
        super.onDestroy()
    }

    /**
     * For testing: `adb shell am start -n org.c3lingo.audio/.MainActivity -e serverURL <url> -e autoplay <channel>`
     * uses another server for this run and selects a channel once the page has loaded.
     */
    private fun applyLaunchOptions(intent: Intent) {
        intent.getStringExtra("serverURL")?.let(app.settings::setOverride)
        intent.getStringExtra("autoplay")?.let { frontend.autoplay = it }
    }
}

@Composable
private fun AppScreen(frontend: Frontend, player: Player, settings: ServerSettings, onChangeServer: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    val loadError by frontend.loadError.collectAsStateWithLifecycle()
    val hasLoaded by frontend.hasLoaded.collectAsStateWithLifecycle()
    val serverURL by settings.serverURL.collectAsStateWithLifecycle()
    val playing = state.channel != null

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        FrontendArea(
            frontend, loadError, hasLoaded, serverURL, playing, onChangeServer,
            Modifier.weight(1f).fillMaxWidth(),
        )
        AnimatedVisibility(
            playing,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            PlayerBar(state, player)
        }
    }
}

/** The frontend, covered by the splash or error screen until it has loaded. */
@Composable
private fun FrontendArea(
    frontend: Frontend,
    loadError: String?,
    hasLoaded: Boolean,
    serverURL: Uri,
    playing: Boolean,
    onChangeServer: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        // The frontend stays clear of the status bar, display cutouts and, without the
        // player bar, the navigation bar.
        val insets = if (playing) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            WindowInsets.safeDrawing
        }
        AndroidView(
            factory = { frontend.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize().windowInsetsPadding(insets),
        )
        AnimatedVisibility(loadError != null, enter = fadeIn(), exit = fadeOut()) {
            LoadErrorView(loadError.orEmpty(), serverURL, onRetry = frontend::reload, onChangeServer = onChangeServer)
        }
        AnimatedVisibility(loadError == null && !hasLoaded, enter = fadeIn(), exit = fadeOut()) {
            SplashView()
        }
    }
}

/** Shown until the frontend has loaded: the logo centred on the screen, with progress below it. */
@Composable
private fun SplashView() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Image(
            painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(128.dp).align(Alignment.Center),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Place below the logo rather than over it.
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (constraints.maxWidth - placeable.width) / 2,
                        constraints.maxHeight / 2 + (64 + 32).dp.roundToPx())
                }
            },
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadErrorView(message: String, url: Uri, onRetry: () -> Unit, onChangeServer: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Icon(
            Icons.Outlined.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp).clearAndSetSemantics {},
        )
        Text(
            stringResource(R.string.load_error_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(
            url.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        TextButton(onClick = onChangeServer) { Text(stringResource(R.string.change_server)) }
    }
}
